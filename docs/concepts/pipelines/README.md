# Pipelines

> A pipeline is a directed graph of steps walked over a shared per-request context: one entry step, `next_step` edges, and a handful of steps that choose their own target (route, loop, todo). This page explains the model; the keys of every step live in the [step reference](../../reference/steps/README.md).

## Overview

A pipeline is declared in workspace YAML as `entry` plus a list of `steps`, compiled to the
`Pipeline` proto at load time and executed by `PipelineExecutor`. Each step is dispatched to
its `StepExecutor`, which reads and writes the `PipelineContext` and returns the id of the
next step, or nothing, which ends the walk. Callers invoke a pipeline with the `InferPipeline`
RPC, or over HTTP by passing the pipeline id as `model`, and receive one stream of
`InferResponse` events.

Twelve step types exist: `infer`, `classify`, `embed`, `route`, `guard`, `llm_guard`,
`regex_guard`, `break`, `loop`, `sub_pipeline`, `tool_select`, `todo`. Only `infer` (and the
`todo` step, for an `ask_user` question or narration) writes to the client stream; every other
step is silent and only mutates the context.

## Key types

| Type | Module | Role |
| --- | --- | --- |
| `Pipeline`, `PipelineStep`, `StepType`, `StepRole` | `protocol` (`pipeline.proto`, `inference.proto`) | The compiled graph. |
| `PipelineExecutor` | `engine` | Builds the context from the request, emits `CREATED`, walks the graph, ends the stream with a finish reason. Also the local `sub_pipeline` callback. |
| `PipelineContext` | `engine` | Per-request scratchpad: string fields, `messages()`, `generatedMessages()`, `verdicts()`, tools, todo plan, usage totals, halt state. |
| `StepDispatcher`, `StepExecutorFactory`, `StepExecutor<C>` | `engine` | Type-to-executor table; each step runs inside an `ai.step` span. |
| `StepContext` | `engine` | What every executor receives: the context, the pipeline, the response stream, tracer and metrics, and `rxNextStep(stepId)` which reads the step's `next_step` edge. |
| `BreakStepEvaluator`, `ConditionEvaluatorFactory` | `engine` | The shared `condition:` block of `break` and `loop`. |
| `JinjaRenderer`, `JinjaContextHelper` | `engine` | Render `{{ ... }}` templates in step configs against the context. |

## Usage

The smallest useful graph: a guard, then generation.

```yaml
workspace:
  pipelines:
    - id: guard
      entry: toxicity_guard
      steps:
        - id: toxicity_guard
          type: guard
          next_step: generate
          config:
            model_id: toxicity
            input_field: prompt
            action: reject
            trigger: { label: toxic, score: 0.75 }
            message: "Blocked (label: {{toxicity_guard.label}}, score: {{toxicity_guard.score}})."
        - id: generate
          type: infer
          role: output
          config:
            model_id: llm
            output_field: generate.output
```

What happens on a request:

