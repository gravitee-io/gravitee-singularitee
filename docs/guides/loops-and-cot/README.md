# Loops and chain-of-thought

> Build bounded self-refinement cycles: a `loop` step sends execution back to an earlier step until a condition holds, a `break` step ends the pipeline when one does, and infer roles decide which of the intermediate generations the client sees.

## Overview

Three mechanisms combine:

- **Roles.** `role: thinking` streams a step's tokens as reasoning, `role: output` streams
  them as the answer, `role: internal` streams nothing. Judges and graders are internal.
- **`loop`.** Evaluates an exit condition. Met: continue to `next_step`. Not met: increment the
  step's iteration counter and jump to `loopback_step`, optionally appending a rendered
  `loopback_message` to the conversation and installing `retry_sampling_params`. Counter at
  `max_iterations`: go to `fallback_step` (or `next_step` when unset).
- **`break`.** When its condition is met, halts the pipeline with
  `FINISH_REASON_BREAK_CONDITION` and names `output_field` as the response; otherwise follows
  `next_step`.

Because every non-internal infer step appends its output to the conversation, and an infer
step without `prompt:` generates from the accumulated conversation, looping back into a step
continues a real dialogue: the model sees its previous attempt and the feedback turn.

## Key types

| Type | Purpose |
| --- | --- |
| `LoopStepExecutor` | Exit check, iteration counter, loopback message, retry sampling override. |
| `BreakStepExecutor` / `BreakStepEvaluator` | Condition evaluation shared by both steps; `evaluate` signals the halt for `break`. |
| `LoopStepConfig`, `BreakStepConfig`, `StepCondition`, `ConditionKind` | The loop and break plugins' config records; the condition types are engine model classes shared by both. |
| `StepRole` | `STEP_ROLE_THINKING` / `OUTPUT` / `INTERNAL`, set per infer step with `role:`. |
| `JinjaRenderer` | Renders `loopback_message.content` against the full pipeline context. |

## Usage

### Reason, judge, gate, answer

`examples/pipelines/cot.yaml` (pipeline id `cot`) and `examples/modular/pipelines/cot.yaml`
(`cot-pipeline`) share this graph:

```yaml
steps:
  - id: reason                      # hidden reasoning, kept in context
    type: infer
    role: thinking
    next_step: evaluate
    config:
      model_id: llm
      output_field: reason.output
      strip_thinking: true

  - id: evaluate                    # internal judge: YES / NO
    type: infer
    role: internal
    next_step: loop_gate
    config:
      model_id: llm
      output_field: evaluate.output
      prompt:
        messages:
          - role: user
            content: "Question: {{prompt}}\nReasoning so far: {{reason.output}}\nDoes the reasoning reach a clear, complete, final answer? Reply with only YES or NO."

  - id: loop_gate
    type: loop
    next_step: answer
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
        content: "Your previous reasoning was not conclusive (evaluator said '{{evaluate.output}}'). Refine it and work towards a clearer conclusion."

  - id: answer
    type: infer
    role: output
    config:
      model_id: llm
      output_field: answer.output
      prompt:
        messages:
          - role: user
            content: "Based on this reasoning: {{reason.output}}\nGive a clear, concise answer to: {{prompt}}"

  - id: fallback_answer
    type: infer
    role: output
    config:
      model_id: llm
      output_field: fallback_answer.output
      prompt:
        messages:
          - role: user
            content: "Based on the reasoning so far ({{reason.output}}), give your best concise answer to: {{prompt}}. If unsure, say so."
```

Run it:

```bash
./run-server.sh --workspace examples/pipelines/cot.yaml      # or: task run:cot
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "cot",
  "messages": [{"role": "user", "content": "Is 1001 prime?"}]
}' | jq '.choices[0].message'
```

`reason` streams as `reasoning_content`, `answer` as `content`; the YES/NO verdicts never
leave the server.

### Halting on a condition

