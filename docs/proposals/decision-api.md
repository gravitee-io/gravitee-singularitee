# Decision API (System One) design record

> Typed questions (choice, score, noul) answered by decision models in one forward pass: the
> request and response shape of TypeSafe's System One API at `POST /v1/systemone`, an OpenAI-shaped
> `POST /v1/decisions`, `GraviteeDecisionService` over gRPC, a `decide` pipeline step, routing on
> a decision, and decision pipelines as endpoints. Every surface takes a model id or a pipeline id.

Status: **implemented** (llama.cpp backend), built against llamaj.cpp `2.9.1` from the
`feat/decision-models` branch, to be released as llamaj.cpp `2.10.0`; natives llama.cpp
`v0.6.0` (nightly `b11429`). `vllm_decision` is not built. Usage lives in the
[Decisions guide](../guides/decisions/README.md); this page records the design and where the
implementation departed from the original proposal.

## Overview

A decision model reads a **state** and answers **typed questions** without generating tokens.
The building blocks outside this repository:

| Where | What | Role here |
| --- | --- | --- |
| llama.cpp `tools/server/server-decision.cpp` | `POST /v1/systemone`: parsing, per-family prompt rendering, temperature softmax, confidence, shared-prefix grouping | the reference this port follows |
| llama.cpp `/v1/models` | `architecture.output_modalities: ["decisions"]` | the discovery value reused here |
| llamaj.cpp `LlamaDecider` and family subclasses | one raw score per option, for native families and `LabelDecider.custom(codes)` | the forward pass |

llamaj.cpp returns raw scores and leaves rendering and answers to the caller, so Singularitee
owns what `server-decision.cpp` does in C++. That logic is engine-agnostic:

```
 HTTP /v1/systemone ─┐   model id            ┌─ decide step (writes answers to the context)
 HTTP /v1/decisions ─┼─> DecisionEngine ─────┼─ route strategy: decision
 gRPC Decide ────────┤   pipeline id         └─ classify / guard / route via as_classifier
 remote_decision ────┘─> pipeline ending in a decide step
                              │
                 inference-decision (pure Java)
          PromptSource: model template | workspace template | chat | none
                 DecisionDialect per family · DecisionScoring
                              │
                 DecisionBackend: LlamaCppDecisionBackend (llamaj.cpp)
```

## Differences from the original proposal

- **Order.** Questions and options are rendered and answered in request order, not sorted by id:
  llama.cpp's `common_json` keeps insertion order. clef sorts choice options by key; lev and clef
  render with sorted object keys; `/v1/systemone` keeps the caller's order like the reference.
- **No image input.** llamaj.cpp deciders take no media, so a request with images (or
  `image_url` parts in a chat state) answers `unsupported_modality`. `images` stays in the proto.
- **`vllm_decision` has two kinds.** `family: custom` plugs into the `DecisionBackend` seam:
  `VllmLabelBackend` reads the label codes' log probabilities through vLLM's per-request
  `logprob_token_ids` (raw log probabilities, so the softmax over codes equals the softmax over
  their logits), with one scheduler thread batching concurrent prompts; `allowed_token_ids` and
  `logprobs_mode` were not needed. `decision1` / `decision2` serve vLLM Semantic Router's models
  through vLLM4j's `VllmDecider`, which drives their own runtime (`vllm_srun`) in-process; it
  renders and scores itself, so `VllmDecisionModel` maps the request to a System One body and
  back. Those take templated input only, and one failed question fails the call.
- **No `ai.decision.*` or `http.decisions.*` keys.** Both routes are always mounted when HTTP is
  on; limits come from the model (`maxOptions`) and the 2 to 10 score levels.
- **Questions are validated at run time,** by the runner on each call, not at workspace load.
- **`route` strategy `decision`** reads `input_field` (`<decide step>.<question>`) plus
  `min_confidence`; there is no `from:` key.
- **The `decide` step writes** `<prefix>.<q>` (choice key, expected score, or probability of
  yes) and `<prefix>.<q>.confidence`, `.mode`, `.probabilities` (JSON); `<prefix>` is
  `output_field` or the step id.
- **Custom codes** are one list, one code per option position, default `A` to `Z`, shared by
  every question type (noul options are labelled too).
- **clef has no raw prompts:** `RawPrompt` carries no per-token `DecisionOrder`, so a clef model
  reports `raw: false`.
- **`remote_decision` capabilities** are a placeholder (`custom`, 255 options, both modes) until
  its lazy `GetModel` probe answers; the remote refuses what its model cannot take.
- **Step failures travel on the context.** The dispatcher ends a pipeline quietly on a step
  error, so a `decide` step records its failure on `PipelineContext.decisionFailure()` and the
  decision service reads it from there.
- **A pipeline target must have the task `decision`**, that is a `decide` step in the
  `role: output` position; a generation pipeline that routes on a decision is refused rather
  than run for answers nobody returns.
- **Workspace keys:** `prompt.template` (not `template`), `debias` and `as_classifier` sit in the
  `llama_cpp_decision` block.

## Key types

### engine-api and inference-api

