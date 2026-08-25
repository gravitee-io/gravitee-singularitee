# Engine-managed todos

> Plan-and-execute inside a pipeline: the model decomposes work with server-executed `set_todos` / `complete_todo` / `ask_user` tools, a loop runs until the plan is done, and clients follow along through streamed progress events.

## Overview

A pipeline that contains a `todo` step gets three things:

1. **Server tools.** `set_todos`, `complete_todo` and `ask_user` are registered on the
   context before the walk and injected into every infer step's tool list (opt out per step
   with `server_tools: false`). The `todo` step executes the calls, appends the assistant call
   and a tool-result turn to the transcript, and removes the calls so they never reach the
   client.
2. **A plan in context.** The plan survives loop iterations and, with a session key or a
   stored conversation, survives turns. Prompts render it with `{{todos}}`; loop conditions
   read `todos.remaining`.
3. **Progress events.** Every plan mutation emits `RESPONSE_EVENT_TYPE_PROGRESS` with the full
   plan. gRPC clients get the event as is; the Responses API streams it as a
   `gravitee.progress` object; Chat Completions drops it.

## Key types

| Type | Where | Purpose |
| --- | --- | --- |
| `TodoStepConfig` | `plugins/todo` | Consumes todo calls; goes to `handled_step` after consuming, `next_step` otherwise. |
| `TodoStepExecutor` | `engine` | Executes calls, records turns, clears consumed calls, emits `PROGRESS`, halts for `ask_user` or client-bound calls. |
| `TodoTools` | `engine-api` | The three tool definitions; `DELEGABLE = {ask_user}`. |
| `ServerToolNames` | `engine-api` | The deployment's display names for those tools (`ai.tools.*.name`), carried on the `PipelineContext`; canonical by default. |
| `PipelineContext.TodoItem`, `TodoStatus` | `engine` | `{id, title, status, proof}`; `pending`, `in_progress`, `done`. |
| `ResponseProgress`, `TodoItem` | `inference.proto` | The progress payload. |
| `TodoSessionStore`, `ConversationStore` | `engine` | Cross-turn persistence (session key, `previous_response_id`). |

## Usage

`examples/pipelines/todo-agent.yaml` (pipeline id `agent`, gpt-oss-20b with Harmony tags) is
the runnable version of this graph:

```yaml
steps:
  - id: plan_gate                  # continued turn with a restored plan: skip planning
    type: loop
    next_step: plan
    config:
      loopback_step: work
      fallback_step: work
      max_iterations: 1
      condition: { type: empty, input_field: todos.total }

  - id: plan                       # model calls set_todos (or replies SKIP)
    type: infer
    role: internal
    next_step: apply_plan
    config:
      model_id: llm
      output_field: plan.output
      system: "If the message is a multi-step task, break it into 2-6 steps and call set_todos. Otherwise reply SKIP."

  - id: apply_plan                 # server executes set_todos, emits PROGRESS
    type: todo
    next_step: work
    config: { handled_step: work }

  - id: work                       # do the in_progress item, call complete_todo
    type: infer
    role: internal
    next_step: track
    config:
      model_id: llm
      output_field: work.output
      system: "Plan:\n{% for t in todos %}- [{{ t.status }}] ({{ t.id }}) {{ t.title }}{% if t.proof %} result: {{ t.proof }}{% endif %}\n{% endfor %}Do ONLY the in_progress item, then call complete_todo with its id and the COMPLETE result in note."

  - id: track                      # server executes complete_todo / ask_user
    type: todo
    next_step: work_gate
    config: { handled_step: work_gate }

  - id: work_gate                  # loop until the plan is done
    type: loop
    next_step: summarize
    config:
      loopback_step: work
      fallback_step: summarize
      max_iterations: 8
      condition: { type: equals, input_field: todos.remaining, match_value: "0" }

  - id: summarize                  # prose only: no tool schemas at all
    type: infer
    role: output
    config:
      model_id: llm
      output_field: summarize.output
      inject_tools: false
      server_tools: false
      system: "Assemble the final answer from:\n{% for t in todos %}### {{ t.title }}\n{{ t.proof }}\n{% endfor %}"
```

The example adds the Harmony `tags:` and `tool_extraction_template: harmony` on each infer
step; see [Tool calling](../tool-calling/README.md).

```bash
./run-server.sh --workspace examples/pipelines/todo-agent.yaml
curl -sN localhost:8080/v1/responses -H 'content-type: application/json' -d '{
  "model": "agent", "stream": true,
  "input": "Write one haiku for each of the four seasons."
}' | grep gravitee.progress
```

Two-turn session over Chat Completions (same `prompt_cache_key` resumes the plan):

```bash
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "agent", "prompt_cache_key": "demo-1",
  "messages": [{"role": "user", "content": "Write a haiku about my favourite season. Ask me which it is first."}]}'
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "agent", "prompt_cache_key": "demo-1",
  "messages": [{"role": "user", "content": "Write a haiku about my favourite season. Ask me which it is first."},
               {"role": "assistant", "content": "Which season is your favourite?"},
               {"role": "user", "content": "Autumn."}]}'
```

### The server tools

