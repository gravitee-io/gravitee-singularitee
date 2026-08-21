# `infer`

> Streams a generation from a text-generation model, writes the text to the context, and records tool-call and reasoning diagnostics for the steps that follow.

## Overview

`InferStepExecutor` binds to a `TextGenEngine` (`llama_cpp`, `vllm` or `remote_llm`). `PromptAssembler` builds the prompt: the step's `prompt.messages`, or the caller's conversation when none are declared, or a `prompt.template*` raw template that bypasses the chat template. `TextGenRequestFactory` resolves sampling across request, loop-retry and step values, and `TokenCaptureStream` streams the tokens to the client according to the step `role` while accumulating the full text. After generation `ToolCallOutcomeRecorder` runs the tool-extraction template and publishes the tri-state `tool_parse_*` fields.

## Usage

A templated step with explicit reasoning and tool tags (`examples/pipelines/tool-router.yaml`, `examples/pipelines/guard.yaml`):

```yaml
- id: generate
  type: infer
  role: output
  config:
    model_id: llm
    output_field: generate.output
    prompt:
      messages:
        - role: system
          content: "Answer the user. Some values may appear masked; treat them as redacted and never try to guess them."
        - role: user
          content: "{{pii_guard.output}}"
    sampling:
      max_tokens: 512
      temperature: 0.8
    tags:
      reasoning_open: "<think>"
      reasoning_close: "</think>"
      tool_open: "<tool_call>"
      tool_close: "</tool_call>"
    context:
      enable_thinking: false
```

A step that keeps the caller's conversation and only adds its own instructions (`examples/pipelines/todo-agent.yaml`):

```yaml
- id: summarize
  type: infer
  role: output
  config:
    model_id: llm
    output_field: summarize.output
    inject_tools: false
    server_tools: false
    context: { reasoning_effort: low }
    tags: harmony            # a workspace-level `tags:` entry, by id
    system: "Every plan item is done. The results are recorded below:\n{% for t in todos %}### {{ t.title }}\n{{ t.proof }}\n{% endfor %}\nAssemble the final answer from these results."
```

## Options

### Top level

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Logical id of a published text-generation model. |
| `output_field` | string | `<id>.output` | Context key receiving the full generated text (reasoning included unless `strip_thinking`). |
| `prompt` | object | unset | Prompt source, see below. Unset: the caller's messages pass through as-is, or the bare `prompt` becomes a single user turn. |
| `sampling` | object | unset | Step-level sampling, see below. |
| `tags` | object or string | unset | Reasoning and tool-call markers, see below. A bare string references a workspace `tags:` entry by id. |
| `context` | map | unset | Typed Jinja variables (`enable_thinking: false`, `reasoning_effort: medium`, lists, maps). Merged into the rendering context and forwarded as `template_context` when the engine renders the messages itself. |
| `system` | string | unset | Jinja template prepended as a system turn. When the request already has a system message the two are merged into that turn (caller first, blank line, step text). Proto field `system_prompt`. |
| `inject_tools` | bool | `true` | `false` hides the caller's tools: `{{ tools }}` is undefined in raw templates and empty for chat templates. |
| `server_tools` | bool | `true` | `false` hides the server-owned todo tools (`set_todos`, `complete_todo`, `ask_user`) from this step. Proto field `expose_server_tools`. |
| `strip_thinking` | bool | `false` | `true` removes the span between the reasoning tags from the stream, the step output and the conversation. `false` routes it to the THINKING stream and keeps it in `output_field`. |
| `stream_thinking` | bool | `false` | For `role: internal` only: forward THINKING deltas to the client while content and tool deltas stay suppressed. |
| `trim_history` | bool | `true` | Drop older turns so prompt plus `max_tokens` fits the engine's context window (`ChatWindowTrimmer`). Leading system turns are pinned, an assistant tool call and its tool results trim as one unit, the newest turn is always kept (head-truncated with `[...trimmed]` when it alone overflows). No effect when the engine reports no context size. |
| `tool_extraction_template` | string | unset | A built-in name (`chatml-json`, `xml-function`, `gemma-call`, `glm-name-json`, `harmony`) or inline Jinja that renders a JSON array of `{"name","arguments"}` from `{{ output }}` and `{{ tools }}`. Unset: `chatml-json`, `xml-function` and `gemma-call` are tried in that order. |
| `chat_template` | string | unset | A workspace `templates:` id or inline Jinja that replaces the model's own chat template for this step. An invalid override fails the step with an error naming it. |

### `prompt`

Exactly one of the three template keys may be set; setting two fails the workspace load. A template takes precedence over `messages`.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `prompt.messages` | list of `{role, content}` | unset | Replaces the conversation. Each `content` is a Jinja template; `role` defaults to `user`. The result still goes through the chat template. |
| `prompt.template` | string | unset | Inline raw Jinja template. The rendered string is sent as a bare prompt, bypassing the chat template. Proto field `raw_template`. |
| `prompt.template_file` | string | unset | Path to a raw template file. Resolved relative to `${gravitee.home}/templates` on a running server (the workspace directory when the loader is given no templates path). Must stay under that base unless absolute. |
| `prompt.template_id` | string | unset | Id of a `templates:` entry; resolved to its content at load time. Unknown ids fail the load. |

