# `break`

> Halts the pipeline when a condition on a context field is met; otherwise continues to `next_step`.

## Overview

`BreakStepExecutor` delegates to `BreakStepEvaluator.evaluate`. When the condition holds, the context is halted with `FINISH_REASON_BREAK_CONDITION` and the named `output_field`; the walk stops and the client receives `COMPLETED` with that finish reason, the accumulated usage and any extracted tool calls. When it does not hold, the step follows `next_step`.

## Usage

End a repair loop cleanly once a valid answer exists (`examples/pipelines/tool-repair.yaml`):

```yaml
- id: done
  type: break
  config:
    output_field: generate.output
    condition:
      type: not_empty
      input_field: generate.output
```

Stop when a classifier is not confident enough:

```yaml
- id: low_confidence
  type: break
  next_step: generate
  config:
    output_field: prompt
    condition:
      type: score_below
      input_field: intent.label     # resolves intent.score
      threshold: 0.4
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `output_field` | string | unset | Context field reported as the pipeline's output on halt (`PipelineContext.breakOutputField`). |
| `condition.type` | string | required | `equals`, `contains`, `label_equals`, `score_above`, `score_below`, `not_empty`, `empty`. See [conditions](./README.md#conditions-break-and-loop). |
| `condition.input_field` | string | required | Flat context key to evaluate. |
| `condition.match_value` | string | unset | Reference for `equals`, `contains`, `label_equals`. Bare YAML booleans become `YES` / `NO`. |
| `condition.threshold` | float | `0` | Reference for `score_above`, `score_below`. `0` is not sent. |

## Context fields

Reads: `condition.input_field`, plus `<input_field>.score` or `<parent>.score` for score conditions.

Writes: nothing. It sets the halt state (`haltReason = BREAK_CONDITION`, `breakOutputField = output_field`).

## Notes

- `break` does not stream anything. Text already streamed by an earlier `output` step is what the client sees; the halt only ends the turn.
- A `break` without `next_step` whose condition is not met is terminal anyway, so it behaves as a plain end of pipeline.
- On the OpenAI HTTP API `BREAK_CONDITION` is reported as `finish_reason: stop`.

## See also

- [Loops and chain-of-thought guide](../../guides/loops-and-cot/README.md)
- [`loop`](./loop.md)
- [Context fields](../context-fields.md)
