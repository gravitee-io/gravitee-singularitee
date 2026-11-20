# Step types

> One page per pipeline step type: the YAML keys each step accepts, what it reads from and writes to the pipeline context, and how it picks the next step.

## Overview

A pipeline is `entry` plus a list of `steps`. `PipelineExecutor` walks the graph one step at a time: `StepDispatcher` looks up the executor the step's `type` plugin registered, runs it inside the platform decorator chain (tracing, diagnostics, then plugin decorators) against the shared `PipelineContext`, and takes either the id of the next step or nothing (terminal). Every step type is a plugin (`gravitee-singularitee-plugins/`): the plugin's `StepConfigCodec` parses the YAML `config:` block at load and its `StepExecutor` runs it. The graph is a Java model (`PipelineModel`/`StepModel`), never a wire type.

| `type` | Executor | Purpose | Model-bound | Next step |
| --- | --- | --- | --- | --- |
| [`infer`](./infer.md) | `InferStepExecutor` | Streams a generation from a text-generation model. | yes (`TextGenEngine`) | `next_step`, or terminal. |
| [`classify`](./classify.md) | `ClassifyStepExecutor` | Writes the top label and score of a classifier. | yes (`ClassifierEngine`) | `next_step`, or terminal. |
| [`embed`](./embed.md) | `EmbedStepExecutor` | Writes an embedding vector. | yes (`EmbeddingEngine`) | `next_step`, or terminal. |
| [`route`](./route.md) | `RouteStepExecutor` | Dispatches to the step named by the matching rule. | classifier or embedding (`llm_structured` and `decision` use none) | The rule's `next_step`, else `default_step`, else terminal. |
| [`guard`](./guard.md) | `GuardStepExecutor` | Classifier-based reject / warn / redact. | yes (`ClassifierEngine`) | `next_step`; `reject` halts the pipeline. |
| [`llm_guard`](./llm_guard.md) | `LlmGuardStepExecutor` | LLM-as-judge verdict, reject or warn. | yes (`TextGenEngine`) | `next_step`; `reject` halts the pipeline. |
| [`loop`](./loop.md) | `LoopStepExecutor` | Bounded back-edge on a condition. | no | `next_step` on exit, `loopback_step` on retry, `fallback_step` when exhausted. |
| [`break`](./break.md) | `BreakStepExecutor` | Halts the pipeline when a condition is met. | no | `next_step` when not met; terminal when met. |
| [`sub_pipeline`](./sub_pipeline.md) | `SubPipelineStepExecutor` | Runs another pipeline, locally or on a remote server. | no (delegates) | `next_step`, or terminal. A non-`STOP` sub-result halts the parent. |
| [`regex_guard`](./regex_guard.md) | `RegexGuardStepExecutor` | Model-free pattern reject / warn / redact. Deprecated in favour of a `regex` model behind `guard`. | no | `next_step`; `reject` halts the pipeline. |
| [`tool_select`](./tool_select.md) | `ToolSelectStepExecutor` | Shortlists the request's tools with a zero-shot classifier. | yes (`ClassifierEngine`) | `next_step`, or terminal. |
| [`decide`](./decide.md) | `DecideStepExecutor` | Answers typed questions (choice, score, noul) with a decision model. | yes (`DecisionEngine`) | `next_step`, or terminal. |
| [`todo`](./todo.md) | `TodoStepExecutor` | Executes the server-owned plan tools called by the previous `infer` step. | no | `handled_step` after a plan call; `next_step` otherwise; halts on `ask_user` or client tool calls. |

## Key types

- `StepDefinition` (`WorkspaceDefinition`): the YAML envelope; `config` is kept as a raw map and handed to the `StepConfigCodec` of the plugin that provides `type`, which parses and validates it at load.
- `StepTypes` (engine-api): the core type strings; `StepRoleKey` (workspace): YAML slug to `StepRole`.
- `StepModel` (engine-api): `id`, `type`, `role` and the plugin-owned `config` object.
- `StepExecutorPlugin` (plugin-api) / `StepConfigCodec` (engine-api): how a plugin contributes a type.
- `StepExecutor<C>` / `ModelBoundStepExecutor<C, E>`: the executor contract. Model-bound executors look the model up in `ModelRegistry` and check the engine type before running.
- `StepContext`: what every executor receives: the `PipelineContext`, the `PipelineModel` (for the `edges` map), the response stream, tracer and metrics.
- `BreakStepEvaluator` / `ConditionEvaluatorFactory` / `ScoreResolver`: the `condition:` block shared by `break` and `loop`.

## Usage

Every step has the same envelope:

```yaml
steps:
  - id: toxicity_guard        # [A-Za-z_][A-Za-z0-9_]*: it is also a Jinja identifier
    type: guard               # one of the thirteen slugs above
    next_step: generate       # edge to follow; omit for a terminal step
    config:                   # type-specific block, see the page for the type
      model_id: toxicity
      action: reject
      trigger: { label: toxic, score: 0.75 }

  - id: generate
    type: infer
    role: output              # output | thinking | internal
    config:
      model_id: llm
```

Step order in the list has no execution meaning. Execution is defined by `entry`, by `next_step` edges and by the targets named inside `route`, `loop` and `todo` configs.

