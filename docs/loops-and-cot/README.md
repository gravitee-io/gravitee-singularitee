# Loops & Chain-of-Thought

> Build bounded self-refinement cycles with `loop` back-edges, `break` halts, and infer step roles that separate reasoning from the final answer.

## Overview
Chain-of-thought pipelines combine three mechanisms. Infer step **roles** decide what the
client sees: `thinking` streams tokens tagged as reasoning, `output` streams the final
answer, and `internal` never streams (judges, graders, routers). A **`loop`** step is a
bounded back-edge: it evaluates an exit condition and either proceeds to `next_step`,
jumps back to `loopback_step` (optionally injecting a Jinja-rendered feedback message
into the conversation), or branches to `fallback_step` when `max_iterations` is
exhausted. A **`break`** step is an output-side halt: when its condition is met, the
pipeline stops and returns the value of `output_field` with
`FINISH_REASON_BREAK_CONDITION`. Because chat-mode infer output is auto-appended to the
conversation, each loop iteration sees the previous reasoning and feedback as real chat
turns.

## Key types
- `LoopStepExecutor` — executes `type: loop`; exit-condition met → `next_step`; not met → increment the per-step iteration counter and jump to `target_step_id` (YAML `loopback_step`), injecting `loopback_message` if configured; counter ≥ `max_iterations` → `fallback_step_id` (or `next_step` when unset).
- `BreakStepExecutor` — executes `type: break`; on condition met, calls `PipelineContext.signalHalt(output_field, FINISH_REASON_BREAK_CONDITION)` and ends the pipeline.
- `BreakStepEvaluator` — shared condition evaluation for both step types (`evaluate` / `evaluateLoopExit`), delegating to `ConditionEvaluatorFactory`.
- `LoopStepConfig` / `BreakStepConfig` / `BreakCondition` — proto definitions in `pipeline.proto`. YAML condition types: `equals`, `contains`, `label_equals`, `score_above`, `score_below`, `not_empty`, `empty`.
- `StepRole` — `STEP_ROLE_THINKING` / `STEP_ROLE_OUTPUT` / `STEP_ROLE_INTERNAL` (YAML `role: thinking|output|internal` on the step, meaningful only for `infer`).
- `InferStepExecutor` — writes each generation to `output_field`, appends it to the `generated_messages` log, and (unless the role is `internal`) appends it to `PipelineContext.messages()` as an assistant turn.
- `JinjaRenderer` / `JinjaContextHelper` — render `loopback_message.content` against the full pipeline context (prompt, step outputs, messages, verdicts).

## Usage
The worked example is `examples/modular/pipelines/cot.yaml` — reason, self-judge, gate, answer:

```yaml
workspace:
  pipelines:
    - id: cot-pipeline
      name: Chain-of-Thought Pipeline
      entry: reason

      steps:
        # Think through the problem. Hidden reasoning (role: thinking) is stripped
        # from the streamed output but kept in context for the steps below.
        - id: reason
          type: infer
          role: thinking
          next_step: evaluate
          config:
            model_id: llm
            output_field: reason.output
            strip_thinking: true

        # Self-judge: is the reasoning conclusive? Emits a strict YES / NO.
        - id: evaluate
          type: infer
          role: internal
          next_step: loop_gate
          config:
            model_id: llm
            output_field: evaluate.output
            prompt:
              messages:
                - role: user
                  content: "Question: {{prompt}}\nReasoning so far: {{reason.output}}\nDoes the reasoning reach a clear, complete, final answer to the question? Reply with only YES or NO."

        # Gate: exit to `answer` when the evaluator says YES; otherwise loop back
        # to `reason` to refine. After max_iterations, go to `fallback_answer`.
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
              content: "Your previous reasoning was not yet conclusive (evaluator said '{{evaluate.output}}'). Continue your thinking — refine your analysis and work towards a clearer conclusion."

        # Condition met — produce the validated answer.
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

        # Max iterations exhausted — best-effort answer.
        - id: fallback_answer
          type: infer
          role: output
          config:
            model_id: llm
            output_field: fallback_answer.output
            prompt:
              messages:
                - role: user
                  content: "Based on the reasoning so far ({{reason.output}}), give your best concise answer to: {{prompt}}. If you are still unsure, say so honestly."
```

Walking one request through: `reason` generates with the caller's messages (its `prompt:`
is omitted, so messages pass through) and appends its output to the conversation as an
assistant turn; `evaluate` judges it — `role: internal` keeps the YES/NO out of both the
stream and the chat history; `loop_gate` checks `contains "YES"` on `evaluate.output`.
On NO it renders `loopback_message` (interpolating `{{evaluate.output}}`), appends it as
a user turn, and jumps back to `reason`, which now sees "previous reasoning + refine
request" as real conversation. After up to 3 iterations without a YES, `fallback_answer`
runs instead of `answer`.

