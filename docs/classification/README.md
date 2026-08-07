# Classification

> Classify text with ONNX BERT, GLiNER zero-shot, regex, and composite models — via the `Classify` RPC, the `classify` pipeline step, or `POST /v1/classify`.

## Overview
Classification is served by any model whose engine implements `ClassifierEngine`. Five
model types qualify: `onnx_classifier` (fine-tuned BERT, sequence- or token-level),
`gliner_classifier` and `gliner_ner` (GLiNER2 zero-shot — the label/entity schema is
plain YAML, editable without retraining, and overridable per request), `regex`
(deterministic pattern matching, pure Java, no native library or GPU), and
`composite_classifier` (fans one request out to a list of delegate models and merges
their verdicts). All five return the same `ClassifyResponse` shape: a top label + score,
a per-label score map, and detailed results that carry character spans for
token-classification engines — the spans the guard step's `redact` action masks.
Callers reach a classifier three ways: the `GraviteeInferenceService.Classify` /
`ClassifyBatch` RPCs, a `classify` step inside a pipeline (`ClassifyStepExecutor`), or
the OpenAI-style HTTP endpoint `POST /v1/classify`. Oversized inputs are split and
batched automatically (see Notes).

## Key types
- `ClassifierEngine` — the engine interface: `rxClassify(ClassifyRequest)`, `rxClassify(request, labels)` (per-request GLiNER label override), `rxClassifyBatch`, and `rxClassifyPresplit` (skip internal splitting when a composite already split the input). `task()` reports `text-classification` or `token-classification`.
- `OnnxClassifierEngine` / `OnnxClassifierFactory` — `onnx_classifier` models, backed by `OnnxBertClassifierModel`. `ClassifierMode` is `SEQUENCE` (one label set for the whole input) or `TOKEN` (NER — per-token labels with character spans).
- `GlinerClassifierEngine` / `GlinerNerEngine` — `gliner_classifier` / `gliner_ner` models over the GLiNER4j façade (`GLiNER4jClassifier` / `GLiNER4jNER`). Zero-shot: labels/entities with optional descriptions are supplied at load time and can be replaced per request.
- `GlinerChunking` — long-input chunking for both GLiNER engines: estimates tokens (no tokenizer API), reserves budget for the label prompt inside the ~512-token encoder window (`token_cap`), and splits the rest with `RecursiveTextSplitter`.
- `RegexClassifierEngine` — `regex` models: a list of `{pattern, entity_type}` entries compiled into one alternation regex; every match yields a `ClassifyResult` with score `1.0` and character spans. Task is always `token-classification`.
- `CompositeClassifierEngine` — `composite_classifier` models: runs delegates sequentially in declaration order and merges responses (see Notes for combine semantics).
- `ClassifyStepExecutor` — executes the `classify` pipeline step: writes the top label to `<output_field>` and the score to `<output_field>.score` in the pipeline context.
- `ClassifyRequest` / `ClassifyResponse` / `ClassifyResult` / `ClassifyLabel` — proto messages in `inference.proto` (`ClassifyResult` = `label`, `score`, optional `token`, optional `start`/`end` character offsets).
- `ClassifyHandler` — `POST /v1/classify` (HTTP module); maps to `ClassifyBatchRequest`.
- `RecursiveTextSplitter` — token-budget-aware splitter (paragraph → line → sentence → clause; never mid-word) used by every classifier family for oversized input.

## Usage
Sequence classifier — `examples/classifier/toxicity-bert.yaml`:

```yaml
workspace:
  models:
    - id: toxicity
      name: gravitee-io/distilbert-multilingual-toxicity-classifier
      type: onnx_classifier
      memory_check: disabled
      onnx_classifier:
        model_path: model.quant.onnx
        tokenizer_path: tokenizer.json
        config_json_path: config.json
        labels:
          - non-toxic
          - toxic
        max_sequence_length: 512
        classifier_mode: SEQUENCE
```

Token-level (NER) PII classifier — `examples/classifier/pii-bert.yaml`:

```yaml
workspace:
  models:
    - id: pii
      name: gravitee-io/bert-small-pii-detection
      type: onnx_classifier
      memory_check: disabled
      onnx_classifier:
        model_path: model.onnx
        tokenizer_path: tokenizer.json
        config_json_path: config.json
        max_sequence_length: 512
        classifier_mode: TOKEN
```