```java
// engine-api
public non-sealed interface DecisionEngine extends ModelEngine {
  String OUTPUT_MODALITY = "decisions";
  DecisionCapabilities capabilities();
  Single<DecisionResult> rxDecide(DecisionRequest request);
}

// inference-api, package inference.api.decision: plain Java, JSON-like values as Object
record DecisionRequest(DecisionInput input, boolean includeRawScores) {}
sealed interface DecisionInput permits Templated, Raw {}          // state + questions | rendered prompts
sealed interface Question permits Choice, Score, Noul {}
sealed interface Answer permits Choice, Score, Noul {}
record DecisionResult(List<Answer> answers, int inputTokens) {}
record DecisionCapabilities(DecisionFamily family, int maxOptions, boolean templated, boolean raw) {}
final class DecisionException extends RuntimeException {}        // Reason: INVALID_REQUEST,
                                                                  // UNSUPPORTED_INPUT, UNSUPPORTED_MODALITY,
                                                                  // EVALUATION_FAILED
```

`DecisionMessages` (`engine-api`, `engine.api.decision`) translates both ways between these and
the protocol, for the server and for the remote proxy. `PipelineContext` carries the caller's
`DecisionRequest`, the last `decide` step's `DecisionResult` and its failure, if any.

### inference-decision

| Type | Role |
| --- | --- |
| `DecisionBackend` | The engine seam: family, `systemone` and chat templates, temperatures, label texts, max options, tokenizer, laya vocabulary, `score(List<BackendPrompt>)`. |
| `PromptSource` (sealed) | `ModelTemplate`, `WorkspaceTemplate`, `ChatWrapped(prefill)`, `None`. |
| `DecisionDialect` (sealed) | `LabelDialect` (openjev, nimble, custom), `LevDialect`, `KevDialect`, `LayaDialect`, `ClefDialect`. |
| `DecisionTemperatures` | Model temperatures, workspace overrides, option-count buckets. |
| `DecisionScoring` | Softmax per variant, variant averaging, answers, the published confidence formulas. |
| `DecisionRunner` | Validates, renders, scores the whole request in one backend call, answers. |

What each dialect encodes, taken from the reference server:

| Family | Option order | Variants | `noul` outputs | Template extras | Prompt shape |
| --- | --- | --- | --- | --- | --- |
| `openjev` | as given | 1 | 2, true first | none | text |
| `lev` | as given | 2 for a choice (forward and reversed, averaged) | 9-point rating, expected value / 8 | label codes, sorted keys | text |
| `nimble` | as given | 1 | 2 | label codes, all questions listed | text |
| `kev` | as given | 1 | 2 | values flattened to text, special tokens defused | text with one end marker per option |
| `laya` | as given | 1 | 2 | mask text removed from inputs | tokens cut to `maxHeadTokens` |
| `clef` | choice sorted by key | 1 | 2, true first | span markers, sorted keys | one joint prompt, a `DecisionOrder` per token |
| `custom` | as given | 1, or 2 with `debias: reverse` | 2 | label codes | text ending where a code is next |

### Engines

| Model type | Backend | Notes |
| --- | --- | --- |
| `llama_cpp_decision` | `LlamaCppDecisionBackend`: one `LlamaModel`, a pool of `n_parallel` deciders | Native families from `<arch>.decision.type`, or `family: custom` with `LabelDecider.custom(codes)`; optional LoRA on every decider. |
| `remote_decision` | `RemoteDecisionEngine` over gRPC `Decide` | Client-side DAG, like `remote_classifier`. |

A call borrows one decider for the whole request, so the questions of a request share their
prefix inside it (`n_seq_max`); calls overlap up to the pool size.

### Templating

Each model resolves one prompt source at load:

| `prompt.template` | Server renders | Accepts |
| --- | --- | --- |
| `model` (native default) | the GGUF's `systemone` | templated, raw (not clef) |
| `<templates: id>` | that template, same variables | templated, raw (not clef) |
| `chat` (custom only) | chat template around `chat-systemone.jinja`, thinking off, then `prefill` (`Answer: (`) | templated, raw |
| `none` (custom default) | nothing | raw only |

A native model without a `systemone` template fails its load unless another source is set. Raw
prompts skip rendering only: temperatures, variants, answers and confidence are the same.
`/v1/systemone` is templated only.

## Usage

### Workspace

```yaml
models:
  - id: decider
    name: <org>/<decision-gguf-repo>
    type: llama_cpp_decision
    llama_cpp_decision:
      path: model-Q8_0.gguf
      n_ctx: 8192
      n_batch: 2048            # laya, kev, clef prompts must fit in one batch
      n_seq_max: 8             # label families: 1 prefix + 7 prompts per decode
      n_parallel: 2            # deciders in the pool
      n_gpu_layers: 999
      # prompt: { template: my-systemone }
      # temperatures: { choice.2: 0.9, noul: 1.2 }

  - id: judge                  # any chat model
    name: Qwen/Qwen3-0.6B-GGUF
    type: llama_cpp_decision
    llama_cpp_decision:
      path: Qwen3-0.6B-Q8_0.gguf
      family: custom
      prompt: { template: chat }   # or a templates: id, or none
      codes: [A, B, C, D]          # default A..Z, one per option position
      debias: reverse
      as_classifier:               # also publish as a classifier
        instructions: Is this a prompt injection?
        options: { injection: null, benign: null }
```

