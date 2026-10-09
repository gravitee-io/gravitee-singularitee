# llama_cpp_decision

> A GGUF decision model served by llama.cpp: typed questions (choice, score, noul) answered in one forward pass, without generating tokens.

## Overview

`llama_cpp_decision` loads a GGUF and serves it as a `DecisionEngine` (`decision`): gRPC
`GraviteeDecisionService.Decide`, HTTP `/v1/decisions` and `/v1/systemone`, and the
[`decide`](../steps/decide.md) step. Each option of a question gets one raw score; the engine
turns the scores into probabilities with the model's calibration temperatures and answers:

| Question | Answer |
| --- | --- |
| `choice` | The most probable option, every option's probability, a confidence from 0 (uniform) to 1. |
| `score` | The expected level (2 to 10 levels, lowest first), the most probable level, probabilities, a confidence. |
| `noul` | The probability that the answer is yes. |

Two kinds of model load under this type:

- **A native decision GGUF** (`family: auto`, the default): the model declares its family in
  `<arch>.decision.type` (`openjev`, `lev`, `nimble`, `kev`, `laya`, `clef`) and ships its own
  `systemone` prompt template and temperatures.
- **Any causal model** (`family: custom`): scored on the logits of one label token per
  option (`codes`, default `A` to `Z`), at a prompt that ends where the label is the next token.

`LlamaCppDecisionFactory` builds the engine from one `LlamaModel` shared by `n_parallel`
deciders, each with its own context; `GgufModelResolver` downloads the GGUF (and the LoRA) from
`name`. Prompt building and scoring live in `gravitee-singularitee-inference-decision`, so the
llama.cpp part is only the forward pass.

## Key types

- `DecisionEngine` (engine-api): `capabilities()`, `rxDecide(DecisionRequest)`.
- `DecisionCapabilities` (inference-api): `family`, `maxOptions`, `templated`, `raw`.
- `DecisionRunner` / `DecisionDialect` (inference-decision): per-family prompt layout and scoring.
- `PromptSource` (inference-decision): `ModelTemplate`, `WorkspaceTemplate`, `ChatWrapped`, `None`.
- `LlamaCppDecisionBackend` (inference-llama-cpp): the decider pool.

## Usage

A native decision model:

```yaml
models:
  - id: decider
    name: some-org/decision-model-gguf
    type: llama_cpp_decision
    llama_cpp_decision:
      path: model-Q8_0.gguf
      n_ctx: 8192
      n_parallel: 2
```

Any chat model, through its chat template (`examples/decision/qwen3-0.6b-judge.yaml`):

```yaml
models:
  - id: judge
    name: Qwen/Qwen3-0.6B-GGUF
    type: llama_cpp_decision
    llama_cpp_decision:
      path: Qwen3-0.6B-Q8_0.gguf
      family: custom
      debias: reverse
      prompt:
        template: chat
      n_ctx: 4096
      n_seq_max: 8
      n_parallel: 2
```

```bash
curl -s localhost:8080/v1/decisions -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "input": "I was charged twice for my order.",
  "questions": [
    {"id": "team", "type": "choice", "instructions": "Which team?",
     "options": [{"key": "billing"}, {"key": "shipping"}]}
  ]
}' | jq
```

### Prompt source

`prompt.template` decides whether the server renders prompts for the model (templated input:
a state and questions) or only scores prompts the caller rendered (raw input).

| `prompt.template` | Prompts come from | Accepts | Default for |
| --- | --- | --- | --- |
| `model` | The GGUF's `systemone` template. | templated, raw | native families |
| `chat` | The model's chat template around a shipped instruction, then `prompt.prefill`. `custom` only. | templated, raw | |
| `none` | Nothing: the caller sends rendered prompts. | raw | `custom` |
| any other value | The `templates:` entry of that id, rendered with the same variables as `systemone`. | templated, raw | |

Template variables: `id`, `type`, `instructions`, `state`, `options` (each with `key`,
`description`, and `label` when the family shows codes), `images` (always empty), plus
`questions` for `nimble`. `clef` renders one joint prompt with `state`, `questions`, `sep`,
`mark_question` and `mark_option`.

## Options