GLiNER zero-shot classifier — `examples/classifier/intent-gliner.yaml` (intent routing;
`examples/classifier/guardrails-gliner.yaml` uses the same type for the `gliguard` safety
schema, and `examples/classifier/pii-gliner.yaml` the NER variant for label-free extraction where the
labels come per request):

```yaml
workspace:
  models:
    - id: router
      name: gravitee-io/gliner4j-gliner2-base-v1
      type: gliner_classifier
      memory_check: disabled
      gliner_classifier:
        variant: onnx_fp16
        threshold: 0.3
        labels:
          - name: software development question
            description: >
              User asks about writing, fixing, or understanding code; debugging; ...
          - name: general conversation
            description: >
              User engages in casual or non-specialized interaction; small talk, ...
```

GLiNER zero-shot NER — `examples/classifier/pii-gliner.yaml` (42-type PII schema, trimmed):

```yaml
workspace:
  models:
    - id: pii
      name: gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi
      type: gliner_ner
      memory_check: disabled
      gliner_ner:
        variant: onnx_fp16
        threshold: 0.4
        entities:
          - name: email
            description: Email address
          - name: phone_number
            description: Phone or mobile number
          - name: card_number
            description: Credit / debit card number
```

Regex + composite — a production PII workspace can layer five regex models, a regex
composite, and a GLiNER NER model into a single `pii-detector` (abridged):

```yaml
workspace:
  name: pii
  models:
    - id: pii-regex-contact
      type: regex
      regex:
        patterns:
          - pattern: '(?U)\b[\p{L}\p{Nd}._%+-]+@[\p{L}\p{Nd}.-]+\.\p{L}{2,}\b'
            entity_type: EMAIL
          - pattern: '\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b'
            entity_type: IPV4_ADDRESS

    - id: pii-regex               # composite of the 5 regex category models
      type: composite_classifier
      composite_classifier:
        models: [pii-regex-contact, pii-regex-financial, pii-regex-government,
                 pii-regex-secrets, pii-regex-device]

    - id: pii-ner                 # GLiNER2 Pii-Multi, full 42-type schema
      type: gliner_ner
      name: gravitee-io/gliner4j-gliner2-privacy-filter-PII-multi
      memory_check: disabled
      gliner_ner:
        variant: onnx
        threshold: 0.4
        entities: [...]

    - id: pii-detector            # union of regex + NER
      type: composite_classifier
      composite_classifier:
        models: [pii-regex, pii-ner]
```

`classify` pipeline step (writes `intent` and `intent.score` into the pipeline context):

```yaml
steps:
  - id: classify_intent
    type: classify
    next_step: route_by_intent
    config:
      model_id: router
      input_field: prompt
      output_field: intent
```

HTTP — `POST /v1/classify` (requires `http.enabled: true` in `gravitee.yml`; default port 8080).
`input` is a string or array; the optional `labels` array is the per-request GLiNER override:

```bash
curl -s http://localhost:8080/v1/classify \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "pii-detector",
    "input": "Contact john.doe@acme.com or +1 (415) 555-0134",
    "labels": [
      {"name": "email", "description": "Email address"},
      {"name": "phone_number", "description": "Phone or mobile number"}
    ]
  }'
# → {"object":"classification","model":"pii-detector",
#    "results":[{"top_label":"EMAIL","top_score":1.0,
#                "scores":{"EMAIL":1.0,"PHONE_US":1.0,...},
#                "spans":[{"label":"EMAIL","score":1.0,
#                          "token":"john.doe@acme.com","start":8,"end":25}, ...]}]}
```

## Options

### `onnx_classifier`
| Field | Default | Purpose |
| --- | --- | --- |
| `model_path` | — | Path to the ONNX model file inside the HF bundle. |
| `tokenizer_path` | — | HuggingFace tokenizer file/directory. |
| `config_json_path` | — | Optional `config.json` for the `id2label` mapping. |
| `labels` | from `config.json` | Ordered class labels matching output logit indices. |
| `max_sequence_length` | `0` (library default 510) | Tokenizer sequence cap. |
| `classifier_mode` | `SEQUENCE` | `SEQUENCE` (whole-input label) or `TOKEN` (NER with spans). |

