# Guards & Redaction

> Screen pipeline input with classifier, LLM-as-judge, or regex guards, and reject, warn, or redact before generation.

## Overview
Three guard step types protect a pipeline before (or between) generation steps. A `guard`
step runs the input through a `ClassifierEngine` (ONNX sequence classifier, GLiNER
zero-shot, regex model, or a composite) and fires when a configured trigger label scores
above its threshold — or, for token-classification engines, when any entity span is
detected. An `llm_guard` step is LLM-as-judge: it prompts a `TextGenEngine` (e.g. Llama
Guard) and checks whether the verdict starts with a safe token. A `regex_guard` step is
model-free: a list of named Java regex patterns evaluated directly. All three share the
same action vocabulary — `reject` halts the pipeline with `FINISH_REASON_GUARD_BLOCKED`,
`warn` logs and passes through, `redact` masks the matched spans and lets the sanitized
text flow downstream.

## Key types
- `GuardStepExecutor` — executes `type: guard`; classifies via `ClassifierEngine.rxClassify` and applies the action. Span redaction only for engines whose task is `TOKEN_CLASSIFICATION` (NER/regex); sequence classifiers redact the whole input to `************`.
- `LlmGuardStepExecutor` — executes `type: llm_guard`; renders `prompt` (messages or raw template) through Jinja, generates a verdict on an internal non-streamed sequence, and compares it against `safe_token` (default `"safe"`, case-insensitive prefix match on the first token).
- `RegexGuardStepExecutor` — executes `type: regex_guard`; deprecated in favour of a workspace `regex` model behind a generic `guard` step (`RegexClassifierEngine` + `CompositeClassifierEngine`).
- `GuardStepConfig` / `LlmGuardStepConfig` / `RegexGuardStepConfig` — proto configs in `pipeline.proto`.
- `GuardAction` — `GUARD_ACTION_REJECT` / `GUARD_ACTION_REDACT` / `GUARD_ACTION_WARN` (YAML: `reject` / `redact` / `warn`).
- `GuardTrigger` — a `{label, score}` pair; the guard fires when the classifier returns that label at or above the score.
- `RegexEntityDef` — a `{name, pattern}` entry for `regex_guard`; names are free-form (spaces/dashes allowed) because the executor generates positional named groups internally.
- `FinishReason.FINISH_REASON_GUARD_BLOCKED` — the halt reason set by `reject`.

## Usage
Rejection guard — `examples/modular/pipelines/toxicity-guard.yaml` (`toxicity` is a classifier model id, `llm` a text-gen model id):

```yaml
workspace:
  pipelines:
    - id: toxicity-guard-pipeline
      name: Toxicity Guard Pipeline
      entry: toxicity_guard

      steps:
        - id: toxicity_guard
          type: guard
          next_step: generate
          config:
            model_id: toxicity
            input_field: prompt
            action: reject
            trigger:
              label: toxic
              score: 0.75
            message: "Your request was blocked because toxic content was detected (label: {{toxicity_guard.label}}, confidence: {{toxicity_guard.score}})."

        - id: generate
          type: infer
          role: output
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

Redaction guard — `examples/modular/pipelines/pii-redact.yaml`: the `pii` NER classifier redacts
detected spans, and the downstream infer step reads the sanitized text via
`{{pii_guard.output}}`:

```yaml
steps:
  - id: pii_guard
    type: guard
    next_step: generate
    config:
      model_id: pii
      input_field: prompt
      action: redact
      output_field: pii_guard.output
      trigger:
        label: PII
        score: 0.5
```

Multiple triggers (any match fires) — the shape recommended in `pii-redact.yaml` for the
zero-shot GLiNER backend, and the natural pairing with the `gliguard` multi-category
classifier from `examples/classifier/guardrails-gliner.yaml`:

```yaml
triggers:
  - { label: person,       score: 0.3 }
  - { label: email,        score: 0.3 }
  - { label: phone_number, score: 0.3 }
```

Production-grade classifier backends: `examples/classifier/guardrails-gliner.yaml`
declares the `gliguard` GLiNER zero-shot safety classifier (labels like `malicious`,
`prompt_injection`, `jailbreak_attempt`), and `examples/classifier/pii-gliner.yaml` declares
`pii-detector` — a `composite_classifier` unioning five deterministic `regex` models
(contact, financial, government, secrets, device) with the `pii-ner` GLiNER2 42-entity
NER model.

## Options

### `guard` (`GuardStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | — (required) | Workspace model id; must resolve to a `ClassifierEngine`. |
| `input_field` | `prompt` | Context field to classify. |
| `action` | `reject` | `reject`, `warn`, or `redact`. |
| `trigger` | — | Single `{label, score}` (deprecated fallback, used only when `triggers` is empty). |
| `triggers` | `[]` | List of `{label, score}`; the guard fires if ANY matches. |
| `output_field` | `<step_id>.redacted` | Where the (possibly redacted) text is written for `redact`. |
| `message` | — | Jinja-templated reject message returned to the caller (e.g. `{{<step_id>.label}}`, `{{<step_id>.score}}`). |
| `redact_with_entity_type` | `false` | Replace spans with `[ENTITY_TYPE]` (upper-cased label) instead of `************`. |