1. `PipelineContext.fromRequest` seeds `prompt` (the last user message), `messages` (the
   caller's turns), `tools`, `cache_key` and any `context` map entries. `CREATED` is emitted.
2. `toxicity_guard` writes `toxicity_guard.label` and `toxicity_guard.score`. On a hit it calls
   `signalHalt(..., FINISH_REASON_GUARD_BLOCKED)`; the walk stops at the next check.
3. Otherwise the step returns `generate`. The infer step streams deltas tagged
   `STEP_ROLE_OUTPUT`, writes `generate.output`, appends an assistant turn, records usage.
4. `generate` has no `next_step`, so the walk ends and `COMPLETED` carries the engine's last
   finish reason (`STOP`, `LENGTH`, `TOOL_CALLS`).

```bash
./run-server.sh --workspace examples/pipelines/guard.yaml
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"guard","prompt":"Hello there"}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

### Edges and termination

- `next_step` on a step is its only outgoing edge. `route` names a target per rule plus
  `default_step`; `loop` names `loopback_step` and `fallback_step`; `todo` names `handled_step`.
- The order of `steps:` is irrelevant. Execution is defined by `entry` and the edges.
- A walk ends when a step returns no next step (no `next_step`, or a `break` whose condition
  is met), when the context is halted, or when a step throws (`StepDispatcher` logs and
  completes; the client gets `COMPLETED` with whatever streamed). An unknown step id halts
  with a warning.

### Roles and the event stream

`role:` is meaningful on `infer` steps only. `output` streams deltas as `STEP_ROLE_OUTPUT`,
`thinking` as `STEP_ROLE_THINKING`, `internal` streams nothing (unless `stream_thinking`
forwards the reasoning channel). Tool-call spans stream as `STEP_ROLE_TOOL` regardless of
role. The stream is `CREATED`, then `OUTPUT_TEXT_DELTA*` (each with `step_role`), optional
`PROGRESS` events from a `todo` step, then exactly one of `COMPLETED` or `FAILED`.

### Finish reasons

The final reason is chosen in this order: the halt reason if a step halted, else the last
engine finish reason recorded by an infer step, else `STOP`.

| `FinishReason` | Raised by | HTTP `finish_reason` |
| --- | --- | --- |
| `STOP` | Graph exhausted, engine stopped on EOS or a stop string | `stop` |
| `LENGTH` | Engine hit `max_tokens` or the context window | `length` |
| `TOOL_CALLS` | Tool calls extracted from an infer step, or a `todo` step ending the turn for client-bound calls | `tool_calls` |
| `GUARD_BLOCKED` | `guard`, `llm_guard`, `regex_guard` with `action: reject`; a failed child pipeline | `content_filter` (sent as a `FAILED` event with `error_code: content_filter`) |
| `BREAK_CONDITION` | `break` condition met; `todo` step pausing on `ask_user` | `stop` |
| `MAX_ITERATIONS` | Defined on the wire; `loop` branches to `fallback_step` instead of raising it | `stop` |
| `CANCELLED`, `STALLED` | Engine: client went away, or the backend failed mid-generation | `stop` |

HTTP keeps the OpenAI closed set, so everything not listed in the right column maps to
`stop`. The gRPC stream carries the raw enum.

### Context fields and failure signals

Every field is a string. Steps write under their own id prefix (`generate.output`,
`toxicity_guard.score`, `route.label`), and also publish how they finished so a later
`loop` or `break` can react: `<step>.finish_reason`, `<step>.tool_parse_failed`,
`<step>.thinking_unclosed`, `<loop>.iterations`, `<loop>.max_iterations_reached`,
`<route>.matched`, `todos.remaining`. The complete list is in
[Context fields](../../reference/context-fields.md). Jinja templates see the same data as
`{{generate.output}}` plus `prompt`, `system`, `history`, `messages`, `generated_messages`,
`verdicts`, `tool_names`, `todos` and `constraints`.

### How messages accumulate

`PipelineContext.messages()` starts as the caller's turns and grows during the walk:

- an `infer` step with `role: output` or `thinking` appends its generation as an assistant
  turn; `role: internal` does not (its output still lands in `output_field` and
  `generated_messages`);
- a `loop` step appends its rendered `loopback_message` on the retry edge only;
- a `todo` step appends the assistant call and a tool-result turn for every server tool it
  executed;
- an infer step with no `prompt:` uses the accumulated messages as its prompt, which is what
  makes a loop back into it a continued conversation.

`<step>.output` is overwritten on each iteration; `generated_messages` keeps every one.

### Sub-pipeline propagation

A `sub_pipeline` step sends the child a snapshot of the parent's context map and the parent's
tools, plus either a flat prompt or the whole message list. The child's events are forwarded to
the caller as they arrive, its usage is added to the parent totals, and a child finish reason
other than `STOP` is re-signalled on the parent, so a guard two levels down still ends the
top-level response. See [Sub-pipelines](../../guides/sub-pipelines/README.md).

## Options

Envelope keys only; per-step keys are in the [step reference](../../reference/steps/README.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `id` | string | required | What callers pass as `model` / `pipeline_id`. |
| `entry` | string | required | Id of the first step. |
| `steps[].id` | string | required | Step id and context-field prefix. |
| `steps[].type` | string | required | One of the twelve step types. |
| `steps[].role` | `output` / `thinking` / `internal` | `output` | `infer` only. |
| `steps[].next_step` | string | unset | Outgoing edge; unset makes the step terminal. |
| `steps[].config` | map | required | Type-specific block. |
| `task`, `visible`, `modalities` | publication | derived | See [Workspaces](../../workspaces/README.md). |
| `server` | string | unset | Declares the pipeline as a proxy to one on a remote endpoint. |

## Notes

- `PipelineContext.set` ignores null values; a step that produced nothing leaves the field
  unset, which `empty` / `not_empty` conditions can test.
- Halt checks run before and after every step, so a halt inside a loop body or a child
  pipeline stops the parent at the next edge.
- With OpenTelemetry enabled each run opens an `ai.pipeline` span and each step an `ai.step`
  span (`step.id`, `step.type`); model calls nest under the active step.
- Pipelines with a `todo` step get the server tools registered on the context before the walk,
  and may restore a plan from a stored conversation or a session key.

## See also

- [Step reference](../../reference/steps/README.md): every key of every step type.
- [Context fields](../../reference/context-fields.md): every field steps read and write.
- [Text generation](../../guides/text-generation/README.md): the `infer` step in depth.
- [Loops and chain-of-thought](../../guides/loops-and-cot/README.md): `loop` and `break`.
- [Tool calling](../../guides/tool-calling/README.md): tools through a pipeline.
- [gRPC API](../../api/grpc/README.md) and [HTTP API](../../api/http/README.md): how to call a pipeline.
