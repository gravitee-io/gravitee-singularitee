# Pipelines

> Compose models into a reactive DAG of steps — guards, classifiers, routers, loops, and streaming generation — walked step-by-step by `PipelineExecutor` over a shared `PipelineContext` scratchpad.

## Overview
A pipeline is `entry` + a list of `steps` declared in workspace YAML and compiled to the `Pipeline` proto. `PipelineExecutor` walks the DAG as a recursive `flatMapCompletable` chain — no blocking, no latches. Each step is dispatched by `StepDispatcher` to a typed `StepExecutor` that returns a `Maybe<String>` emitting the next step id; an empty `Maybe` (or a missing `next_step` edge) makes the step terminal. Steps communicate through the `PipelineContext` scratchpad: the entry `prompt`, per-step outputs under `{step_id}.output`, the running `messages` conversation, and accumulated token usage. A pipeline is invoked with the `InferPipeline` RPC (or via the OpenAI HTTP API using the pipeline id as the model name) and streams `InferResponse` events: `CREATED → OUTPUT_TEXT_DELTA* → COMPLETED | FAILED`.

## Key types
- `PipelineExecutor` — reactive DAG walker; emits the `CREATED` event, recurses `walkStep`, and ends the stream with the right `FinishReason`. Implements `SubPipelineStepExecutor.PipelineExecutorCallback` for local sub-pipelines.
- `PipelineContext` — mutable per-request scratchpad: `get`/`set` string fields, `messages()` (`List<ChatTurn>`), `generatedMessages()`, `verdicts()`, loop `incrementIteration`, halt signalling (`signalHalt`, `haltReason`, `haltMessage`), and `accumulateUsage` for `TokenUsage`/`InferencePerformance` totals.
- `StepDispatcher` — routes a `PipelineStep` to its handler by `StepType`; wraps each step in an `ai.step` OTel span; unhandled errors terminate the pipeline (`onErrorComplete`).
- `StepExecutorFactory` — builds the `Map<StepType, StepExecutor<?>>` handler table (one executor per step type).
- `StepExecutor<C>` / `ModelBoundStepExecutor<C, E>` — the per-step contract: `extractConfig(PipelineStep)` then `rxExecute`; model-bound executors resolve the engine from `ModelRegistry`.
- `StepContext` — per-walk state passed to every step: `PipelineContext`, the `Pipeline`, the response `WriteStream<InferResponse>`, the caller Vert.x `Context`, tracer/metrics, and `rxNextStep(stepId)` which reads the `edges` map.
- `ConditionEvaluatorFactory` / `ContextAwareConditionEvaluator` — builds the condition predicates used by `break`, `loop` and route rules.
- `JinjaRenderer` / `JinjaContextHelper` — compiled-and-cached Jinja2 rendering of `{{expr}}` templates in step configs against the pipeline context.
- `StreamRegistry` / `TokenCaptureStream` — per-model, per-sequence token delivery: the capture stream accumulates the step output, forwards deltas to the client with the step's `StepRole`, and handles thinking-tag routing/stripping.

## Step types

| YAML `type` | Executor | What it does |
| --- | --- | --- |
| `infer` | `InferStepExecutor` | Streams LLM generation; writes `{step_id}.output`, appends an assistant turn (unless `role: internal`). |
| `classify` | `ClassifyStepExecutor` | ONNX/GLiNER classification; writes top label to `output_field` and score to `{output_field}.score`. |
| `embed` | `EmbedStepExecutor` | Embedding model; writes the vector as a JSON float-array string to `output_field`. |
| `route` | `RouteStepExecutor` | Classifies/embeds the input and jumps to the rule whose `label` matches (`classifier`, `embedding_knn`, or `llm_structured` strategy), else `default_step`. |
| `guard` | `GuardStepExecutor` | Input-side classifier safety check; `reject`/`warn`/`redact` on any matching `triggers[]` entry. |
| `llm_guard` | `LlmGuardStepExecutor` | LLM-as-judge verdict (Llama-Guard style); safe iff the first token equals `safe_token`. |
| `regex_guard` | `RegexGuardStepExecutor` | Model-free pattern guard; named `patterns[]` for reject/warn or span-merged redaction. |
| `break` | `BreakStepExecutor` | Conditional early halt; returns `output_field` with `FINISH_REASON_BREAK_CONDITION`. |
| `loop` | `LoopStepExecutor` | Bounded back-edge to `loopback_step` until the condition is met or `max_iterations`; optional `loopback_message` injected into the conversation on each retry. |
| `sub_pipeline` | `SubPipelineStepExecutor` | Invokes another published pipeline (locally or on a named remote `server`) as a nested sub-graph. |

## Usage
A complete guard-then-generate pipeline (`examples/modular/pipelines/toxicity-guard.yaml`):

```yaml
workspace:
  pipelines:
    - id: toxicity-guard-pipeline
      name: Toxicity Guard Pipeline
      entry: toxicity_guard              # step_id of the first step

      steps:
        - id: toxicity_guard
          type: guard
          next_step: generate            # edge: guard → generate
          config:
            model_id: toxicity           # logical id of a classifier model
            input_field: prompt          # read the entry prompt
            action: reject
            trigger:
              label: toxic
              score: 0.75
            message: "Your request was blocked because toxic content was detected (label: {{toxicity_guard.label}}, confidence: {{toxicity_guard.score}})."

        - id: generate
          type: infer
          role: output                   # stream tokens as OUTPUT; terminal (no next_step)
          config:
            model_id: llm
            output_field: generate.output
            prompt:
              messages:
                - role: user
                  content: "{{prompt}}"
            sampling:
              max_tokens: 512
```