```yaml
- id: quality_gate
  type: break
  next_step: escalate
  config:
    output_field: draft.output          # the response when the condition fires
    condition:
      type: score_above
      input_field: quality_check.output # a classify step's output field
      threshold: 0.9
```

### Repair loops on failure signals

Every infer step publishes how it finished as context fields, so a gate can react to a broken
generation rather than only to its text:

| Field | Meaning |
| --- | --- |
| `<step>.finish_reason` | `stop`, `length`, `tool_calls`, `guard_blocked`, `break_condition`, `cancelled`, `stalled`, ... |
| `<step>.tool_parse_failed` | `true` only when the model attempted a tool call and extraction found none. Gate repair loops on this field. |
| `<step>.tool_parse_ok`, `<step>.tool_call_count`, `<step>.parse_error`, `<step>.attempted_tool` | Extraction outcome, error text and the tool name the model tried. |
| `<step>.thinking_unclosed` | The whole generation was reasoning: no answer, no tool call. |
| `<loop>.iterations`, `<loop>.max_iterations_reached` | Written by the loop step itself. |

`examples/pipelines/tool-repair.yaml` gates on `generate.tool_parse_failed`, re-enters
`generate` with the parse error as a corrective user turn, tightens sampling on retries, and
falls back to a plain-text answer after three attempts:

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

`tool_names` (the request's declared tool names) is available to every template.
`examples/pipelines/tool-repair-escalate.yaml` points `fallback_step` at an infer step bound
to a larger model instead: the escalation step inherits the conversation, corrective turns
included. See [Tool calling](../tool-calling/README.md).

## Options

Keys this guide uses. Full tables: [`loop`](../../reference/steps/loop.md),
[`break`](../../reference/steps/break.md), [`infer`](../../reference/steps/infer.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `loop.loopback_step` | string | required | Step to re-enter when the condition is not met. |
| `loop.max_iterations` | int | required (> 0) | Ceiling on retries; reached when the counter equals it. |
| `loop.fallback_step` | string | `next_step` | Target once the ceiling is reached. |
| `loop.condition` | block | required | `{type, input_field, match_value?, threshold?}`. |
| `loop.loopback_message` | `{role?, content}` | unset | Appended on the retry edge only; `content` is Jinja, `role` defaults to `user`. |
| `loop.retry_sampling_params` | sampling block | unset | Applied to infer steps on the retry edge; cleared on exit and fallback. Precedence: request > retry > step. |
| `break.condition` | block | required | Same shape as the loop condition. |
| `break.output_field` | string | unset | Field returned as the final response. |
| `condition.type` | enum | required | `equals`, `contains`, `label_equals`, `score_above`, `score_below`, `not_empty`, `empty`. |
| `infer.role` (step level) | enum | `output` | `output`, `thinking`, `internal`. |
| `infer.strip_thinking` | bool | `false` | Drop the reasoning span from the stream and the stored output. |

## Notes

- The loopback message is rendered with Jinja; a render failure or an empty result skips the
  injection with a warning and the loop continues.
- `max_iterations: 3` allows three passes back through the body; the counter is per loop
  step id and persists for the request, so two gates looping into the same step count
  separately.
- `role: internal` output is neither streamed nor appended to the conversation, which keeps a
  "YES"/"NO" verdict out of what later steps see. It is still written to `output_field`.
- Unquoted `YES` / `NO` in YAML parse as booleans; the loader maps them back to the strings,
  but quoting `match_value: "YES"` is safer.
- `score_above` / `score_below` read `<input_field>.score`, which `classify` and `guard` steps
  write next to their label.

## See also

- [Pipelines](../../concepts/pipelines/README.md): edges, termination, message accumulation.
- [Tool calling](../tool-calling/README.md): the extraction signals repair loops gate on.
- [Text generation](../text-generation/README.md): the `infer` step, tags and sampling.
- [Routing](../routing/README.md): branch on a label instead of looping.
- [Context fields](../../reference/context-fields.md): every field a condition can read.