| Tool | Arguments | Effect |
| --- | --- | --- |
| `set_todos` | `todos: [{id, title}]` (plain strings accepted), `constraints?` | Installs the plan; first item becomes `in_progress`. A re-send keeps the status of ids it already knows. Refused while a plan is locked. The result echoes the compact plan (`[x]` done / `[>]` in_progress / `[ ]` pending). |
| `complete_todo` | `id`, `note?` | Marks the item `done`; the next `pending` item becomes `in_progress`. `note` becomes the item's `proof`. Unknown id: the single `in_progress` item if there is one, else an error result. The result echoes the updated compact plan. |
| `ask_user` | `question`, `options?` | Streams the question (plus enumerated `options`, when given) as the visible answer, emits an `ask` elicitation on the progress event, ends the turn with `finish_reason: stop`, saves the plan. |

Tool results go back to the model as a tool turn; argument errors are returned as an error
result and written to `<step>.todo_error`.

### Pausing for the user

Default: `ask_user` streams the question as ordinary assistant content and the pipeline halts
with `BREAK_CONDITION`, which HTTP renders as `stop`. The structured question and its closed
`options` also ride the progress event as an `ask` elicitation, so a gateway can render a
choice dialog. The next user message continues the conversation. The tool names are
deployment-renamable (`ai.tools.ask-user.name` and siblings); delegation matches the
configured name.

Client-owned `ask_user`: declare a tool named `ask_user` in the request's `tools[]` with your
own schema (for example `{questions: [{question, options[]}]}`). The model sees your schema,
the call rides out as a normal tool call with `finish_reason: tool_calls`, and the answer
returns as a tool result. Only `ask_user` is delegable; declaring `set_todos` or
`complete_todo` does not take over the plan.

### Keeping the plan across turns

- **Responses API.** Every response carries an id and is stored (transcript and plan). Send
  `previous_response_id` with only the new `input`; the plan and the server-curated history
  come back. An unknown id fails the request (`previous_response_not_found`).
- **Session key.** `cache_key` on gRPC; `prompt_cache_key` (falling back to `user`) on HTTP.
  The plan is restored as it paused. A finished plan clears its session.
- Neither key: the plan lives for one request.

The store is the gravitee-node cache manager: in-memory by default, swappable for a
distributed cache plugin.

## Options

Full tables: [`todo`](../../reference/steps/todo.md), [`infer`](../../reference/steps/infer.md),
[Configuration](../../reference/configuration.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `todo.handled_step` | string | `next_step` | Target after consuming a todo call. Without a todo call the step always follows `next_step`. |
| `infer.server_tools` | bool | `true` | Inject the server tools into this step. `false` for prose-only steps. |
| `infer.inject_tools` | bool | `true` | Inject the caller's tools into this step. |
| `infer.stream_thinking` | bool | `false` | Forward reasoning deltas from an internal step so UIs can show live deliberation. |
| `ai.todos.session-ttl` | seconds | `1800` | Session idle timeout; `0` disables key-based persistence. |
| `ai.todos.session-max-entries` | int | `10000` | Cap on tracked sessions. |
| `ai.conversations.ttl` | seconds | `3600` | Stored-conversation idle timeout; `0` disables. |

Context fields: `todos.total`, `todos.completed`, `todos.remaining` (numeric strings),
`conversation.repeated_call` (length of the trailing run of identical tool calls),
`<step>.todo_error`, `<step>.question`, `<step>.client_tool_calls`. Jinja variables: `todos`
(list of `{id, title, status, proof}`) and `constraints` (string).

## Notes

- Plans lock on install. `set_todos` is refused mid-run; the lock lifts only when a request
  restores a finished plan and opens with a fresh user message.
- Use `role: internal` on planning and working steps. Tool spans of `output` steps stream as
  tool deltas; internal steps stay silent and the client follows `PROGRESS` instead. The
  visible words an internal step wrote before a client-bound call are kept as narration and
  streamed when the turn ends.
- Client-bound calls end the turn. When a generation calls the caller's own tools (alone or
  mixed with todo calls), the todo step executes the todo calls and halts with
  `finish_reason: tool_calls`; the client executes its calls and replies. The plan survives
  through the session key or `previous_response_id`.
- A generation that contained only todo calls ends as `stop`: the calls are removed and the
  finish reason reset.
- Progress is a side channel, not an output item, so the internal calls never become client
  conversation state and are never replayed as history. `ask_user` questions are conversation
  state and stream as content.
- Bound the work loop with `max_iterations` and a `fallback_step`; `ask_user` wins over
  `handled_step`.
- Keep the live plan at the context tail, not in the `system:` prompt. The system prompt anchors
  the KV prefix cache that `prompt_cache_key` routing reuses, so rendering the mutating `{{ todos }}`
  there re-prefills the whole prompt every turn. The `set_todos`/`complete_todo` results already
  echo the compact plan on the (tail) TOOL turn, so the model sees its current plan each turn with
  no prefix-cache cost; render `{{ todos }}` in a `system:` block only when the plan is small and
  the extra prefill is acceptable.

## See also

- [Tool calling](../tool-calling/README.md): extraction, tags and dialect templates.
- [Loops and chain-of-thought](../loops-and-cot/README.md): the gates the plan loop uses.
- [HTTP API](../../api/http/README.md): `gravitee.progress`, `previous_response_id`, `prompt_cache_key`.
- [gRPC API](../../api/grpc/README.md): consuming `PROGRESS` events.
