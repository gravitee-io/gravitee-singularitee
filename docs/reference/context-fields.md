# Context fields

> Every key a step reads from or writes to the pipeline context, and how templates and conditions reference them.

## Overview

`PipelineContext` is the per-request scratchpad every step shares. It holds a flat `Map<String, String>` of fields (`get` / `set`; `set` ignores null values), plus structured state that is not a string field: the conversation (`messages()`), the request tools, the `generated_messages` and `verdicts` logs, the todo plan, sampling overrides, halt state and usage totals. Conditions (`break`, `loop`) read the flat map by exact key. Jinja templates see a derived view built by `TemplateContextHelper.buildBaseContext`: dotted keys of the flat map are split on the first dot into nested maps (`generate.output` becomes `{{ generate.output }}`), undotted keys are not exposed (except `prompt`, and `reasoning_effort` for `infer`), keys starting with `__` are hidden, and the structured state is exposed under its own names.

## Key types

- `PipelineContext`: the scratchpad and its `KEY_*` constants (`prompt`, `reasoning_effort`, `instructions`, `__guard_triggered`, `__tool_call_step`, `__selected_tools`, `__condensed_tool_descriptions`, `conversation.repeated_call`, `todos.total`, `todos.completed`, `todos.remaining`).
- `TemplateContextHelper` (engine-api): builds the Jinja view (`buildBaseContext`, `buildStepOutputContext`, `mergeStepContext`).
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

