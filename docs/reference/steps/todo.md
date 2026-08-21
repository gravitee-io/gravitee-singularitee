# `todo`

> Executes the server-owned plan tools (`set_todos`, `complete_todo`, `ask_user`) called by the preceding `infer` step and keeps the plan in the pipeline context.

## Overview

The presence of a `todo` step anywhere in a pipeline registers the todo tool definitions, so every `infer` step injects them (unless `server_tools: false`). `TodoStepExecutor` then reads the tool calls extracted by the previous `infer` step and splits them into server-owned calls and client-bound calls. Server-owned calls are executed against the plan on the `PipelineContext`: `set_todos` installs or non-destructively updates the plan and locks it, `complete_todo` marks an item done with its `note` as proof, `ask_user` pauses. Each executed call is appended to the conversation as an assistant tool-call turn followed by a tool result turn, removed from the calls sent to the client, and a `PROGRESS` event with the plan snapshot is streamed. Then:

- an `ask_user` call streams its question as the visible answer and halts with `BREAK_CONDITION` (plain `stop` over HTTP), the plan being saved for the next turn;
- remaining client-bound calls halt with `TOOL_CALLS` so the client executes them, with any narration the generating step produced streamed first;
- otherwise execution continues to `handled_step` (or `next_step` when unset);
- with no todo call at all and no client call, the step falls through to `next_step`.

## Usage

From `examples/pipelines/todo-agent.yaml`:

```yaml
- id: plan
  type: infer
  role: internal
  next_step: apply_plan
  config:
    model_id: llm
    system: "If the user's message is a real multi-step task, break it into 2-6 concrete steps and call the set_todos tool with them. Otherwise reply with the single word SKIP."

- id: apply_plan
  type: todo
  next_step: work
  config:
    handled_step: work

- id: work
  type: infer
  role: internal
  next_step: track
  config:
    model_id: llm
    system: "Plan status:\n{% for t in todos %}- [{{ t.status }}] ({{ t.id }}) {{ t.title }}{% endfor %}\nDo ONLY the item marked in_progress, then call complete_todo with its id and the complete result in the note."

- id: track
  type: todo
  next_step: work_gate
  config:
    handled_step: work_gate

- id: work_gate
  type: loop
  next_step: summarize
  config:
    loopback_step: work
    fallback_step: summarize
    max_iterations: 8
    condition:
      type: equals
      input_field: todos.remaining
      match_value: "0"
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `handled_step` | string | `next_step` | Step to branch to after consuming a plan call (usually the `infer` step that produced it, or the gate after it). Proto `handled_step_id`. |

## Context fields

Reads: the extracted tool calls of the previous `infer` step, the request tools (a caller-declared `ask_user` schema makes that tool client-bound), the plan, the session key (`cache_key`).

Writes:

| Field | Value |
| --- | --- |
| `todos.total`, `todos.completed`, `todos.remaining` | Numeric strings refreshed on every plan change; the usual loop-gate inputs. |
| `conversation.repeated_call` | Seeded by the executor at pipeline start for todo pipelines: length of the trailing run of identical assistant tool calls (`0` when no repeat), for gates that break behavioural loops. |
| `<id>.question` | The `ask_user` question; also the halt output field. |
| `<id>.client_tool_calls` | Number of client-bound calls when the step halts for them. |
| `<id>.todo_error` | The error text when a call's arguments failed to execute (the model receives it as the tool result too). |

Jinja additionally sees `todos` (list of `{id, title, status, proof}`) and `constraints` (the plan-level constraints paragraph). Both survive across turns through `previous_response_id` or the session key.

## Notes

- `set_todos` is refused while a plan is in progress, or when it is finished and the request did not open with a fresh user message; the model receives an explanatory error. An empty list is refused as well.
- Re-sending the whole list to `set_todos` keeps each existing item's status unless a valid `status` is given, so a verbatim re-send never resets done items.
- `complete_todo` on an already-done item returns an error naming the current `in_progress` item instead of a silent no-op.
- A pipeline with a `todo` step should keep `infer` steps that must produce prose on `server_tools: false`; a tool schema in the prompt invites a call that a later tag-less step would leak as text.
- Session persistence needs a `cache_key` on the request (`user` or `prompt_cache_key` over HTTP) and `ai.todos.session-ttl` above `0`.

## See also

- [Todos guide](../../guides/todos/README.md)
- [`infer`](./infer.md), [`loop`](./loop.md)
- [Context fields](../context-fields.md)
