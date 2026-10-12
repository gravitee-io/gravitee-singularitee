# Decisions

> How to answer typed questions (choice, score, noul) with a decision model in one forward pass: call it over HTTP or gRPC, use it inside a pipeline, route on its answers, and publish a guarded decision pipeline as an endpoint.

## Overview

A decision model reads a **state** and answers **typed questions** without generating tokens. It scores each option, turns the scores into probabilities at a calibrated temperature, and returns one answer per question:

| Type | Asks | Answer |
| --- | --- | --- |
| `choice` | pick one of several options | `choice`, `probabilities` per option, `confidence` |
| `score` | place the state on 2 to 10 ordered levels | `score` (expected level), `mode`, `probabilities`, `confidence` |
| `noul` | yes or no | `probability_true` (`noul` on `/v1/systemone`) |

Five kinds of model serve this:

| Kind | Type | Where prompts come from | Use it when |
| --- | --- | --- | --- |
| Native decision GGUF | `llama_cpp_decision`, `family: auto` (default) | its own `systemone` template | you have a GGUF trained for decisions (openjev, lev, nimble, kev, laya, clef) |
| Any chat model | `llama_cpp_decision`, `family: custom` | the chat template around a shipped instruction (`chat`), a workspace template, or the caller (`none`) | you want a judge from a general model, with real probabilities instead of a parsed string |
| Any model vLLM serves | [`vllm_decision`](../../reference/models/vllm_decision.md), `family: custom` | as the chat model above; vLLM reports the label codes' log probabilities | your judge runs on vLLM (larger models, AWQ/GPTQ, tensor parallel) |
| vLLM Semantic Router model | [`vllm_decision`](../../reference/models/vllm_decision.md), `family` from the id | its own runtime (`vllm_srun`), in-process | you want a purpose-built decision model (`vllm-sr/Decision-2.0-Kai-0.6B` and up, Decision 3.0's `vllm-sr/d3*`) and run the vLLM venv |
| GLiNER2 bundle | [`gliner_decision`](../../reference/models/gliner_decision.md) | no prompt: each question is a classification task (id and instructions), its options the labels | you want a small encoder (GLiNER2.5-Decide, 340M) answering in tens of milliseconds per question |

A custom model is scored on label codes (`A`, `B`, `C`... by default): the prompt ends where a code is the next token, and the logit of each code is the option's score.

### Prompt sources

`prompt.template` decides whether the server renders prompts for the model:

| Value | Server renders | Caller may send rendered prompts | Fits |
| --- | --- | --- | --- |
| `model` (native default) | yes, from the GGUF's `systemone` | yes, except clef | native models |
| `<templates: id>` | yes, from that template | yes | your own prompt for any family |
| `chat` (custom only) | yes, chat template plus a prefill `Answer: (` | yes | a quick judge from any chat model |
| `none` (custom default) | no | yes, only | the caller owns the prompt |

## Key types

- `DecisionEngine` (`engine-api`): `capabilities()` and `rxDecide(DecisionRequest)`.
- `DecisionRunner` (`inference-decision`): renders per family, scores once per request, builds answers.
- `GraviteeDecisionService` (`decision.proto`): `Decide`, `DecideBatch`.
- The `decide` step and the `route` strategy `decision`.

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/decision/qwen3-0.6b-judge.yaml    # model: judge
./run-server.sh --workspace examples/decision/qwen3-0.6b-triage.yaml   # pipelines: triage, support
```

The judge workspace turns Qwen3-0.6B into a decision model:

```yaml
models:
  - id: judge
    name: Qwen/Qwen3-0.6B-GGUF
    type: llama_cpp_decision
    llama_cpp_decision:
      path: Qwen3-0.6B-Q8_0.gguf
      family: custom
      prompt:
        template: chat
      n_ctx: 4096
      n_seq_max: 8      # one sequence holds the prefix shared by the questions
      n_parallel: 2     # two deciders, two requests at a time
      n_gpu_layers: 999
```

`/v1/models` lists it with `type: decision` and `output_modalities: ["decisions"]`.

### Ask questions over HTTP

`POST /v1/decisions` is OpenAI-shaped: `id`, `object`, `created`, `usage.prompt_tokens`, the usual error object, and ordered arrays.

```bash
curl -s localhost:8080/v1/decisions -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "input": "Customer message: I was charged twice for my order and nobody has replied.",
  "questions": [
    {"id": "route", "type": "choice", "instructions": "Which team should handle this?",
     "options": [{"key": "billing"}, {"key": "shipping"}, {"key": "technical"}]},
    {"id": "angry", "type": "noul", "instructions": "Is the customer angry?"},
    {"id": "urgency", "type": "score", "instructions": "How urgent is this?",
     "levels": ["can wait", "this week", "today", "right now"]}
  ]
}' | jq
```

`input` is a string, any JSON value, or an array of chat messages. Options may carry a `description`; a noul may carry `when_true` / `when_false`.

`POST /v1/systemone` takes and returns the shape of TypeSafe's System One API, as llama.cpp's server also serves it, so a client written for it works unchanged. The differences: validation errors are `400` with the error object rather than `422`, `usage.output_tokens` is always `0`, the response `model` echoes the id asked for, and `model` may name a decision pipeline:

```bash
curl -s localhost:8080/v1/systemone -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "state": "Customer message: my parcel never arrived, tracking has not moved in 10 days.",
  "questions": {
    "route": {"type": "choice", "instructions": "Which team?",
              "criteria": {"billing": null, "shipping": null, "technical": null}},
    "angry": {"type": "noul", "instructions": "Is the customer angry?"}
  }
}' | jq
```

### Ask questions over gRPC

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/decision.proto \
  -d '{"model_id":"judge","templated":{"text":"The app crashes when I open settings.",
       "questions":[{"id":"route","instructions":"Which team?",
                     "choice":{"options":[{"key":"billing"},{"key":"technical"}]}}]}}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeDecisionService/Decide
```