### `llm_guard` (`LlmGuardStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `model_id` | — (required) | Workspace model id; must resolve to a `TextGenEngine`. |
| `action` | `reject` | `reject` or `warn` (`redact` is unsupported — falls back to `warn`). |
| `safe_token` | `safe` | First token expected when content is safe; case-insensitive prefix comparison. |
| `prompt` | — | `messages:` list or raw `template:` — each content is a Jinja template over the pipeline context. |
| `sampling` | `max_tokens: 64` | Optional sampling override (`temperature`, `top_p`, `max_tokens`). |
| `message` | — | Jinja-templated reject message. |
| `context` | — | Per-step template variables (e.g. a `categories` list iterated with `{% for %}`). |

### `regex_guard` (`RegexGuardStepConfig`) — deprecated
| Field | Default | Purpose |
| --- | --- | --- |
| `input_field` | `prompt` | Context field to scan. |
| `patterns` | — (required) | List of `{name, pattern}` Java regex entries; empty list skips the step. |
| `action` | `reject` | `reject`/`warn` match a single combined alternation; `redact` collects all spans per pattern. |
| `redact_with_entity_type` | `false` | Replace spans with `[NAME]` instead of `[REDACTED]`. |
| `output_field` | `<step_id>.output` | Where the (possibly redacted) text is written. |
| `message` | — | Jinja-templated reject message. |

## Notes
- **Redaction placeholders differ by step type**: `guard` uses `[ENTITY_TYPE]` or `************`; `regex_guard` uses `[NAME]` or `[REDACTED]`.
- **Overlapping-span merging**: both redactors sort spans by start offset and merge spans that overlap or are adjacent (`next.start <= current.end + 1`); in `GuardStepExecutor` the merged span keeps the label of its highest-scoring member. Replacement runs right-to-left so earlier offsets stay valid.
- **Redaction rewrites the conversation**: after redacting, USER messages whose content equals the original text are replaced with the redacted text, and when `input_field` is empty or `prompt`, `KEY_PROMPT` is overwritten too — so `{{ prompt }}`, `{{ messages }}`, and `{{ history }}` never leak the original input downstream.
- **Entity spans only count for token classifiers**: `GuardStepExecutor` gates span-bearing results on `ModelTasks.TOKEN_CLASSIFICATION`. A sequence classifier whose long input was chunk-split also carries spans, but those are chunk ranges, not entities — they neither trigger the guard nor get redacted.
- **Redact with no trigger still writes `output_field`**: on a clean pass with `action: redact`, the original text is copied to `output_field`, so downstream `{{<step_id>.output}}` references always resolve.
- **How `GUARD_BLOCKED` surfaces**: `reject` calls `PipelineContext.signalHalt(..., FINISH_REASON_GUARD_BLOCKED)` with the rendered `message` as the halt message. Over gRPC the response completes with that finish reason and guard message; the OpenAI HTTP layer (`WriteStreamTokenAdapter`) maps `FINISH_REASON_GUARD_BLOCKED` to `finish_reason: "content_filter"` (and a `content_filter`-coded failure to HTTP 400). A `sub_pipeline` step propagates a child's non-STOP finish reason (including `GUARD_BLOCKED`) into the parent context, so a guard inside a nested pipeline halts the whole chain.
- **Trigger variables for templates**: on trigger, `guard` publishes `<step_id>.label`, `<step_id>.score` (top match), `<step_id>.labels`, `<step_id>.scores`, `<step_id>.details`, plus an entry in the `verdicts` log; `llm_guard` publishes `<step_id>.verdict` (first line) and `<step_id>.verdict_full`; `regex_guard` publishes `<step_id>.triggered`, `<step_id>.match`, `<step_id>.pattern`, `<step_id>.entity_type` (trigger mode) or `<step_id>.entity_types` (redact mode).
- **`warn` is non-blocking**: it sets `guard_triggered` to the step id in the context and logs — nothing is streamed or rejected.
- **LLM guard verdicts are never streamed**: the judge runs on a `STEP_ROLE_INTERNAL` capture stream with thinking always stripped, so reasoning tokens cannot contaminate the `safe_token` check.
- **Prefer models over `regex_guard`**: declare a `regex` (or `composite_classifier`) model in the workspace — as `examples/classifier/pii-gliner.yaml` does — and reference it from a generic `guard` step; `regex_guard` is kept for backward compatibility only.

## See also
- [Pipelines](../pipelines/README.md) — the DAG model, step wiring, and the pipeline context that guards read and write.
- [Classification](../classification/README.md) — the classifier engines (ONNX, GLiNER, regex, composite) that back `guard` steps.
- [Routing](../routing/README.md) — branch on a classifier label instead of blocking on it.
- [Sub-pipelines](../sub-pipelines/README.md) — how a child pipeline's `GUARD_BLOCKED` halt propagates to the parent.
- [OpenAI HTTP API](../openai-http-api/README.md) — how guard blocks surface as `content_filter` to OpenAI clients.