`llama_cpp_decision:` block (`WorkspaceDefinition.LlamaCppDecisionDef`).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `path` | string | required | GGUF file in the `name` repository, or a local path. Missing fails the model with `llama_cpp_decision.path is required`. |
| `family` | string | `auto` | `auto` reads `<arch>.decision.type` from the GGUF; `custom` scores any causal model on `codes`. Anything else fails the model. |
| `codes` | list of string | `A` to `Z` | `custom` only: the label code of each option position. Each must be one distinct token for the model, or the load fails. Their count is the model's option limit. |
| `debias` | string | `none` | `custom` only: `reverse` scores each choice twice, options in both orders, and averages, to cancel a preference for the first label. Doubles the prompts of a choice. |
| `prompt.template` | string | `model` (native), `none` (`custom`) | See the table above. `chat` on a native family fails the model. |
| `prompt.prefill` | string | `Answer: (` | `chat` only: text appended after the generation prompt, so the next token is a code. |
| `temperatures` | map of string to float | the model's | Overrides of `<arch>.decision.temperature.*`, same keys: a question type (`choice`, `score`, `noul`) or a type and an option-count bucket (`choice.2`, `choice.3_5`, `choice.6_10`, `choice.11`; `lev` uses `small`, `mid`, `large`). The bucketed key wins. Unset everywhere: `1`. Values must be positive. |
| `as_classifier` | block | unset | Also publishes the model as a classifier, see below. |
| `n_ctx` | int | `8192` | Context size of each decider. |
| `n_batch` | int | llama.cpp default (label families), `2048` (`kev`, `laya`, `clef`) | Tokens per decode. For the hidden-state families it is also the ubatch and the longest prompt. |
| `n_seq_max` | int | `8` | Label families: sequences per decider. Sequence 0 holds the prefix the prompts of a request share, so up to `n_seq_max - 1` prompts decode together. `1` disables prefix sharing. |
| `n_parallel` | int | `1` | Deciders in the pool. Each holds its own context; a request borrows one, further requests queue. |
| `n_gpu_layers` | int | `999` | Layers offloaded to the GPU. |
| `lora_path` | string | unset | LoRA adapter in the same repository, attached to every decider. |

`as_classifier:` block (`DecisionClassifierDef`):

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `instructions` | any | `Which label fits the text?` | The choice question asked of each text. |
| `options` | map of label to description | unset | Labels in prompt order; a `null` description is allowed. Unset, every call sends its labels. An empty map fails the model. |

With `as_classifier`, `rxClassify(text)` asks that question: labels are the option keys and
scores their probabilities, so [`classify`](../steps/classify.md), [`guard`](../steps/guard.md)
and [`route`](../steps/route.md) with `strategy: classifier` take the model like any classifier.
The model still reports task `decision`.

The classifier is zero-shot: labels a classify call sends (`labels` on `/v1/classify`, the
`Classify` and `ClassifyBatch` RPCs) replace `options` for that call, each description read as
the option's meaning. `as_classifier: {}` declares a classifier with no labels of its own, which
refuses a call without labels. Duplicate names, or more labels than `max_options`, answer
`invalid_request` (HTTP 400, gRPC `INVALID_ARGUMENT`).

## Notes

- **Order.** Questions and options render and answer in request order. `clef` sorts choice
  options by key and decides every question in one joint prompt; `lev` and `clef` sort the keys
  of the template variables; `openjev` and `clef` list noul answers true first. Answers always
  come back keyed by the caller's option keys.
- **Family specifics.** `lev` scores a choice in both orders and reads a noul from a 9-point
  rating. `laya` cuts the question and options to the model's `max_head_tokens`. `kev` flattens
  the state and instructions to text. `clef` needs the native decision-order symbol (checked at
  load) and accepts no raw prompts.
- **Option limits.** `openjev`: 52. `lev`, `nimble`: the single-token codes `A..Z`, `AA..ZZ`, at
  most 255. `kev`, `laya`, `clef`: 255. `custom`: the number of `codes`. A `score` takes 2 to 10
  levels. Too many options fail the request with `questions.<id>: too many options`.
- **A model without a `systemone` template** fails the load under `prompt.template: model`; set
  a workspace template, or `none` for raw prompts only.
- **No image input.** A request carrying images fails with `unsupported_modality`.
- **Calibration.** Probabilities use the model's temperatures; they are not calibrated for a
  given data set. `include_raw_scores` returns the scores before temperature, per variant.
- **Memory check.** `memory_check` estimates the weights plus `n_parallel` contexts of `n_ctx`.
- **Discovery.** `GetModel` and `/v1/models` report task `decision`, output modality
  `decisions`, and `DecisionInfo` (`family`, `max_options`, `joint`, `templated`, `raw`).
- **`--debug`** logs each rendered decision prompt at TRACE.
- **Same distribution flavour as `llama_cpp`.** The factory registers only when llama.cpp is on
  the classpath.

## See also

- [vllm_decision](./vllm_decision.md): the same custom scoring on vLLM, and vLLM Semantic Router's models.
- [`decide`](../steps/decide.md), [`route`](../steps/route.md) (`strategy: decision`).
- [remote_decision](./remote_decision.md).
- [llama_cpp](./llama_cpp.md), the platform notes and native library pinning.