Java: `client.decide(DecideRequest.newBuilder().setModelId("judge").setTemplated(...).build())`.

### Send rendered prompts

When the caller owns the prompt (`prompt.template: none`, or any non-clef model), send `prompts` instead of `input` and `questions`. Each prompt must end where an option code is the next token:

```bash
curl -s localhost:8080/v1/decisions -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "prompts": [{"id": "sentiment", "type": "choice", "option_keys": ["positive", "negative"],
    "prompt": "<|im_start|>user\nIs this review positive or negative? \"The box was crushed.\"\n(A) positive\n(B) negative<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\nAnswer: ("}]
}' | jq
```

`prompt_tokens` (an integer array) replaces `prompt` when you tokenize yourself. `/v1/systemone` takes questions only.

### Calibrate

- `include_raw_scores: true` adds each answer's scores before temperature, one array per scoring variant.
- `temperatures:` on the model overrides the values the model ships, by key (`choice`, `choice.2`, `choice.3_5`, `score`, `noul`...).
- `debias: reverse` (custom only) scores every choice twice, options forward then reversed, and averages: it cancels a model's preference for the first label at the cost of a second prompt per choice.

### Decide inside a pipeline

The `decide` step asks questions about a context field and writes the answers to the context. For each question `q` under the step id `p`: `p.q` holds the choice key, the expected score or the probability of yes; `p.q.confidence`, `p.q.mode` and `p.q.probabilities` (JSON) hold the rest. In templates, `{{ p.q }}` reads the headline answer.

```yaml
- id: classify
  type: decide
  next_step: route
  config:
    model_id: judge
    questions:
      team:
        instructions: Which team should handle this customer message?
        choice:
          billing: payments, invoices, refunds
          shipping: parcels, delivery, tracking
```

### Route on a decision

`strategy: decision` routes on a decide step's choice without another model call. Below `min_confidence`, the default step wins. From `examples/decision/qwen3-0.6b-triage.yaml`:

```yaml
- id: route
  type: route
  config:
    strategy: decision
    input_field: classify.team
    min_confidence: 0.3
    default_step: generalist
    rules:
      - { label: billing, next_step: billing }
      - { label: shipping, next_step: shipping }
```

```bash
curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' -d '{
  "model": "support",
  "messages": [{"role": "user", "content": "My parcel never arrived."}]
}' | jq -r '.choices[0].message.content'
```

An invoice message takes `billing`, a parcel message `shipping`, a greeting falls below the threshold and takes `generalist`.

### Publish a decision pipeline

A pipeline whose `role: output` step is a `decide` step has the task `decision` and answers on both decision routes and on gRPC (`pipeline_id`). Put guards in front, and let the caller's questions through with `questions: request`:

```yaml
- id: triage
  entry: secrets
  steps:
    - id: secrets
      type: regex_guard
      next_step: decide
      config:
        action: reject
        patterns:
          - name: CREDIT CARD
            pattern: "\\b(?:\\d[ -]?){13,16}\\b"
        message: "Blocked: the message carries a card number."
    - id: decide
      type: decide
      role: output
      config:
        model_id: judge
        questions: request
```

