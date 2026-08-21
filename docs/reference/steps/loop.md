# `loop`

> Bounded back-edge: re-run an earlier step until a condition holds, optionally injecting a corrective turn and tighter sampling on every retry.

## Overview

`LoopStepExecutor` evaluates `condition` against the context. When it holds, the step clears any retry sampling override and exits through the envelope `next_step`. When it does not, the step increments its iteration counter; below `max_iterations` it injects `loopback_message` into the conversation, installs `retry_sampling_params`, and jumps to `loopback_step`; at the ceiling it clears the override and goes to `fallback_step` (or `next_step` when unset). The counter lives in the `PipelineContext`, so it is per request and per loop step.

## Usage

Chain-of-thought refinement (`examples/pipelines/cot.yaml`):

```yaml
- id: loop_gate
  type: loop
  next_step: answer              # exit edge when the condition holds
  config:
    loopback_step: reason
    fallback_step: fallback_answer
    max_iterations: 3
    condition:
      type: contains
      input_field: evaluate.output
      match_value: "YES"
    loopback_message:
      role: user
      content: "Your previous reasoning was not yet conclusive (evaluator said '{{evaluate.output}}'). Continue your thinking and work towards a clearer conclusion."
```

Tool-call repair with deterministic retries (`examples/pipelines/tool-repair.yaml`):

```yaml
- id: repair_gate
  type: loop
  next_step: think_gate
  config:
    loopback_step: generate
    fallback_step: fallback
    max_iterations: 3
    condition:
      type: equals
      input_field: generate.tool_parse_failed
      match_value: "false"
    loopback_message:
      role: user
      content: "Your previous reply did not contain a valid tool call.{% if generate.attempted_tool %} You called '{{ generate.attempted_tool }}', which is not a declared tool.{% endif %}{% if generate.parse_error %} Parse error: {{ generate.parse_error }}.{% endif %} The ONLY valid tools are: {{ tool_names | join(', ') }}. Reply again with a single valid tool call."
    retry_sampling_params:
      temperature: 0.2
```

A one-shot branch (`examples/pipelines/todo-agent.yaml`): `max_iterations: 1` with `condition: { type: empty, input_field: todos.total }` sends a request with no plan to `next_step` and one with a restored plan straight to `fallback_step`.

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `next_step` (envelope) | string | unset | Exit edge taken when the condition holds. Proto `next_step_id`. |
| `loopback_step` | string | required | Step to jump back to on retry. Proto `target_step_id`. |
| `max_iterations` | int | `0` | Retry ceiling. Only positive values are sent; `0` leaves the loop unbounded. Always set it. |
| `fallback_step` | string | `next_step` | Step taken when the ceiling is reached. |
| `condition.type` | string | required | `equals`, `contains`, `label_equals`, `score_above`, `score_below`, `not_empty`, `empty`. See [conditions](./README.md#conditions-break-and-loop). |
| `condition.input_field` | string | required | Flat context key to evaluate. |
| `condition.match_value` | string | unset | Reference for `equals`, `contains`, `label_equals`. Bare YAML booleans are normalised to `YES` / `NO`. |
| `condition.threshold` | float | `0` | Reference for `score_above`, `score_below`. |
| `loopback_message.role` | string | `user` | `system`, `user` or `assistant`; unknown values fall back to `user`. |
| `loopback_message.content` | string | unset | Jinja template appended to `messages` on every retry. A render failure or an empty result skips the injection without failing the loop. |
| `retry_sampling_params` | object | unset | `max_tokens`, `temperature`, `top_p`, `presence_penalty`, `frequency_penalty` applied to infer steps on the retry edge only. Precedence: request override > retry > step. Cleared on exit and on fallback. |

## Context fields

Reads: `condition.input_field` (and `<input_field>.score` for score conditions), the Jinja base context for `loopback_message`.

Writes:

| Field | Value |
| --- | --- |
| `<id>.iterations` | Number of retries taken so far (incremented each time the condition fails). |
| `<id>.max_iterations_reached` | `true` once the ceiling is hit. |

Also appends `loopback_message` to `messages` and installs or clears the retry sampling override on the `PipelineContext`.

## Notes

- The loop does not halt. A `FINISH_REASON_MAX_ITERATIONS` is defined on the wire but the executor branches to `fallback_step` instead of emitting it; put a [`break`](./break.md) or an `infer` on that branch.
- `loopback_message` is injected only on the retry edge, never on exit or fallback.
- `<step>.output` of the re-run step is overwritten each iteration; `generated_messages` keeps every draft for templates that need the history.
- An `infer` step re-entered through the loop renders the conversation again, so the injected turn is visible to the model only when that step uses the conversation (no `prompt.messages` override).

## See also

- [Loops and chain-of-thought guide](../../guides/loops-and-cot/README.md)
- [`break`](./break.md), [`infer`](./infer.md)
- [Context fields](../context-fields.md)
