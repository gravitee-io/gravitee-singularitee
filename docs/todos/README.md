# Engine-managed to-dos

> Plan-and-execute inside a pipeline: the model decomposes work with server-executed
> `set_todos` / `complete_todo` tools, loops until the plan is done, and clients follow along
> through streamed progress events.

## Overview

A pipeline that contains a `todo` step gets three capabilities at once:

1. **Plan-and-execute** — the model calls `set_todos` to decompose the task; the plan lives
   in the pipeline context and survives loop iterations. Prompts render it via `{{todos}}`;
   loop conditions gate on `todos.remaining`.
2. **Server-executed tools** — `set_todos` and `complete_todo` are injected into every infer
   step's tool list automatically, and the *server* executes them: the call and its result
   are appended to the transcript as a proper assistant/tool turn pair, and the calls never
   reach the client (a purely internal generation reverts to `finish_reason: stop`).
3. **Client-visible progress** — every plan mutation emits a `RESPONSE_EVENT_TYPE_PROGRESS`
   event carrying the full plan snapshot. gRPC clients receive it as-is; the OpenAI Responses
   API streams it as a `"type": "gravitee.progress"` object; Chat Completions drops it
   (strict OpenAI compatibility).

## Key types

| Type | Where | Purpose |
| --- | --- | --- |
| `STEP_TYPE_TODO` / `TodoStepConfig` | `pipeline.proto` | The step: consumes todo calls, branches to `handled_step` after consuming, falls through to `next_step` otherwise. |
| `TodoStepExecutor` | `engine` | Executes the calls, records transcript turns, clears consumed calls, emits PROGRESS. |
| `TodoTools` | `engine` | The synthetic `set_todos` / `complete_todo` tool definitions. |
| `PipelineContext.TodoItem` | `engine` | `{id, title, status}`; status: `pending` → `in_progress` → `done`. |
| `ResponseProgress` / `TodoItem` | `inference.proto` | The streamed progress payload. |

## Usage

```yaml
steps:
  - id: plan                       # ask the model to call set_todos
    type: infer
    role: internal                 # tool spans never stream; use PROGRESS instead
    next_step: apply_plan
    config: { model_id: llm, output_field: plan.output }

  - id: apply_plan                 # server executes the call
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
      prompt:
        messages:
          - role: user
            content: "Plan:\n{% for t in todos %}- [{{ t.status }}] {{ t.title }}\n{% endfor %}\nDo the in_progress item, then call complete_todo."

  - id: track
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
```

Runnable end-to-end version: `examples/pipelines/todo-agent.yaml`.

## Options

### `todo` (`TodoStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `handled_step` | `next_step` | Step to branch to after consuming a todo tool call (typically back to the infer step, or on to a gate). When the generation contained no todo call, execution always follows `next_step`. |

### Context fields (written on every mutation)
| Field | Purpose |
| --- | --- |
| `todos.total` / `todos.completed` / `todos.remaining` | Numeric strings for loop/break conditions. |
| `<step>.todo_error` | Set when a call's arguments failed to parse (the model also receives the error as the tool result). |

### Jinja variables
| Variable | Purpose |
| --- | --- |
| `todos` | The live plan as `[{id, title, status}]`, renderable in any prompt or loopback message. |
| `constraints` | The plan-level constraints paragraph recorded by `set_todos` (empty string when none) — re-inject it into work/summarize prompts so locked user decisions survive long work loops. |

### Server tools (auto-injected)
| Tool | Arguments | Effect |
| --- | --- | --- |
| `set_todos` | `todos: [{id, title}]` (plain strings tolerated), `constraints?` (string) | Replaces the plan; first item becomes `in_progress`. `constraints` records the user's locked decisions; a re-send without it keeps the existing ones. Persisted with the plan across turns. |
| `complete_todo` | `id`, `note?` | Marks the item `done`; the next `pending` item becomes `in_progress`. Unknown ids return an error result to the model. |
| `ask_user` | `question` | Pauses for the user: the question streams as the visible assistant answer, the turn ends with `finish_reason: "stop"`, and the plan is saved for the session. |

Individual infer steps can opt out of the injection with `server_tools: false`
(e.g. a prose-only summarize step that must never call `set_todos`) — see the
infer `## Options` table in [Text generation](../text-generation/README.md).

### Session persistence (`gravitee.yml`)
| Key | Default | Purpose |
| --- | --- | --- |
| `ai.todos.session-ttl` | `1800` | Session idle timeout in seconds; `0` disables cross-turn persistence. |
| `ai.todos.session-max-entries` | `10000` | Upper bound on concurrently tracked sessions. |

## Pausing for the user (`ask_user`)

### Client-owned ask_user (multiple choice)

