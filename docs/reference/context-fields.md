# Context fields

> Every key a step reads from or writes to the pipeline context, and how templates and conditions reference them.

## Overview

`PipelineContext` is the per-request scratchpad every step shares. It holds a flat `Map<String, String>` of fields (`get` / `set`; `set` ignores null values), plus structured state that is not a string field: the conversation (`messages()`), the request tools, the `generated_messages` and `verdicts` logs, the todo plan, sampling overrides, halt state and usage totals. Conditions (`break`, `loop`) read the flat map by exact key. Jinja templates see a derived view built by `JinjaContextHelper.buildBaseContext`: the flat map is split on the first dot into nested maps (`generate.output` becomes `{{ generate.output }}`), keys starting with `__` are hidden, and the structured state is exposed under its own names.

## Key types

- `PipelineContext`: the scratchpad and its `KEY_*` constants (`prompt`, `reasoning_effort`, `instructions`, `__guard_triggered`, `__selected_tools`, `__condensed_tool_descriptions`, `conversation.repeated_call`, `todos.total`, `todos.completed`, `todos.remaining`).
- `JinjaContextHelper`: builds the Jinja view (`buildBaseContext`, `buildStepOutputContext`, `mergeStepContext`).
- `PromptAssembler`: adds the chat-template variables for `infer` steps.
- `BreakStepEvaluator` / `ScoreResolver`: condition lookups.

## Usage

```yaml
# A condition reads a flat key
condition:
  type: equals
  input_field: generate.tool_parse_failed
  match_value: "false"

# A template reads the nested view
loopback_message:
  content: "Parse error: {{ generate.parse_error }}. Valid tools: {{ tool_names | join(', ') }}."

# The caller seeds extra keys through the request context
# grpc: InferPipelineRequest.context = {"tenant": "acme"}  ->  {{ tenant }}
```

## Options

### Request-level fields

Seeded by `PipelineContext.fromRequest` before the first step runs.

| Key | Type | Source | Notes |
| --- | --- | --- | --- |
| `prompt` | flat field, Jinja `{{ prompt }}` | Last user message of the request conversation, or the bare prompt string. | Rewritten by redacting guards when they operate on it. |
| any `context` entry | flat field | `InferPipelineRequest.context` map (HTTP translates known request fields into it). | Arbitrary `key -> string`. Dotted keys nest in Jinja like step fields. |
| `reasoning_effort` | flat field, Jinja variable | Request context. | Overrides a step's `context.reasoning_effort` in the Jinja and engine template context. |
| `instructions` | flat field | Request context (Responses-style `instructions`). | Prepended as a leading system turn at render time only; never stored in the conversation. |
| `messages` | structured, Jinja `messages` | Request conversation (`role`, `content`, `tool_calls`, `tool_call_id`, `name`, media). | Grows as `infer`, `loop` and `todo` steps append turns. A stored conversation (`previous_response_id`) is prepended first. |
| tools | structured, Jinja `tools` and `tool_names` | `InferPipelineRequest.tools`. | `tools` is only set for `infer` steps, as OpenAI `{type: function, function: {...}}` maps, filtered by `tool_select`. `tool_names` lists every request tool name, everywhere. |
| `sampling_params` | structured | Request override. | Wins over loop retry and step sampling, field by field. |
| `cache_key` | structured | `InferPipelineRequest.cache_key` (HTTP `user` / `prompt_cache_key`). | KV-cache affinity and the todo session key. |

### Derived Jinja variables

Available in every template rendered by a step (`prompt.messages`, raw templates, `system`, guard `message`, `loopback_message`, chat templates).

| Variable | Shape | Meaning |
| --- | --- | --- |
| `system` | string | Content of the first system turn in `messages`, or empty. |
| `history` | string | The conversation as `role: content` lines. |
| `messages` | list of `{role, content, tool_calls?, tool_call_id?, name?}` | The conversation; a bare prompt becomes one user turn. |
| `generated_messages` | list of `{role: assistant, content, step}` | Every `infer` output so far, thinking blocks removed, in execution order. Survives loop iterations. |
| `verdicts` | list of `{verdict, details, step}` | Every `guard` / `llm_guard` verdict so far. |
| `tool_names` | list of string | Names of the request tools. |
| `todos` | list of `{id, title, status, proof}` | The todo plan (`status` is `pending`, `in_progress` or `done`). |
| `constraints` | string | Plan-level constraints recorded by `set_todos`, or empty. |
| `<step_id>` | map | One map per step id with its fields (`{{ generate.output }}`, `{{ toxicity_guard.score }}`). |
| step `context:` entries | any | Overlaid last for `infer` and `llm_guard`, so they shadow the above. |
| `reasoning_effort` | string | Request value when present, otherwise the step's `context` value. |
| `add_generation_prompt`, `bos_token`, `eos_token`, `tools` | chat-template variables | `infer` only (`PromptAssembler`). |

Hidden from Jinja: `prompt` is exposed but excluded from the step-map split; `tools`, `system`, `history` flat keys (if seeded) are skipped; every key starting with `__`.

### Per-step fields

`<id>` is the step id; `<out>` is the step's `output_field` (default shown).