A `break` step halts on a condition instead of branching:

```yaml
- id: quality_gate
  type: break
  next_step: escalate
  config:
    output_field: draft.output           # returned as the final response on trigger
    condition:
      type: score_above
      input_field: quality_check.output  # a classify step's output field
      threshold: 0.9
```

## Options

### `loop` (`LoopStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `loopback_step` | — (required) | Step to jump back to when the exit condition is NOT met (proto: `target_step_id`). |
| `next_step` | — (required) | Step to continue to when the exit condition IS met (declared at the step level in YAML). |
| `condition` | — (required) | `{type, input_field, match_value?, threshold?}` exit condition. |
| `max_iterations` | — (must be > 0) | Hard ceiling on loop iterations. |
| `fallback_step` | `next_step` | Step to branch to when `max_iterations` is reached. |
| `loopback_message` | — | `{role?, content}` appended to the conversation on every retry edge; `content` is a Jinja template, `role` defaults to `user`. |

### `break` (`BreakStepConfig`)
| Field | Default | Purpose |
| --- | --- | --- |
| `condition` | — (required) | `{type, input_field, match_value?, threshold?}` halt condition. |
| `output_field` | — | Context field whose value is returned as the final response on trigger. |

### `condition` (`BreakCondition`, shared by both)
| Type | Uses | Purpose |
| --- | --- | --- |
| `equals` / `label_equals` | `match_value` | Field value (or classifier top label) string-equals `match_value`. |
| `contains` | `match_value` | Field value contains `match_value` as a substring. |
| `score_above` / `score_below` | `threshold` | Classifier top score `>= threshold` / `< threshold` (reads `<input_field>.score`). |
| `not_empty` / `empty` | — | Field value is non-blank / blank. |

### Infer step roles (`StepRole`)
| Role | Streamed | Appended to conversation | Use for |
| --- | --- | --- | --- |
| `output` (default) | yes, tagged OUTPUT | yes | Final answer branches. |
| `thinking` | yes, tagged THINKING | yes | Intermediate reasoning the client may display separately. |
| `internal` | no | no | Judges, graders, routers — metadata like "YES"/"NO". |

## Notes
- **The loopback message fires only on the retry edge** — not on condition-met exit (happy path) and not on the max-iterations fallback. It is rendered by Jinja against the full pipeline context; a render failure or empty result skips the injection with a warning rather than crashing the loop.
- **Message accumulation is what makes CoT conversational**: every non-`internal` infer step appends its output to `PipelineContext.messages()` as an assistant turn, and each generation is also logged to `generated_messages` (keyed by step id, preserved across iterations — unlike `<step_id>.output`, which each run overwrites). An infer step with no `prompt:` config uses the accumulated messages as-is, so a loop back to it naturally continues the conversation.
- **`role: internal` keeps judges out of the chat**: internal output still lands in `output_field` and `generated_messages`, but is neither streamed nor appended to the conversation — a "YES"/"NO" verdict would otherwise pollute what downstream infer steps see.
- **`strip_thinking` vs role `thinking`**: `strip_thinking: true` removes `<think>…</think>` content from both the wire and the step output; without it, reasoning is routed to the client on a separate THINKING-tagged flux while the step output keeps the raw text.
- **Bare YES/NO in YAML**: YAML 1.1 parses unquoted `YES`/`NO` as booleans; the loader normalizes booleans back to the strings `"YES"`/`"NO"`, but quoting `match_value: "YES"` (as `cot.yaml` does) is safer.
- **Iteration counting**: the counter is per loop step id and increments on each not-met evaluation; the fallback branch is taken when `counter >= max_iterations`, so `max_iterations: 3` allows at most 3 passes back through the loop body.
- **`break` halts with `FINISH_REASON_BREAK_CONDITION`** and returns the value of `output_field` as the final response; when the condition is not met, execution simply continues along the step's `next_step` edge.

## See also
- [Text Generation](../text-generation/README.md) — infer step configuration: prompt messages, sampling, reasoning tags, `strip_thinking`.
- [Pipelines](../pipelines/README.md) — the DAG model, edges, and the pipeline context loops read and write.
- [Routing](../routing/README.md) — branch on a label instead of looping on a condition (including `llm_structured` judge routing).
- [Sub-pipelines](../sub-pipelines/README.md) — compose a CoT pipeline as a reusable child of a larger flow.
- [Classification](../classification/README.md) — classify steps whose `label`/`score` outputs feed `label_equals` / `score_above` conditions.