Declare a tool named `ask_user` in the request's `tools[]` — with your OWN schema, e.g.
`{questions: [{question: string, options: string[]}]}` — and the server delegates: the
model sees your schema (the server's is suppressed for the request), the call rides out
as a normal `function_call` item with `finish_reason: "tool_calls"`, your UI renders the
choices, and the selections return as a standard `function_call_output`. Pure OpenAI
Responses — no vendor events. Without the declaration you get the default behavior
below (question streamed as text, turn ends with `"stop"`). Only `ask_user` is
delegable; declaring `set_todos`/`complete_todo` does not take over the plan.

Dependent questions compose in the same schema: add optional `depends_on` (the index of
the controlling question) and `options_by_answer` (a map of that question's answer →
options list) to the question object — the model groups tooling options per language
("Python" → uv/pip, "Rust" → cargo) and the UI reveals the right set once the
controlling answer is picked. Still one round, still pure client schema.

A plan whose step needs the user — "step 3 of 5 requires their preference" — pauses cleanly:
the model calls `ask_user`, the todo step streams the question as ordinary assistant content,
and the pipeline halts with `finish_reason: "stop"`. Any stock OpenAI client just displays
the question; the user's reply arrives as the next message of the conversation.

**Recovery, preferred path (Responses API)**: stored conversations. Every pipeline response
on `/v1/responses` carries a `resp_…` id and is stored server-side (transcript *and* todo
plan); the next turn sends `previous_response_id` + only the new `input`, and the pipeline
resumes with server-curated history — the full OpenAI continuation model, no client message
replay. See [OpenAI HTTP API](../openai-http-api/README.md).

**Recovery, fallback (Chat Completions or key-based)**: the request's `cache_key` — on the
OpenAI surface, `prompt_cache_key` (falling back to `user`). When the next request carries
the same key, the pipeline restores the plan exactly as it paused (items 1–2 `done`, item 3
`in_progress`) and continues. A completed plan clears its session so it cannot leak into an
unrelated conversation under the same key. **Clients that send no session key and no
`previous_response_id` get no recovery** — the plan then lives only within one request.

The store is the pluggable gravitee-node cache: standalone in-memory by default, swappable
for the Hazelcast/Redis cache plugins by replacing the `CacheManager` bean — sessions then
survive process restarts and are shared across a multi-node deployment. (The same manager
backs the KNN route-embedding cache; process-local caches like the llama.cpp slot cache and
compiled-template caches deliberately stay in-process.)

## Notes

- **Plans are locked automatically.** Installing a plan locks `set_todos`; the lock lifts
  only at request restore when every item is `done` AND the request opens with a fresh user
  message (a tool-result continuation is the same run still executing). A mid-run
  `set_todos` — a model that lost the plan install to context trimming, or one chaining a
  finished plan into a new one — gets a refusal as the tool result; `complete_todo` also
  falls back to the single `in_progress` item when the id doesn't match. Plans are authored
  on human input only.
- **Use `role: internal` on todo-calling infer steps.** Tool-call spans of `output` steps
  stream to the client as TOOL deltas; internal steps stay silent and the client follows the
  plan through PROGRESS events instead.
- **Client-bound tool calls end the turn.** Whenever the generation contains calls to the
  client's own tools (alone or mixed with todo calls), the todo step executes the todo calls
  server-side and then HALTS the pipeline with `finish_reason: tool_calls` and the client
  calls attached — the client must execute them and reply. Looping onward would swallow the
  call (and a later tag-less step would leak its raw span as text). Agent clients driving a
  todo pipeline therefore get standard tool-use turns; the plan survives across them via
  stored conversations / the session key.
- **A purely internal tool generation ends as `stop`.** The consumed calls are removed and
  the finish reason reset, so nothing leaks into the OpenAI surface.
- **Progress on the wire.** gRPC: `InferResponse` with `RESPONSE_EVENT_TYPE_PROGRESS` and a
  `ResponseProgress` payload. Responses API: `{"type":"gravitee.progress","step_id":…,"text":"1. [x] …\n2. [>] …",
  "todos":[…],"completed":n,"total":n}` with a sequence number. Chat Completions: dropped.
- **Why a custom event and not output items.** The OpenAI-native way to surface server-side
  tool activity is Responses *output items* (`response.output_item.added/done` — how OpenAI
  streams its own built-ins like `web_search_call`). We deliberately do NOT use them for the
  todo tools: output items are part of the response object and therefore of conversation
  state — SDKs and the `previous_response_id` flow carry them into the next turn's input, so
  the internal `set_todos`/`complete_todo` calls would leak into the client's transcript and
  be replayed back as history, breaking the never-leak invariant this step enforces. A
  `gravitee.`-namespaced side-channel event keeps plan state out of conversation history
  while following the same `type`+`sequence_number` envelope as canonical events, which
  conforming clients skip when unknown. (An `ask_user` question, by contrast, IS meant to be
  conversation state — which is why it streams as ordinary assistant content, not as a
  progress event.)
- **Bound the work loop.** `max_iterations` on the gate is the safety net against a model
  that never calls `complete_todo`; pair it with a `fallback_step`.
- **`ask_user` wins over `handled_step`.** When a generation contains an `ask_user` call,
  the pipeline halts for the turn instead of looping — the remaining plan items run on the
  next request.

## See also

- [Loops & CoT](../loops-and-cot/README.md) — the loop/break conditions the plan gates use.
- [Text Generation](../text-generation/README.md) — infer step configuration and tool tags.
- [OpenAI HTTP API](../openai-http-api/README.md) — the Responses API surface that carries
  `gravitee.progress`.
- [gRPC API & Client](../grpc-api-and-client/README.md) — consuming raw `InferResponse`
  events including PROGRESS.