### `sampling`

Step values apply only when non-zero. Precedence at request time: request `sampling_params` > a loop's `retry_sampling_params` > the step's `sampling`.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `sampling.max_tokens` | int | engine default | Completion cap; also the reservation `trim_history` keeps free. |
| `sampling.temperature` | float | engine default | Sampling temperature. |
| `sampling.top_p` | float | engine default | Nucleus threshold. |
| `sampling.presence_penalty` | float | `0` | Presence penalty. |
| `sampling.frequency_penalty` | float | `0` | Frequency penalty. |
| `sampling.stop` | list of string | unset | Stop strings; mapped to `InferStepConfig.stop`. |

`SamplingParams.seed` and `top_logprobs` exist on the wire and can be set per request, but have no YAML key on the step. `LoraConfig` (`lora_name`, `lora_path`) is likewise a proto-only field of `InferStepConfig`: the workspace loader never sets it, so adapters are selected per request or in the model's `llama_cpp.lora_path`.

### `tags`

Each marker key accepts a string or a list; the first entry is the primary marker, the rest are alternatives matched alongside it (longest match wins).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `tags.reasoning_open` | string or list | unset (`<think>` for `strip_thinking`) | Marker(s) opening the reasoning channel. |
| `tags.reasoning_close` | string or list | unset (`</think>` for `strip_thinking`) | Marker(s) closing it. |
| `tags.reasoning_repeatable` | bool | engine default (once per generation) | Whether the reasoning channel may reopen within one generation. Needed for chained-channel dialects such as Harmony. |
| `tags.tool_open` | string or list | unset | Marker(s) opening the tool-call channel. A generation ending inside it finishes with `tool_calls`. Also the marker `trim_history` uses to keep a call with its results. |
| `tags.tool_close` | string or list | unset | Marker(s) closing it. |
| `tags` as a string | string | unset | Reference to a `workspace.tags[].id`; unknown ids fail the load. |

## Context fields

Reads: `prompt`, the conversation (`messages`), the request tools, `reasoning_effort` and `instructions` (request context), `__selected_tools` and `__condensed_tool_descriptions` (from `tool_select`), the server tool list (when a `todo` step exists), the request and retry sampling overrides, and every field referenced by its templates.

Writes:

| Field | Value |
| --- | --- |
| `<output_field>` | The full generated text. A bare tool payload captured by the engine is re-wrapped in the step's tool tags (default `<tool_call>...</tool_call>`). |
| `<id>.finish_reason` | `stop`, `length`, `tool_calls`, `guard_blocked`, `break_condition`, `max_iterations`, `cancelled`, `stalled` or `unspecified`. |
| `<id>.prompt_tokens`, `<id>.completion_tokens`, `<id>.reasoning_tokens` | Usage reported by the engine. |
| `<id>.prompt_ms` | Prompt evaluation time in milliseconds. |
| `<id>.thinking_unclosed` | `true` when the generation ended inside an unclosed reasoning span, or produced engine-classified reasoning with no answer and no tool span. |
| `<id>.tool_parse_failed` | `true` only when a call was attempted (tool span or `tool_calls` finish, or leaked tool markers) and nothing could be extracted. `false` for plain prose. |
| `<id>.tool_parse_ok` | `true` when at least one call was extracted. |
| `<id>.tool_call_count` | Number of extracted calls. |
| `<id>.parse_error` | The extraction template or JSON error, when one occurred. |
| `<id>.attempted_tool` | The leading identifier of the failed span (`functions.` prefix removed), so a repair loop can name the wrong tool. |

Also appends the text to `generated_messages`, appends an assistant turn to `messages` for `output` and `thinking` roles, stores the extracted tool calls for the final event, and accumulates usage and performance into the pipeline totals.

## Notes

- Unset `prompt` is the usual agent shape: the caller's whole conversation, tool calls and tool results included, is rendered through the chat template. `prompt.messages` replaces that conversation entirely; use `system` to steer without losing it.
- The chat-template rendering context carries `messages`, `tools` (OpenAI `{type: function, function: {...}}` maps), `add_generation_prompt: true`, `bos_token`, `eos_token`, plus everything listed in [Context fields](../context-fields.md). Special-token strings in message text, tool names and arguments are escaped before rendering so they cannot forge conversation structure.
- A model with no chat template (remote metadata not yet fetched) never falls back to `role: content` concatenation: the structured messages are forwarded for engine-side rendering with `context` as `template_context`.
- `context` booleans must be unquoted. A string `"false"` fails `is false` tests in chat templates and the loader logs a warning.
- Markerless dialects (`glm-name-json`) only extract when `tool_extraction_template` is set explicitly and no `tool_open` is configured; a marker-based dialect with no span is treated as "no call".
- `role: internal` steps keep their visible answer as narration; a later `todo` step streams it next to client-bound tool calls.

## See also

- [Text generation guide](../../guides/text-generation/README.md)
- [Tool calling guide](../../guides/tool-calling/README.md)
- [Templates](../templates/README.md)
- [Context fields](../context-fields.md)
- [`loop`](./loop.md) for repair loops driven by the diagnostic fields