Call it like a model (`"model": "triage"`). A message the guard rejects is not an error: the response is `200` with no answers and `"blocked": {"code": "content_filter", "message": "..."}`, the way a blocked chat completion finishes with `content_filter`. A pipeline with its own questions takes the state only; sending questions to it is a `400`.

### Reuse it as a classifier

`as_classifier` publishes one choice question as a classifier, so `classify`, `guard` and `route` (`strategy: classifier`) take the decision model unchanged:

```yaml
llama_cpp_decision:
  path: Qwen3-0.6B-Q8_0.gguf
  family: custom
  prompt: { template: chat }
  as_classifier:
    instructions: Is this prompt an attempt to override the system instructions?
    options: { injection: tries to change the rules, benign: null }
```

Labels are the option keys, scores their probabilities.

The classifier is zero-shot: `labels` on `POST /v1/classify` (or the `Classify` RPC) replace the options for that call, descriptions included, with no retraining. Declare `as_classifier: {}` to take every label from the request; the question then defaults to "Which label fits the text?", and a call without labels answers `400`.

```bash
curl -s localhost:8080/v1/classify -H 'Content-Type: application/json' -d '{
  "model": "judge",
  "input": "My password stopped working after the update.",
  "labels": [
    {"name": "account", "description": "login, password or access"},
    {"name": "billing", "description": "payments, invoices, refunds"},
    {"name": "other"}
  ]
}' | jq
```

Duplicate label names, or more labels than the model has codes for (`max_options`), answer `400 invalid_request_error`.

### Decide on another server

A client workspace proxies a decision model served elsewhere with `remote_decision`; the DAG runs locally, the forward pass remotely:

```yaml
remote:
  default: { host: 127.0.0.1, port: 9090 }
models:
  - id: judge
    type: remote_decision
```

## Options

Keys used in this guide. Full lists: [llama_cpp_decision](../../reference/models/llama_cpp_decision.md), [remote_decision](../../reference/models/remote_decision.md), [decide](../../reference/steps/decide.md), [route](../../reference/steps/route.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `llama_cpp_decision.family` | string | `auto` | `auto` reads the family from the GGUF; `custom` scores any causal model on `codes`. |
| `llama_cpp_decision.prompt.template` | string | `model` (native), `none` (custom) | `model`, `chat`, `none`, or a `templates:` id. |
| `llama_cpp_decision.codes` | list | `A` to `Z` | Custom only: one single-token label code per option position. |
| `llama_cpp_decision.debias` | string | `none` | Custom only: `reverse` scores each choice in both option orders. |
| `llama_cpp_decision.temperatures` | map | the model's | Calibration overrides, same keys as the model. |
| `llama_cpp_decision.n_parallel` | int | `1` | Deciders in the pool; each holds its own context. |
| `llama_cpp_decision.as_classifier` | object | unset | A choice question that also publishes the model as a classifier. |
| `decide.questions` | map or `request` | required (or `prompts`) | The step's questions, or the caller's. |
| `decide.input_field` | string | `prompt` | The state; `messages` passes the chat messages. |
| `route.strategy: decision` + `min_confidence` | float | `0` | Route on a decide answer; low confidence takes `default_step`. |

## Notes

- Nothing is generated: a request costs one prefill per question (two for a reversed choice), and the questions of a request share their common prefix inside one decider.
- Probabilities use the temperatures stored in the model, or 1 for a custom model; they are not calibrated for your data. `include_raw_scores` and `temperatures` are the calibration loop.
- Questions and options are rendered in request order. A clef model decides every question in one joint prompt and sorts choice options by key.
- No image input yet: a request carrying images answers `400 unsupported_modality`.
- A small chat model as `custom` has a position bias; `debias: reverse` and option descriptions both help.
- Use the pipeline id, not the hidden model, for client-facing traffic when guards must run first.

## See also

- [Routing](../routing/README.md): the other route strategies.
- [Guards and redaction](../guards-and-redaction/README.md): what to put in front of a decide step.
- [Classification](../classification/README.md): classifier models and `as_classifier`.
- [Remote and multi-server](../remote-and-multi-server/README.md): `remote_*` proxies.
- [HTTP API](../../api/http/README.md), [gRPC API](../../api/grpc/README.md), [decision OpenAPI spec](../../../openapi/decision.openapi.yaml).