| Step | Field | Value |
| --- | --- | --- |
| `infer` | `<out>` (`<id>.output`) | Full generated text, tool payload re-wrapped in the step's tool tags. |
| `infer` | `<id>.finish_reason` | `stop`, `length`, `tool_calls`, `guard_blocked`, `break_condition`, `max_iterations`, `cancelled`, `stalled`, `unspecified`. |
| `infer` | `<id>.prompt_tokens`, `<id>.completion_tokens`, `<id>.reasoning_tokens` | Engine usage. |
| `infer` | `<id>.prompt_ms` | Prompt evaluation time in ms. |
| `infer` | `<id>.thinking_unclosed` | `true` when the generation ended inside reasoning with no answer. |
| `infer` | `<id>.tool_parse_failed` | `true` only when a call was attempted and nothing parsed; `false` for prose. |
| `infer` | `<id>.tool_parse_ok` | `true` when at least one call was extracted. |
| `infer` | `<id>.tool_call_count` | Number of extracted calls. |
| `infer` | `<id>.parse_error` | Extraction template or JSON error. |
| `infer` | `<id>.attempted_tool` | Leading identifier of a failed call span. |
| `classify` | `<out>` (`<id>.label`) | Top label. |
| `classify` | `<out>.score` | Top score. |
| `embed` | `<out>` (`<id>.embedding`) | `[f0, f1, ...]`. |
| `route` | `<id>.label` | Resolved label. |
| `route` | `<id>.matched` | `true` when a rule matched. |
| `guard` | `<id>.label`, `<id>.score` | Top matched trigger and its score (4 decimals). |
| `guard` | `<id>.labels`, `<id>.scores`, `<id>.details` | All matched triggers, highest first. |
| `guard` | `<out>` (`<id>.redacted`) | `redact` only: the redacted (or untouched) text. |
| `guard`, `llm_guard`, `regex_guard` | `__guard_triggered` | Step id, on `warn`. |
| `llm_guard` | `<id>.verdict` | First line of the verdict. |
| `llm_guard` | `<id>.verdict_full` | Whole verdict. |
| `loop` | `<id>.iterations` | Retries taken. |
| `loop` | `<id>.max_iterations_reached` | `true` once exhausted. |
| `sub_pipeline` | `<out>` (`<id>.output`) | Everything the sub-pipeline streamed. |
| `regex_guard` | `<id>.triggered` | `true` on match. |
| `regex_guard` | `<id>.match`, `<id>.pattern`, `<id>.entity_type` | `reject` / `warn`: first matching entry. |
| `regex_guard` | `<id>.entity_types`, `<out>` (`<id>.output`) | `redact`: matched names and the redacted text. |
| `tool_select` | `__selected_tools` | Comma-joined shortlist. |
| `tool_select` | `__condensed_tool_descriptions` | `name=description` pairs joined by `;`. |
| `todo` | `todos.total`, `todos.completed`, `todos.remaining` | Plan counters. |
| `todo` | `conversation.repeated_call` | Trailing run of identical tool calls, seeded at pipeline start for todo pipelines. |
| `todo` | `<id>.question` | `ask_user` question. |
| `todo` | `<id>.client_tool_calls` | Client-bound calls that ended the turn. |
| `todo` | `<id>.todo_error` | Execution error of a plan call. |

### Structured state touched by steps

| State | Written by | Read by |
| --- | --- | --- |
| `messages` | `infer` (`output` / `thinking` append an assistant turn), `loop` (`loopback_message`), `todo` (call and result turns), redacting guards (rewrite the user turn) | `infer` (passthrough conversation), `tool_select` (last user turn), `sub_pipeline` (`forward_messages`), Jinja `messages` / `history` / `system` |
| `generated_messages` | `infer` | Jinja |
| `verdicts` | `guard`, `llm_guard` | Jinja |
| extracted tool calls | `infer` | `todo`, the final `COMPLETED` event, conversation storage |
| pending narration | `infer` (`role: internal`) | `todo` when halting for client calls |
| retry sampling | `loop` | `infer` |
| halt state (`haltReason`, `breakOutputField`, `haltMessage`) | `break`, `loop` (never), guards, `sub_pipeline`, `todo` | `PipelineExecutor` |
| usage and performance totals | `infer`, `sub_pipeline` | the final `COMPLETED` event |
| todo plan, plan lock, constraints | `todo`, request restore | `todo`, Jinja `todos` / `constraints`, session and conversation stores |

## Notes

- Everything in the flat map is a string. Compare numbers as text (`match_value: "0"`) and booleans as `"true"` / `"false"`.
- Step-map nesting splits on the first dot only. `intent.label.score` renders as `intent["label.score"]`, unreachable with dot syntax; keep `output_field` to one dot.
- `<step>.output` is overwritten on every loop iteration; `generated_messages` keeps each draft.
- `ScoreResolver` for `score_above` / `score_below`: `<input_field>.score`, then `<input_field minus last segment>.score`, then the field value itself parsed as a float, else `0`.
- A `sub_pipeline` forwards the whole flat map as the child request's `context`; nothing but `<out>` comes back.
- `--debug` (TRACE logging) prints the full context before and after every step (`PipelineContext.debugSnapshot`) and the rendering context of each template.

## See also

- [Step types](./steps/README.md)
- [Templates](./templates/README.md)
- [Pipelines concept](../concepts/pipelines/README.md)