## Options

### Step envelope

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | Step id. Must match `[A-Za-z_][A-Za-z0-9_]*` (the loader rejects anything else) because it prefixes the step's context fields (`<id>.output`) and is a Jinja identifier (`{{ generate.output }}`). |
| `type` | string | required | One of `infer`, `classify`, `embed`, `route`, `guard`, `llm_guard`, `loop`, `break`, `sub_pipeline`, `regex_guard`, `tool_select`, `todo`, `decide`, or any type an installed plugin provides. Case-insensitive. |
| `role` | string | unspecified | `output`, `thinking` or `internal`. Unknown values fall back to `output`. Omitted, the role is `STEP_ROLE_UNSPECIFIED`, see below. Streaming and conversation semantics apply to `infer`; `role: output` on any step type marks the step a pipeline's `task` is derived from. |
| `next_step` | string | unset | Linear edge, stored in `PipelineModel.edges`. Unset makes the step terminal. `loop` reads it as its exit edge. |
| `config` | object | per type | The type-specific block. Unknown keys are ignored (`@JsonIgnoreProperties(ignoreUnknown = true)`), so a typo silently drops the option. |

### `role` semantics (infer steps)

| `role` | Streamed to the client | Appended to `messages` | Typical use |
| --- | --- | --- | --- |
| `output` | yes, tagged `STEP_ROLE_OUTPUT` | yes, as an assistant turn | The answer. Also the step a pipeline's `task` is derived from (the last `role: output` step, else the entry step), and the only step a request's structured output constraint applies to. |
| unset | yes, tagged `STEP_ROLE_OUTPUT` | yes, as an assistant turn | Streams and appends like `output`, but is not the output step for `task` derivation and never receives the request's structured output constraint. |
| `thinking` | yes, tagged `STEP_ROLE_THINKING` | yes | Visible deliberation that the client renders as reasoning. |
| `internal` | no (only `stream_thinking: true` forwards its reasoning deltas) | no; its answer is kept as narration for a later tool-call halt | Graders, routers, planners whose verdict must not enter the conversation. |

A pipeline's `modalities` are not tied to a role: they are the union over every model-bound step.

In every role the full raw text is written to `output_field` and appended to `generated_messages`. A `thinking` or `output` step whose reasoning is routed (`tags.reasoning_*` without `strip_thinking`) appends only the answer part to the conversation.

### How the next step is chosen

1. The executor returns a step id. Linear steps return `PipelineModel.edges[id]` (the YAML `next_step`); `route`, `loop` and `todo` return their own targets.
2. An empty result ends the walk. The pipeline finishes with the last engine finish reason (`stop`, `length`, `tool_calls`, ...) or `FINISH_REASON_STOP`.
3. A halted context ends the walk regardless of the returned id: `break` halts with `BREAK_CONDITION`, guards with `GUARD_BLOCKED` (delivered as a `FAILED` event with `error_code: content_filter`), `todo` with `BREAK_CONDITION` (ask_user) or `TOOL_CALLS` (client tool calls).
4. A returned id that names no step logs a warning and ends the walk.
5. A model-bound step whose `model_id` is not loaded, or is bound to the wrong engine type, fails (`ModelBoundStepExecutor` returns an error) rather than being skipped, so a guard can never let a request through unchecked. The error ends the walk as in rule 6. `route` is the exception: it logs a warning and falls through to `default_step`.
6. An exception inside an executor is logged and ends the walk (`StepDispatcher` uses `onErrorComplete`); the client receives `COMPLETED` with whatever streamed so far.

### Conditions (`break` and `loop`)

| `condition.type` | Evaluates | Needs |
| --- | --- | --- |
| `equals` | field string-equals `match_value` | `match_value` |
| `contains` | field contains `match_value` | `match_value` |
| `label_equals` | same evaluator as `equals` | `match_value` |
| `score_above` | resolved score `>= threshold` | `threshold` |
| `score_below` | resolved score `< threshold` | `threshold` |
| `not_empty` | field is non-null and non-blank | nothing |
| `empty` | field is null or blank | nothing |

`input_field` is a flat context key (`generate.tool_parse_failed`, `todos.remaining`). For `score_above` / `score_below`, `ScoreResolver` reads `<input_field>.score`, then `<parent>.score` when `input_field` ends in a segment such as `.label`, then parses the field value itself as a float; anything unparsable counts as `0`. An unknown or blank `type` fails the workspace load. A step with no `condition` block never matches.

## Notes

- `match_value` is stored as text; YAML parses bare `YES`/`NO`/`true`/`false` as booleans and the loader maps them back to `YES`/`NO`. Quote the value to avoid surprises.
- All context fields are strings; numbers are written with `Long.toString` / `String.valueOf`, booleans as `"true"` / `"false"`.
- Threshold and score YAML values equal to `0` are treated as unset by the step codecs, so a trigger score of `0` means "any score".
- Step configs are plugin-owned Java records, not wire types: a page lists exactly the YAML keys its codec reads.

## See also

- [Pipelines concept](../../concepts/pipelines/README.md)
- [Context fields](../context-fields.md)
- [Templates](../templates/README.md)
- [Workspaces](../../workspaces/README.md)