### `gliner_classifier` / `gliner_ner`
| Field | Default | Purpose |
| --- | --- | --- |
| `model_dir` | — | Root model directory (contains `onnx/`, tokenizer, config); usually resolved from `name`. |
| `labels` / `entities` | — | Zero-shot schema: `{name, description}` entries; the description biases the label embedding. |
| `threshold` | `0` (model default) | Minimum confidence (0.0–1.0). |
| `variant` | `onnx` | ONNX variant folder: `onnx`, `onnx_fp16`, `onnx_quantized`. |
| `token_cap` | `0` (512) | Encoder token window including the label prompt; long inputs are chunked to fit. |

### `regex` / `composite_classifier`
| Field | Default | Purpose |
| --- | --- | --- |
| `regex.patterns[].pattern` | — | Java regex; matches become `ClassifyResult` spans with score `1.0`. |
| `regex.patterns[].entity_type` | — | Label attached to matches of that pattern. |
| `composite_classifier.models` | — | IDs of other models in the same workspace, invoked in order. |

### `classify` step config
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | — | Logical id of a classifier model. |
| `input_field` | step input | Pipeline-context key to read the text from. |
| `output_field` | `<step_id>.label` | Key for the top label; score goes to `<output_field>.score`. |
| `threshold` | `0` | Informational — used by `guard`/`break` conditions that reference this step's output. |

## Notes
- **`classifier_mode` values are exactly `SEQUENCE` and `TOKEN`** (`ClassifierMode` enum). There is no separate `NER` value — token mode *is* NER; the engine's `task()` becomes `token-classification` and results carry `start`/`end` spans.
- **Per-request label overrides are GLiNER-only.** `ClassifyRequest.labels` / `ClassifyBatchRequest.labels` (and the HTTP `labels` array) replace the model's configured schema for that request; ONNX/regex/composite engines silently ignore them (the default `rxClassify(request, labels)` falls back to `rxClassify(request)`).
- **Composite combine semantics** (`CompositeClassifierEngine.merge`): `results` is the *union* of all delegate spans (original offsets preserved); `allScores` merges per label with **max wins**; `topLabel` is the first non-null top label in delegate order; `topScore` is the max across delegates. No delegate matching anything yields the empty response (`topLabel = null`, coalesced to `""` on the wire).
- **Oversized input is split automatically**, never truncated. Each family splits with `RecursiveTextSplitter` (semantic boundaries first — paragraph → line → sentence → clause — token-window fallback, never mid-word): ONNX sequence classifiers batch-classify the chunks and keep one row per (label, split) with the headline score = max across splits; ONNX token classifiers use a sliding window; GLiNER budgets `token_cap − estimated label prompt` per chunk (`GlinerChunking`, ~3.5 chars/token estimate since gliner4j exposes no tokenizer) and rolls scores up as max per label; regex chunks at ~4096 estimated tokens to bound backtracking. All span offsets are shifted back to the original text.
- **A composite splits once for everyone**: it chunks the input at its own 4096-token budget and hands chunks to delegates via `rxClassifyPresplit`, so the same huge document is not re-split per delegate. Chunks never overlap, so a regex match straddling a chunk boundary can in principle be missed.
- **Regex matches all report score `1.0`** and `topLabel` is the *first match in the text*, not the "strongest" — thresholds on regex-backed guards are effectively binary.
- **`regex` and `composite_classifier` are workspace-only types** (`ModelType.isClientLocal()`): pure-Java, they run in-process on server or client — declare them in `workspace.yaml`; they have no engine config in `model.proto`.
- **GLiNER classifier chunk rows**: for a chunked input, `results` holds one row per (label, chunk) tagged with the *chunk's* span — those spans locate the chunk, not an entity. Only NER/regex spans are entity spans; the HTTP handler emits `spans` only for entries carrying offsets.
- **Guard threshold interplay**: `ClassifyStepExecutor` stores only the top label/score; span-based redaction and trigger thresholds live in the `guard` step, not here.

## See also
- [Guards & Redaction](../guards-and-redaction/README.md) — reject/warn/redact on classifier verdicts, including span-based PII masking.
- [Routing](../routing/README.md) — branch a pipeline on a classifier's top label.
- [Pipelines](../pipelines/README.md) — the step DAG the `classify` step runs in.
- [Embeddings & Reranking](../embeddings-and-reranking/README.md) — the other encoder-model families, sharing the same smart content split.
- [OpenAI HTTP API](../openai-http-api/README.md) — enabling the HTTP listener that serves `/v1/classify`.
- [Workspaces](../workspaces/README.md) — declaring models and logical ids in `workspace.yaml`.