# The caller seeds extra keys through the request context; dotted keys reach Jinja
# grpc: InferPipelineRequest.context = {"request.tenant": "acme"}  ->  {{ request.tenant }}
```

## Options

### Request-level fields

Seeded by `PipelineContext.fromRequest` before the first step runs.

| Key | Type | Source | Notes |
| --- | --- | --- | --- |
| `prompt` | flat field, Jinja `{{ prompt }}` | Last user message of the request conversation, or the bare prompt string. | Rewritten by redacting guards when they operate on it. |
| any `context` entry | flat field | `InferPipelineRequest.context` map (HTTP translates known request fields into it). | Arbitrary `key -> string`. Dotted keys nest in Jinja like step fields; undotted keys are readable by conditions through the flat map but are not in the Jinja context. A key the pipeline writes itself is ignored: one whose top-level segment is a step id, the top-level segment of a step's `output_field`, `prompt`, `todos` or `conversation`, and every key starting with `__`. |
| `reasoning_effort` | flat field, Jinja variable (`infer` only) | Request context. | Overrides an `infer` step's `context.reasoning_effort` in the Jinja and engine template context. Not applied to `llm_guard`, which uses only its step `context:`. |
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
| `messages` | list of `{role, content}` | The conversation; a bare prompt becomes one user turn. An `infer` step's chat template receives a richer list (`tool_calls`, `tool_call_id`, `name` when present). |
| `generated_messages` | list of `{role: assistant, content, step}` | Every `infer` output so far, thinking blocks removed, in execution order. Survives loop iterations. |
| `verdicts` | list of `{verdict, details, step}` | Every `guard` / `llm_guard` verdict so far. |
| `tool_names` | list of string | Names of the request tools. |
| `tool_calls` | list of `{name, arguments, description}` | The tool calls extracted so far (`arguments` as JSON text, `description` from the request tool). Also mirrored as `<step>.tool_calls` on the step that produced them (`__tool_call_step`). |
| `todos` | list of `{id, title, status, proof}` | The todo plan (`status` is `pending`, `in_progress` or `done`). |
| `constraints` | string | Plan-level constraints recorded by `set_todos`, or empty. |
| `<step_id>` | map | One map per step id with its fields (`{{ generate.output }}`, `{{ toxicity_guard.score }}`). |
| step `context:` entries | any | Overlaid for `infer` and `llm_guard`, so they shadow the above. |
| `reasoning_effort` | string | `infer` only: request value when present, otherwise the step's `context` value. For `llm_guard`, only the step's `context` value. |
| `add_generation_prompt`, `bos_token`, `eos_token`, `tools` | chat-template variables | `infer` only (`PromptAssembler`). |

Hidden from Jinja: `prompt` is exposed but excluded from the step-map split; `tools`, `system`, `history` flat keys (if seeded) are skipped; every other undotted key; every key starting with `__`.

### Per-step fields

`<id>` is the step id; `<out>` is the step's `output_field` (default shown).

| Step | Field | Value |
| --- | --- | --- |
| `infer` | `<out>` (`<id>.output`) | Full generated text, tool payload re-wrapped in the step's tool tags. |
| `infer` | `<id>.finish_reason` | `stop`, `length`, `tool_calls`, `guard_blocked`, `break_condition`, `max_iterations`, `cancelled`, `stalled`, `start_failed`. Not written when the engine reports no finish reason. |
| `infer` | `<id>.prompt_tokens`, `<id>.completion_tokens`, `<id>.reasoning_tokens` | Engine usage. |
| `infer` | `<id>.prompt_ms` | Prompt evaluation time in ms. |
| `infer` | `<id>.thinking_unclosed` | `true` when the generation ended inside reasoning with no answer. |
| `infer` | `<id>.tool_parse_failed` | `true` only when a call was attempted and nothing parsed; `false` for prose. |
| `infer` | `<id>.tool_parse_ok` | `true` when at least one call was extracted. |
| `infer` | `<id>.tool_call_count` | Number of extracted calls. |
| `infer` | `<id>.parse_error` | Extraction template or JSON error. |
| `infer` | `<id>.attempted_tool` | Leading identifier of a failed call span. |
| `infer` | `__tool_call_step` | Id of the step that extracted at least one tool call. |
| `infer` | `<id>.perplexity`, `<id>.mean_logprob` | Mean chosen-token confidence over the whole generation (perplexity = exp(-mean logprob), 1.0 = confident). Present only when logprobs were captured. |
| `infer` | `<id>.answer_perplexity`, `<id>.answer_mean_logprob` | The same, over the answer tokens alone (reasoning excluded). |
| `infer` | `<id>.max_token_perplexity`, `<id>.answer_max_token_perplexity` | Perplexity of the single least-confident token (whole generation / answer only): the peak the mean hides in a long reasoning trace. |
| `infer` | `<id>.p95_perplexity`, `<id>.p90_perplexity`, `<id>.uncertain_token_fraction`, `<id>.logprob_stdev` | Robust peaks (95th/90th-percentile token), the share of tokens below an uncertain threshold, and the spread of confidence. |
| `infer` | `<id>.tail_perplexity`, `<id>.logprob_slope` | Confidence over the last few tokens (the conclusion) and its slope across positions. |
| `infer` | `<id>.min_margin`, `<id>.low_margin_fraction` | Smallest and share of small top-1 vs top-2 token margins: how contested the model's most-contested pick was. Written as `0.0` when the engine returned no alternatives (the step always asks for the top 3). |
| `infer` | `<id>.mean_entropy`, `<id>.max_entropy` | Mean and max per-token entropy over the top-k candidates. Written as `0.0` when the engine returned no alternatives (the step always asks for the top 3). |
| `classify` | `<out>` (`<id>.label`) | Top label. |
| `classify` | `<out>.score` | Top score. |
| `embed` | `<out>` (`<id>.embedding`) | `[f0, f1, ...]`. |
| `decide` | `<p>.<q>` (`p` = `output_field`, default `<id>`) | Per answer `q`: the chosen key (choice), the expected level (score) or the probability of yes (noul). |
| `decide` | `<p>.<q>.confidence` | Choice and score: 0 to 1. Read by `route` with `strategy: decision` and `min_confidence`. |
| `decide` | `<p>.<q>.mode` | Score: the most probable level. |
| `decide` | `<p>.<q>.probabilities` | Choice and score: JSON object of key to probability. |
| `route` | `<id>.label` | Resolved label. |
| `route` | `<id>.matched` | `true` when a rule matched. |
| `guard` | `<id>.label`, `<id>.score` | Top matched trigger and its score (4 decimals). |
| `guard` | `<id>.labels`, `<id>.scores`, `<id>.details` | All matched triggers, highest first. |
| `guard` | `<out>` (`<id>.redacted`) | `redact` only: the redacted (or untouched) text. |
| `guard`, `llm_guard`, `regex_guard` | `__guard_triggered` | Step id, on `warn`. |
| `llm_guard` | `<id>.verdict` | First line of the verdict, or `no_verdict` when the judge produced no answer text. |
| `llm_guard` | `<id>.verdict_full` | Whole verdict. |
| `loop` | `<id>.iterations` | Failed condition checks so far. |
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
| `todo` | `<id>.client_tool_calls` | Number of client-bound calls that ended the turn. |
| `todo` | `<id>.todo_error` | Execution error of a plan call. |

### Structured state touched by steps

| State | Written by | Read by |
| --- | --- | --- |
| `messages` | `infer` (`output` / `thinking` append an assistant turn), `loop` (`loopback_message`), `todo` (call and result turns), redacting guards (rewrite every matching turn in scope) | `infer` (passthrough conversation), `guard` / `regex_guard` (turns selected by `scope`), `decide` (`input_field: messages`), `tool_select` (last user turn), `sub_pipeline` (`forward_messages`), Jinja `messages` / `history` / `system` |
| `generated_messages` | `infer` | Jinja |
| `verdicts` | `guard`, `llm_guard` | Jinja |
| extracted tool calls | `infer` | `todo`, the final `COMPLETED` event, conversation storage |
| pending narration | `infer` (`role: internal`) | `todo` when halting for client calls |
| retry sampling | `loop` | `infer` |
| halt state (`haltReason`, `breakOutputField`, `haltMessage`) | `break`, `loop` (never), guards, `sub_pipeline`, `todo` | `PipelineExecutor` |
| usage and performance totals | `infer`, `sub_pipeline` | the final `COMPLETED` event |
| todo plan, plan lock, constraints | `todo`, request restore | `todo`, Jinja `todos` / `constraints`, session and conversation stores |
| decision request | a decision call targeting the pipeline | `decide` (`questions: request`) |
| decision result, decision failure | `decide` | the decision service, which answers the call with them |

## Notes

- Everything in the flat map is a string. Compare numbers as text (`match_value: "0"`) and booleans as `"true"` / `"false"`.
- Step-map nesting splits on the first dot only. `intent.label.score` becomes `intent = {label: ..., "label.score": ...}`: read it as `{{ intent['label.score'] }}` in Jinja, or through the flat key `intent.label.score` in conditions.
- `<step>.output` is overwritten on every loop iteration; `generated_messages` keeps each draft.
- `ScoreResolver` for `score_above` / `score_below`: `<input_field>.score`, then `<input_field minus last segment>.score`, then the field value itself parsed as a float, else `0`.
- A `sub_pipeline` forwards the whole flat map as the child request's `context`; nothing but `<out>` comes back.
- `--debug` (TRACE logging) prints the full context before and after every step (`PipelineContext.debugSnapshot`, logged at DEBUG) and the rendering context of each template.
- The `<id>` confidence signals are free byproducts of the one generation (the sampler already produces the log-probabilities), computed by the `ConfidenceSignal` set in `engine-api` and emitted whenever the engine returned token log-probabilities: the `infer` step always requests the top 3 per position, independent of the request's `top_logprobs`. They are raw signals, not correctness measures: a downstream step calibrates the one it wants against resolved outcomes. Which summary predicts correctness, if any, depends on the model and the task, so measure before trusting one. The same values appear on the step span as `singularitee.infer.*` (see [Observability](../operations/observability/README.md)).

## See also

- [Step types](./steps/README.md)
- [Templates](./templates/README.md)
- [Pipelines concept](../concepts/pipelines/README.md)
