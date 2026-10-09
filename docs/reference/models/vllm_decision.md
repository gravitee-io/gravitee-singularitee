# vllm_decision

> A decision model on vLLM: a vLLM Semantic Router model (Decision 1.0 or 2.0) run by its own runtime, or any causal model vLLM serves scored on label codes (`family: custom`). Typed questions answered in one forward pass.

## Overview

`vllm_decision` serves a `DecisionEngine` (`decision`): gRPC `GraviteeDecisionService.Decide`,
HTTP `/v1/decisions` and `/v1/systemone`, and the [`decide`](../steps/decide.md) step. Answers
have the same shape as a [`llama_cpp_decision`](./llama_cpp_decision.md) model's:

| Question | Answer |
| --- | --- |
| `choice` | The most probable option, every option's probability, a confidence. |
| `score` | The expected level (2 to 10 levels, lowest first), the most probable level, probabilities, a confidence. |
| `noul` | The probability that the answer is yes. |

Two kinds of model load under this type:

| Kind | `family` | Who renders and scores | Weights |
| --- | --- | --- | --- |
| vLLM Semantic Router model | `decision1`, `decision2` (read from a built-in id) | the model's own runtime, `vllm_srun` | downloaded and verified by the runtime |
| Any causal model | `custom` | the server, as `llama_cpp_decision` `family: custom`; vLLM computes the logits | downloaded like a [`vllm`](./vllm.md) model's |

**vLLM Semantic Router models.** vLLM's `LLMEngine` does not run the `vllm-sr/Decision-1.0-*` and
`vllm-sr/Decision-2.0-*` models; their runtime, `vllm_srun`, owns the prompt layout and the
scoring. vLLM4j's `VllmDecider` drives it in the embedded CPython: no HTTP server, no extra
process. A request goes to it as a System One body (the state and the named questions), so the
answers are the runtime's own, calibrated by the model. At load the runtime resolves the pinned
revision, checks every file against its SHA-256, loads the weights and checks the model against
its golden answers; the model is published only once that passes.

**Custom models.** Any chat or base model vLLM can serve becomes a judge: the shared decision
runner renders each question (the model's chat template around a shipped instruction, a
workspace template, or the caller's own prompt), the prompt ends where a label code (`A`, `B`,
`C`... by default) is the next token, and vLLM reports the log probability of exactly those codes
(`logprob_token_ids`) for one greedy step. Those are raw log probabilities, so a softmax over them
is a softmax over the code logits: the same scores `llama_cpp_decision` reads, with calibration
temperatures and `debias: reverse` applied the same way.

## Key types

- `DecisionEngine` (engine-api): `capabilities()`, `rxDecide(DecisionRequest)`.
- `VllmDecisionModel` (inference-vllm): System One mapping over `VllmDecider` (runtime models).
- `VllmLabelBackend` (inference-vllm): the `DecisionBackend` of custom models; one scheduler
  thread owns the vLLM engine and batches every queued prompt into each step.
- `DecisionRunner` (inference-decision): prompt layout and answers, shared with llama.cpp.
- `VllmDecisionFactory`, `VllmDecisionEngine`, `DecisionRunnerEngine` (grpc): the adapters.

## Usage

A vLLM Semantic Router model (`examples/vllm/decision-2.0-kai-0.6b.yaml`):

```yaml
models:
  - id: kai
    name: vllm-sr/Decision-2.0-Kai-0.6B
    type: vllm_decision
    vllm_decision:
      device: auto
      as_classifier:
        instructions: What does the user want?
        options:
          billing: Payment, invoice or refund issue
          account: Login or access issue
          other: Anything else
```

Any chat model on vLLM (`examples/vllm/qwen3-0.6b-judge.yaml`):

```yaml
models:
  - id: judge
    name: Qwen/Qwen3-0.6B
    type: vllm_decision
    vllm_decision:
      family: custom
      debias: reverse
      prompt:
        template: chat
      vllm:
        max_model_len: 4096
        gpu_memory_utilization: 0.3
        enforce_eager: true
```

```bash
./scripts/setup-venv.sh -b metal -r        # or -b cuda | -b cpu; -r adds vllm-srun
./run-server.sh --workspace examples/vllm/qwen3-0.6b-judge.yaml

curl -s localhost:8080/v1/decisions -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "input": "Customer message: I was charged twice for my order and nobody has replied.",
  "questions": [
    {"id": "route", "type": "choice", "instructions": "Which team should handle this?",
     "options": [{"key": "billing"}, {"key": "shipping"}, {"key": "technical"}]},
    {"id": "angry", "type": "noul", "instructions": "Is the customer angry?"}
  ]
}' | jq
```

The built-in runtime models, all under `vllm-sr/`:

