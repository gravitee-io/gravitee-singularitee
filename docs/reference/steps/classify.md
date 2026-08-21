# `classify`

> Runs a classifier on a context field and writes the top label and its score.

## Overview

`ClassifyStepExecutor` binds to a `ClassifierEngine` (`onnx_classifier`, `gliner_classifier`, `gliner_ner`, `regex`, `composite_classifier`, `remote_classifier`). It classifies the text in `input_field` and stores `topLabel()` under `output_field` and `topScore()` under `<output_field>.score`. The step never branches; pair it with [`route`](./route.md), [`break`](./break.md) or [`loop`](./loop.md) to act on the result.

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
      type: score_below       # reads intent.score
      input_field: intent.label
      threshold: 0.4
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published classifier model. |
| `input_field` | string | `prompt` | Context key holding the text to classify. An empty value skips the step with a warning. |
| `output_field` | string | `<id>.label` | Context key receiving the top label. The score lands in `<output_field>.score`. |
| `threshold` | float | unset | Carried on the wire (`ClassifyStepConfig.threshold`) for downstream readers; the executor itself does not filter on it. Values of `0` are not sent. |

## Context fields

Reads: `input_field` (default `prompt`).

Writes:

| Field | Value |
| --- | --- |
| `<output_field>` | Top label string. |
| `<output_field>.score` | Top score as a float string (`String.valueOf(float)`). |

## Notes

- With the default `output_field`, Jinja sees `{{ intent.label }}` and `{{ intent.score }}`: `intent.label.score` is flattened to `intent = {label: ..., "label.score": ...}` and the nested key is unreachable by dot navigation. Keep `output_field` to a single dot (`<id>.label`) when templates need the score.
- The full per-label score map is not written to the context; only the top entry is. Use [`guard`](./guard.md) with `triggers` to test several labels.

## See also

- [Classification guide](../../guides/classification/README.md)
- [`route`](./route.md), [`guard`](./guard.md)
- [Context fields](../context-fields.md)
