# `classify`

> Runs a classifier on a context field and writes the top label and its score.

## Overview

`ClassifyStepExecutor` binds to a `ClassifierEngine` (`onnx_classifier`, `gliner_classifier`, `gliner_ner`, `regex`, `composite_classifier`, `remote_classifier`, or a decision model with `as_classifier`). It classifies the text in `input_field` and stores `topLabel()` under `output_field` and `topScore()` under `<output_field>.score`. The step never branches; pair it with [`route`](./route.md), [`break`](./break.md) or [`loop`](./loop.md) to act on the result.

With `labels`, the step brings its own label set to a zero-shot model (`gliner_classifier`, `gliner_ner`, a decision model published as a classifier): every call sends them in place of the model's, as a `/v1/classify` request's `labels` does. Several steps then share one loaded model, each with its own labels, and changing a step's labels is a pipeline change that a synced node applies without a restart.

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

Two steps sharing one GLiNER2 model, each with its own labels (`examples/pipelines/gliner-step-labels.yaml`):

```yaml
- id: mood
  type: classify
  next_step: topic
  config:
    model_id: zero-shot       # a gliner_classifier declared without labels
    labels: [angry, neutral, happy]

- id: topic
  type: classify
  config:
    model_id: zero-shot       # the same model, other labels
    labels:
      - name: billing
        description: The user asks about an invoice, a charge or a refund.
      - name: technical problem
        description: Something does not work.
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published classifier model. The codec does not check it: a missing id fails the step at run time (`model '' is not loaded`). |
| `input_field` | string | `prompt` | Context key holding the text to classify. An empty value skips the step with a warning. |
| `output_field` | string | `<id>.label` | Context key receiving the top label. The score lands in `<output_field>.score`. |
| `threshold` | float | unset | Carried on the wire (`ClassifyStepConfig.threshold`) for downstream readers; the executor itself does not filter on it. Values of `0` are not sent. |
| `labels` | list | unset | Labels sent with every call in place of the model's own: names, or `{name, description}` objects (the shapes `/v1/classify` takes; the model reads the description). Needs a model that takes request labels; see Notes. Unset or empty: the model's own labels, as before. |

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
- `labels` are checked at pipeline load, never at request time: a name that is blank or declared twice fails the load, and so does a step with `labels` on a model that cannot take request labels (`onnx_classifier`, `regex`, `llm_token_classifier`, a composite with a member that cannot), with a message naming the step and the model. A release applied in place is checked the same way; [Releases applied without a restart](../configuration.md#releases-applied-without-a-restart) says which failures leave the pipeline out and which refuse the release.
- A `remote_classifier` forwards the labels to its server, which honours them only when the model behind it is zero-shot: the proxy is registered without probing the remote, so the load check cannot tell.
- A `composite_classifier` takes `labels` only when every member does: a member that ignores request labels (regex, ONNX) would answer with its own.
- The answer always stays within `labels`: should the model still name another label (a `remote_classifier` whose remote model has fixed labels), that label and its score are dropped and the best remaining label wins; with none left, `label` is unset.
- GLiNER scores a call with request labels on its own instead of through the micro-batcher that coalesces calls using the model's labels.

## See also

- [Classification guide](../../guides/classification/README.md)
- [`route`](./route.md), [`guard`](./guard.md)
- [Context fields](../context-fields.md)
