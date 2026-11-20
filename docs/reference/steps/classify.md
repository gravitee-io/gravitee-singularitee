# `classify`

> Runs a classifier on a context field and writes the top label and its score.

## Overview

`ClassifyStepExecutor` binds to a `ClassifierEngine` (`onnx_classifier`, `gliner_classifier`, `gliner_ner`, `regex`, `composite_classifier`, `remote_classifier`, or a decision model with `as_classifier`). It classifies the text in `input_field` and stores `topLabel()` under `output_field` and `topScore()` under `<output_field>.score`. The step never branches; pair it with [`route`](./route.md), [`break`](./break.md) or [`loop`](./loop.md) to act on the result.

## Usage

```yaml
- id: intent
  type: classify
  next_step: gate
  config:
    model_id: router          # a gliner_classifier or onnx_classifier model
    input_field: prompt
    output_field: intent.label

- id: gate
  type: break
  next_step: generate
  config:
    output_field: prompt
    condition:
      type: score_below       # reads intent.label.score
      input_field: intent.label
      threshold: 0.4
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published classifier model. |
| `input_field` | string | `prompt` | Context key holding the text to classify. An empty value skips the step with a warning. |
| `output_field` | string | `<id>.label` | Context key receiving the top label. The score lands in `<output_field>.score`. |
| `threshold` | float | unset | Kept on `ClassifyStepConfig.threshold`; the executor does not filter on it. `0` or a negative value is stored as `0`. |

## Context fields

Reads: `input_field` (default `prompt`).

Writes:

| Field | Value |
| --- | --- |
| `<output_field>` | Top label string. |
| `<output_field>.score` | Top score as a float string (`String.valueOf(float)`). |

## Notes

- Jinja nests context keys on the first dot only, so `output_field: intent.label` gives `intent = {label: ..., "label.score": ...}`. The label is `{{ intent.label }}`; the score is reachable as `{{ intent['label.score'] }}`, and conditions read it through the flat key `intent.label.score`.
- The full per-label score map is not written to the context; only the top entry is. Use [`guard`](./guard.md) with `triggers` to test several labels.

## See also

- [Classification guide](../../guides/classification/README.md)
- [`route`](./route.md), [`guard`](./guard.md)
- [Context fields](../context-fields.md)