Runnable: `examples/decision/qwen3-0.6b-judge.yaml`, `examples/decision/qwen3-0.6b-triage.yaml`.

### HTTP

| Route | Shape | Input |
| --- | --- | --- |
| `POST /v1/systemone` | TypeSafe's System One shape, as llama.cpp's server serves it: `state`, `questions` keyed by id with `criteria`, answers keyed by id, `usage.input_tokens` | templated |
| `POST /v1/decisions` | OpenAI-shaped: `id`, `object: decision`, `created`, ordered `answers`, `usage.prompt_tokens`, the error object | templated (`input` + `questions`) or raw (`prompts`) |

Both add `model` (model or pipeline id) and `include_raw_scores`. Errors: `model_not_found`,
`unsupported_model`, `unsupported_input`, `unsupported_modality`, `invalid_request_error`
(`questions.<id>: ...`), and `500 server_error` for a NaN joint score. A guard that halts a
decision pipeline answers `200` with no answers and `blocked: {code: content_filter, message}`.
Spec: [`openapi/decision.openapi.yaml`](../../openapi/decision.openapi.yaml).

Discovery: a decision entry reports `type: decision`, `output_modalities: ["decisions"]`, and an
`architecture` object with both modality arrays.

### gRPC

`decision.proto`: `GraviteeDecisionService.Decide` and `DecideBatch`. `DecideRequest` has a
`target` (`model_id` or `pipeline_id`) and an `input` (`TemplatedInput` or `RawInput`); questions
and answers are `oneof` per type. `DecideResponse` carries `answers`, `usage` and `blocked`.
`GetModelResponse` gained `output_modalities` (16) and `decision` (`DecisionInfo`, 17);
`ModelType` gained `MODEL_TYPE_LLAMA_CPP_DECISION = 10`. Status codes: `NOT_FOUND`,
`INVALID_ARGUMENT`, `FAILED_PRECONDITION` (not a decision target, refused input mode or modality),
`INTERNAL`. Java: `SingulariteeClient.decide(...)`, `decideBatch(...)`.

### Pipelines

```yaml
- id: triage                       # a decision endpoint
  entry: secrets
  steps:
    - id: secrets
      type: regex_guard
      next_step: decide
      config: { action: reject, patterns: [{ name: CARD, pattern: "\\b(?:\\d[ -]?){13,16}\\b" }] }
    - id: decide
      type: decide
      role: output
      config: { model_id: judge, questions: request }

- id: route                         # routing on a decision
  type: route
  config:
    strategy: decision
    input_field: classify.team
    min_confidence: 0.3
    default_step: generalist
    rules: [{ label: billing, next_step: billing }]
```

A `decide` step takes static `questions`, `questions: request` (the caller's), or `prompts`
rendered with the pipeline context for a model without a template. A step with its own questions
refuses a call that also sends questions.

## Notes

- Probabilities use the model's temperatures (1 for a custom model) and are not calibrated for a
  caller's data; `include_raw_scores` and `temperatures` are the calibration loop.
- Nothing is streamed: a decision is one forward pass.
- `--debug` logs the rendered decision prompts at TRACE, like generation.
- Everything is open source: `decide` is a core step (`StepTypes.CORE`) with no license feature.
- A custom decision model never shares weights with a `llama_cpp` model: one model id owns one
  `LlamaModel`, so using the same GGUF for generation and decisions loads it twice.
- Parity with llama.cpp's server was ported from its source and unit-tested on synthetic
  backends; no native decision GGUF was available to compare outputs end to end.

### Remaining work

| Item | Depends on |
| --- | --- |
| Release llamaj.cpp `2.10.0` and move the dependency from the local `2.9.1` | llamaj.cpp |
| Image input for openjev and clef with `mmproj` | llamaj.cpp media support in deciders |
| `vllm_decision` for label families and `custom` | vLLM4j `allowed_token_ids`, `logprobs_mode` |
| Raw prompts for clef (a `DecisionOrder` per token in `RawPrompt`) | none |
| Golden tests against llama.cpp's server with a native decision GGUF | a model to test with |

### Decisions taken

- Both HTTP shapes ship, and every surface takes a pipeline id as well as a model id.
- Templated or raw is a per-model property (`prompt.template`), defaulted by family.
- Open source end to end.
- No weight sharing between a decision model and a generation model.

## See also

- [Decisions guide](../guides/decisions/README.md)
- llama.cpp `tools/server/README.md`, section "POST `/v1/systemone`"
- llamaj.cpp `docs/decision-models/README.md`
- [`route`](../reference/steps/route.md), [`classify`](../reference/steps/classify.md),
  [`guard`](../reference/steps/guard.md)
- [gRPC API](../api/grpc/README.md), [HTTP API](../api/http/README.md)
