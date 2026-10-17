# gliner_decision

> A GLiNER2 bundle (GLiNER2.5-Decide) answering typed questions, one classification task per question.

## Overview

`gliner_decision` registers a `DecisionEngine` (`decision`, family `gliner`) over gliner4j's
`GLiNER4jDecider`. Each question becomes one classification task about the state: the question id
and its instructions are the task prompt (`"id: instructions"`), the options are its labels, and a
softmax over the label scores gives one probability per option. The answers come back typed like
those of any decision model, on `/v1/decisions`, `/v1/systemone`, gRPC `Decide` and the
[`decide`](../steps/decide.md) step. `GlinerDecisionEngine` implements it,
`GlinerDecisionFactory` builds it.

It is an encoder, not a language model: there is no prompt to render, so it takes a state and
questions only, and refuses rendered prompts with `unsupported_input`.

## Key types

| Type | Role |
| --- | --- |
| `GlinerDecisionFactory` | Loads the bundle with `GLiNER4jDecider` and wraps the engine as a classifier when `as_classifier` is set. |
| `GlinerDecisionEngine` | Maps each question to a GLiNER task and its label probabilities to the answer of the question's type. |
| `GlinerDecisionConfig` | The resolved `gliner_decision:` block. |

## Usage

```yaml
models:
  - id: decide
    name: fastino/GLiNER2.5-Decide
    type: gliner_decision
    memory_check: disabled
    gliner_decision:
      model_dir: /models/decide-llamacpp   # the bundle GLiNER4j exports
```

```bash
curl -s localhost:8080/v1/systemone -H 'Content-Type: application/json' -d '{
  "model": "decide",
  "state": "Someone changed the password on my account last night and moved money out of it.",
  "questions": {
    "intent":  {"type": "choice", "instructions": "What does the customer want?",
                "criteria": {"billing_issue": "A wrong, double or unexpected charge",
                             "account_security": "Someone else got into the account"}},
    "urgency": {"type": "score", "instructions": "How urgent is this?",
                "criteria": ["low", "normal", "high"]},
    "handoff": {"type": "noul", "instructions": "Does the customer ask for a human agent?"}
  }
}' | jq
```

The bundle is a GLiNER4j bundle like those of [gliner_classifier](./gliner_classifier.md):
`gliner4j_config.json`, the tokenizer files, and `gguf/` (llama.cpp engine) or `onnx*/` folders.
GLiNER2.5-Decide is exported for the llama.cpp engine by GLiNER4j's `task decide:llamacpp`.

## Options

`gliner_decision:` block (`WorkspaceDefinition.GlinerDecisionDef`). The whole block is optional.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_dir` | string | resolved from `name` | Root directory of the bundle. Set only for a local directory; unset, `name` is downloaded like a `gliner_classifier` bundle. |
| `variant` | string | `onnx` | ONNX bundles: the variant sub-folder. llama.cpp bundles: the GGUF quantisation, `q8_0` or `q4_0`; anything else selects the f16 `model.gguf`. |
| `as_classifier` | `{instructions, options}` | unset | Also publish the model as a classifier: classifying a text asks this one choice question. Same block as on [llama_cpp_decision](./llama_cpp_decision.md#options). |

## Notes

- **Questions.** A `choice` scores its option keys, each with its description. A `score` scores
  its level texts, which must be distinct and not blank; the answer's probabilities stay keyed by
  level index. A `noul` scores `yes` and `no` (with `when_true` / `when_false` as their
  descriptions); `probability_true` is the probability of `yes`.
- **Structured values** (a state, instructions or descriptions that are objects or arrays) are
  read as text: `key: value` lines and `- item` lines.
- **Scores are not ordinal.** The model scores each level as its own label, so a `score` answer
  is often spread out; read `score` with its `probabilities`.
- **No temperatures, no debiasing.** Probabilities are the model's softmax at temperature 1.
- **One call at a time.** Requests run in turn on the worker pool; each question is one forward
  pass of the encoder.
- **Limits.** At most 64 options per question: the labels share the encoder window with the state.
- **Usage.** `input_tokens` is reported as `0`: gliner4j does not expose its token counts.
- **Runtime knobs** are the `GRAVITEE_GLINER_*` environment variables of
  [gliner_classifier](./gliner_classifier.md#notes).

## See also

- [Decisions](../../guides/decisions/README.md).
- [llama_cpp_decision](./llama_cpp_decision.md), [vllm_decision](./vllm_decision.md),
  [remote_decision](./remote_decision.md).
- [gliner_classifier](./gliner_classifier.md), the same bundles as a zero-shot classifier.