| Family | Models |
| --- | --- |
| `decision1` | `Decision-1.0-Kai-0.6B`, `-Lex-0.6B`, `-Route-0.6B` (encoder), `-Eos`, `-Sol`, `-Nox`, `-Lux` (Qwen3.5) |
| `decision2` | `Decision-2.0-Kai-0.6B`, `-Eos-0.8B`, `-Sol-2B`, `-Nox-4B`, `-Lux-9B`, `-Vega-27B` |

Each pins its own minimum device memory, from 4 GiB (Kai) to 72 GiB (Vega).

## Options

Every key of the `vllm_decision:` block is optional. Runtime keys and custom keys are exclusive:
a key of the other kind fails the model.

| Key | Kind | Type | Default | Purpose |
| --- | --- | --- | --- | --- |
| `family` | both | string | from `name` | `decision1`, `decision2` or `custom`. Read from a built-in id (`Decision-1.0-`, `Decision-2.0-`); required otherwise. |
| `as_classifier` | both | object | unset | `instructions` and `options` (label to description): a choice question that also publishes the model as a classifier. Both optional; request `labels` replace `options` per call (zero-shot), see [llama_cpp_decision](./llama_cpp_decision.md#options). |
| `revision` | runtime | string | the runtime's pin | A Hub revision to load instead. |
| `device` | runtime | string | `auto` | `auto`, `cpu`, `cuda`, `cuda:1`, `mps`, `rocm`. |
| `profile` | runtime | string | `exact` | `exact` matches the released numerics; a faster profile the model enables otherwise. |
| `engine` | runtime | string | `auto` | `auto`, `native` or `onnxruntime`. |
| `memory_budget_gib` | runtime | number | the runtime's | Device memory the model may use. Set it when the GPU is shared with a `vllm` model. |
| `threads` | runtime | int | the runtime's | CPU threads the runtime may use. |
| `cache_dir` | runtime | string | `<ai.models.path>/vllm-decision` | Where the runtime downloads model files. |
| `offline` | runtime | boolean | `false` | Load from `cache_dir` only, without contacting the Hub. |
| `prompt.template` | custom | string | `none` | `chat` (the chat template around a shipped instruction), `none` (caller-rendered prompts only), or a `templates:` id. |
| `prompt.prefill` | custom | string | `Answer: (` | `chat` only: appended after the generation prompt, so a code is the next token. |
| `codes` | custom | list | `A` to `Z` | One single-token label code per option position, at most 128. |
| `debias` | custom | string | `none` | `reverse` scores each choice in both option orders and averages. |
| `temperatures` | custom | map | `1.0` | Calibration temperatures, keyed like `choice`, `choice.3_5`, `score`, `noul`. |
| `vllm` | custom | object | vLLM's | The engine block of a [`vllm`](./vllm.md#options) model (`max_model_len`, `gpu_memory_utilization`, `dtype`, `tensor_parallel_size`...), except `lora_path`. |

## Notes

- **The venv.** Both kinds run in the vLLM venv. Runtime models also need `vllm-srun`:
  `scripts/setup-venv.sh -r` (or `VLLM4J_DECISION=1`) installs it from a pinned semantic-router
  commit on the torch vLLM installed; `Dockerfile.vllm-cuda` does the same. The type is
  registered only when vLLM4j ships `VllmDecider`.
- **Input.** Runtime models take templated input only (raw prompts answer `unsupported_input`);
  custom models take raw prompts too, unless `prompt.template` is `none`, which takes raw only.
  Images answer `unsupported_modality` on both. `include_raw_scores` returns the code log
  probabilities of custom models and nothing for runtime models.
- **Runtime models take JSON instructions and noul descriptions.** Text, an object or an array
  reach the runtime as they are; `when_true` / `when_false` become its `true` / `false` criteria.
- **Runtime models fail the whole call on one failed question.** `invalid_question`,
  `max_length_exceeded`... answer `invalid_request`; `unavailable`, `deadline_exceeded`...
  `evaluation_failed`. Their `confidence` is the runtime's; `mode` is the most probable level.
- **Custom models batch.** Concurrent calls share engine steps: eight parallel calls on
  Qwen3-0.6B (Apple Silicon) answer together in about the time of a few single ones. Each prompt
  computes one token, which is discarded.
- **One custom model, one engine.** A custom judge loads its own vLLM engine, never the weights of
  a `vllm` model in the same workspace. Size `gpu_memory_utilization` for both; on Apple Silicon it
  is a fraction of total unified memory.
- **`HF_TOKEN` reaches both** (`ai.huggingface.token`), for gated repositories and to avoid
  anonymous rate limits.

## See also

- [llama_cpp_decision](./llama_cpp_decision.md), [remote_decision](./remote_decision.md).
- [vllm](./vllm.md): the venv, `run-server.sh --venv`, the CUDA image.
- [Decisions guide](../../guides/decisions/README.md).
- [decide step](../steps/decide.md).
