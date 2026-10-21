# `guard`

> Classifier-backed input check: reject the request, warn, or redact the matched spans before generation.

## Overview

`GuardStepExecutor` binds to a `ClassifierEngine` and classifies either one context field (`input_field`) or the request's turns selected by `scope`, each turn on its own. It fires when any inspected text matches: any trigger matches (the result contains the trigger's `label` with a score at or above the trigger's `score`), or, for a token-classification engine (`gliner_ner`, ONNX `classifier_mode: TOKEN`, `regex`), when any entity span scores at or above the lowest trigger score. The `action` then decides what happens.

| `action` | Behaviour |
| --- | --- |
| `reject` (default) | Halts the pipeline with `FINISH_REASON_GUARD_BLOCKED`; the client receives a `FAILED` event (`error_code: content_filter`) carrying the rendered `message`. |
| `warn` | Writes `__guard_triggered = <id>`, logs, continues. |
| `redact` | Replaces matched spans in every matching turn of `messages`, keeps `prompt` equal to the last user turn, and writes the redacted last user turn (or the redacted field) to `output_field`. |

## Usage

From `examples/pipelines/guard.yaml`:

```yaml
- id: toxicity_guard
  type: guard
  next_step: pii_guard
  config:
    model_id: toxicity
    input_field: prompt
    action: reject
    trigger:
      label: toxic
      score: 0.75
    message: "Your request was blocked because toxic content was detected (label: {{toxicity_guard.label}}, confidence: {{toxicity_guard.score}})."

- id: pii_guard
  type: guard
  next_step: generate
  config:
    model_id: pii
    input_field: prompt
    action: redact
    output_field: pii_guard.output
    redact_with_entity_type: true
    triggers:
      - { label: person, score: 0.3 }
      - { label: email,  score: 0.3 }
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published classifier model. |
| `input_field` | string | `prompt` | Context key to classify. `prompt` (or unset) means the request itself, inspected per `scope`; any other key is classified alone. |
| `scope` | string | `conversation` | Turns inspected when the guard reads the request (`input_field` unset or `prompt`): `last_user`, `user_turns`, or `conversation` (every user, assistant and tool turn; system turns are excluded). Each turn is checked on its own and any match fires. Refused alongside any other `input_field`. A request sent as a bare prompt is inspected as that prompt. |
| `on_empty` | string | `reject`; `skip` for `warn` and `redact` | What to do when there is no text to inspect (a blank field, or no turn in scope with text, such as an image-only message): `reject` halts with `FINISH_REASON_GUARD_BLOCKED` and the rendered `message`, `skip` passes to the next step. |
| `output_field` | string | `<id>.redacted` | `redact` only: context key receiving the redacted text (the original text when nothing matched). |
| `action` | string | `reject` | `reject`, `warn` or `redact`. Unknown values fall back to `reject`. |
| `trigger` | `{label, score}` | unset | Single trigger. Ignored when `triggers` is non-empty. Also fills the deprecated proto fields `trigger_label` / `trigger_score`. |
| `triggers` | list of `{label, score}` | unset | Several triggers; any match fires. |
| `triggers[].label` | string | required | Classifier label to watch. |
| `triggers[].score` | float | `0` (any score) | Minimum score. `0` is not sent and means any score. |
| `message` | string | unset | Jinja template rendered on `reject` and returned as the failure message. `{{ <id>.label }}` and `{{ <id>.score }}` are available. |
| `redact_with_entity_type` | bool | `false` | `redact` only: replace each span with `[LABEL]` (upper-cased) instead of `************`. |

## Context fields

Reads: `messages` and `prompt` (per `scope`), or `input_field` when it names another key.

Writes, when triggered by a label match:

| Field | Value |
| --- | --- |
| `<id>.label` | Highest-scoring matched trigger label. |
| `<id>.score` | Its score, four decimals. |
| `<id>.labels`, `<id>.scores` | Comma-separated matched labels and scores, highest first. |
| `<id>.details` | `label: score, label: score, ...`; also appended to the `verdicts` log. |

Plus, by action: `warn` writes `__guard_triggered = <id>`; `redact` writes `<output_field>`, rewrites `messages` and `prompt`; `reject` sets the halt message and halts with the `input_field` as the reported output field. A token-entity trigger with no label match writes none of the `<id>.*` fields.

## Notes

- For `redact` on a sequence classifier (no entity spans) the whole text becomes `************`; span-level redaction needs a token-classification model.
- Overlapping or adjacent spans are merged; the merged span takes the label of its highest-scoring member.
- `redact` rewrites every matching turn in scope, tool and assistant turns included, keeping their tool-call metadata; a classified field that is not the request leaves the conversation untouched.
- Inspecting a whole conversation costs one classification per turn with text. Set `scope: last_user` where only the newest turn matters, for instance when an earlier guard already inspected the history.
- With `on_empty: reject` (the default for `reject`), a guard on a model output field blocks an empty generation; set `on_empty: skip` where an empty value is legitimate.
- Several guards can chain; each one reads the field the previous one produced (`input_field: pii_guard.output`).

## See also

- [Guards and redaction guide](../../guides/guards-and-redaction/README.md)
- [`llm_guard`](./llm_guard.md), [`regex_guard`](./regex_guard.md)
- [Context fields](../context-fields.md)