Walkthrough:
1. `PipelineExecutor.executePipeline` builds a `PipelineContext` from the request (`prompt` = last user message), emits `CREATED`, and starts at `entry: toxicity_guard`.
2. `GuardStepExecutor` runs the `toxicity` classifier on the value of `input_field: prompt`. It writes `toxicity_guard.label` and `toxicity_guard.score` into the context. If the `toxic` label scores ≥ 0.75, it calls `pctx.signalHalt(...)` with `FINISH_REASON_GUARD_BLOCKED` and renders `message` with Jinja — the executor sees `isHalted()` and stops the walk. The client receives a `FAILED` event with `error_code: "content_filter"`.
3. Otherwise the guard emits `next_step: generate` and `InferStepExecutor` renders the message list through the model's chat template, streams tokens tagged `STEP_ROLE_OUTPUT`, writes the full text to `generate.output`, appends an assistant `ChatTurn`, and accumulates usage.
4. `generate` has no `next_step`, so the `Maybe` completes empty; the pipeline ends with `COMPLETED` and `FINISH_REASON_STOP` (or the engine's last finish reason, e.g. `LENGTH`).

Run it:
```shell
grpcurl -plaintext -d '{"pipeline_id":"toxicity-guard-pipeline","prompt":"Hello there"}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

## Options

### Step envelope (every step)
| Field | Default | Purpose |
| --- | --- | --- |
| `id` | required | Step id — also the prefix of its context fields (`{id}.output`, `{id}.label`, ...). |
| `type` | required | One of the step types above. |
| `role` | `output` | `infer` steps only: `output`, `thinking`, or `internal` (see Text Generation). |
| `next_step` | none | Edge to the next step; omit to make the step terminal. Route/loop steps name their own targets instead. |
| `config` | required | The type-specific config block. |

### Conditions (`break` / `loop` `condition:` block)
| `type` | Evaluator | Semantics |
| --- | --- | --- |
| `equals` | `ConditionEvaluatorFactory.forEquals` | Field string-equals `match_value`. |
| `contains` | `forContains` | Field contains `match_value` as a substring. |
| `label_equals` | `forEquals` on the label field | Classifier top-label equals `match_value`. |
| `score_above` | `forScoreAbove` | Resolved score ≥ `threshold` (context-aware via `ScoreResolver`). |
| `score_below` | `forScoreBelow` | Resolved score < `threshold`. |
| `not_empty` | `forNotEmpty` | Field is non-null and non-blank. |
| `empty` | `forEmpty` | Field is null or blank. |

Each condition block also takes `input_field` (the context field to evaluate) plus `match_value` or `threshold` depending on the type.

### Finish reasons
| `FinishReason` | Emitted when |
| --- | --- |
| `FINISH_REASON_STOP` | Normal exhaustion of the DAG (or the last engine finish reason if set — e.g. `LENGTH`, `TOOL_CALLS`). |
| `FINISH_REASON_BREAK_CONDITION` | A `break` (or loop exit) condition fired; the value of the break's `output_field` is the final response. |
| `FINISH_REASON_GUARD_BLOCKED` | A guard rejected; delivered as a `FAILED` event with `error_code: "content_filter"` and the guard's rendered `message`. |
| `FINISH_REASON_MAX_ITERATIONS` | A `loop` hit its `max_iterations` ceiling. |

## Notes
- **Context fields are strings.** `PipelineContext.set` silently ignores null values; classifier scores are stored as float strings under `{output_field}.score`, embeddings as JSON array strings.
- **Jinja everywhere**: `MessageDef.content`, `raw_template`, and guard `message` fields are Jinja2 templates rendered by `JinjaRenderer` (compiled once, cached). `JinjaContextHelper.buildBaseContext` exposes `prompt`, `system`, `history`, `messages`, `generated_messages`, `verdicts`, plus one map per step id (`{{generate.output}}`, `{{toxicity_guard.score}}`) and any request `context` seed values.
- **Errors terminate, they don't propagate**: `StepDispatcher.dispatch(...).onErrorComplete()` logs the failure and ends the walk — the client gets a `COMPLETED` with whatever streamed so far, not an exception. A `step_id` missing from the step map halts with a logged warning.
- **Halt checks are pervasive**: `walkStep` short-circuits whenever `context.isHalted()` — a guard reject inside a sub-pipeline propagates up to the parent.
- **`{step_id}.output` is overwritten on each loop iteration**; use `generated_messages` (preserved append-only log) when a CoT prompt needs previous drafts.
- **Order of `steps:` is irrelevant** — execution is defined only by `entry`, `next_step` edges, and route/loop targets.
- **Streaming is per-role**: only `infer` steps write to the client stream; every other step type is silent. `internal` infer steps are executed but never streamed.
- **Tracing**: with OTel enabled, each run opens an `ai.pipeline` span and each step an `ai.step` span (attributes `step.id`, `step.type`); model calls nest under the active step span.

## See also
- [Text Generation](../text-generation/README.md) — the `infer` step, sampling, templates, and streaming in depth.
- [Guards & Redaction](../guards-and-redaction/README.md) — `guard`, `llm_guard`, and `regex_guard` details.
- [Routing](../routing/README.md) — `route` strategies and rules.
- [Loops & Chain-of-Thought](../loops-and-cot/README.md) — `loop`/`break` revision cycles.
- [Sub-pipelines](../sub-pipelines/README.md) — nesting and remote delegation.
- [Workspaces](../workspaces/README.md) — how pipelines and models are declared and published.
