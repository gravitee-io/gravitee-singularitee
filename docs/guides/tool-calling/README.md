# Tool calling

> Pass the caller's tools through a pipeline: inject their schemas into the prompt, shortlist them with a zero-shot classifier, capture the model's tool span with `tags:`, extract structured calls with a dialect template, repair malformed calls in a loop, and return `tool_calls` over HTTP and gRPC.

## Overview

Tools arrive with the request (`tools[]` on `/v1/chat/completions` and `/v1/responses`,
`InferPipelineRequest.tools` on gRPC) and are stored on the `PipelineContext`. From there:

1. **Injection.** Each `infer` step renders the tools into its prompt through the model's chat
   template (or `{{tools}}` in a raw template) unless `inject_tools: false`. A `tool_select`
   step ahead of it can replace "all tools" with a per-request shortlist.
2. **Capture.** The step's `tags.tool_open` / `tool_close` tell the engine where the model's
   tool span starts and ends. The span streams as `STEP_ROLE_TOOL`, separately from the
   answer, with the markers removed.
3. **Extraction.** `ToolCallExtractor` renders a Jinja extraction template over the span and
   parses the result into `ToolCall{name, arguments_json, coercible_args}`. The step names a
   built-in dialect with `tool_extraction_template`, supplies inline Jinja, or leaves it unset
   to try `chatml-json`, `xml-function`, `gemma-call` in order.
4. **Signals.** The outcome is published as context fields (`<step>.tool_parse_failed`,
   `<step>.parse_error`, `<step>.attempted_tool`, ...) so a `loop` can send the model back with
   a corrective turn.
5. **Delivery.** Extracted calls ride the `COMPLETED` event (`ResponseCompleted.tool_calls`,
   finish reason `TOOL_CALLS`). HTTP renders them as OpenAI `tool_calls` (Chat Completions) or
   `function_call` items (Responses), coercing string arguments to the declared JSON types
   where the dialect lost them.

Server-owned tools (`set_todos`, `complete_todo`, `ask_user`) follow the same path but are
executed by a `todo` step and never reach the client; see [Todos](../todos/README.md).

## Key types

| Type | Module | Purpose |
| --- | --- | --- |
| `ToolDefinition`, `ToolParameterDef` | `protocol` | A tool on the wire; `template` overrides the default JSON-schema rendering. |
| `TagConfig` | `protocol` | `open_tag`, `close_tag`, alternatives, `repeatable`; YAML `tags:` with `tool_open` / `tool_close` (string or list). |
| `ToolCallExtractor` | `engine` (`engine/tools`) | Template-driven extraction; built-ins under `src/main/resources/tool-extraction/`. Fail-open. |
| `ToolCallOutcomeRecorder` | `engine` | Writes the signal fields, detects marker residue, runs markerless extraction. |
| `ToolMarkerResidues` | `engine` | Per-dialect detection of leaked tool markers in the answer text. |
| `ToolSelectStepExecutor` | `engine` | Zero-shot shortlist of the caller's tools (`KEY_SELECTED_TOOLS`). |
| `PromptAssembler.injectableTools` | `engine` | Applies `inject_tools`, the shortlist, condensed descriptions and `server_tools`. |
| `ResponseCompleted.tool_calls`, `ToolCall` | `protocol` | Delivery over gRPC. |
| `ToolCallResolver`, `ChatCompletionsFormatter`, `ResponsesFormatter` | `http` | OpenAI rendering, `call_<id>` assignment, argument coercion, fail-open text. |

## Usage

### A tool-capable step

`examples/llama/qwen3-0.6b.yaml` (pipeline `agent`) is the minimal shape for a ChatML model:

```yaml
- id: agent
  type: infer
  role: output
  config:
    model_id: llm
    output_field: agent.output
    tags:
      reasoning_open: "<think>"
      reasoning_close: "</think>"
      tool_open: "<tool_call>"
      tool_close: "</tool_call>"
```

```bash
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml     # or: task run:qwen
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "agent",
  "messages": [{"role": "user", "content": "What is the weather in Paris?"}],
  "tools": [{"type": "function", "function": {"name": "get_weather",
    "description": "Current weather for a city.",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}]
}' | jq '.choices[0] | {finish_reason, tool_calls: .message.tool_calls}'
```

Expected: `finish_reason: "tool_calls"` and one call `get_weather` with
`arguments: "{\"city\":\"Paris\"}"`. Reply with a `role: tool` message carrying the
`tool_call_id` and the model answers from the result.

Over gRPC, add `tools` to `InferPipelineRequest` and read `response_completed.tool_calls`:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"What is the weather in Paris?",
       "tools":[{"name":"get_weather","description":"Current weather for a city.",
                 "parameters":[{"name":"city","type":"string","required":true}]}]}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

### Dialects

The span a model emits differs per family. Pick the tags and the template that match the
model; the examples carry a working pair for each:

| Model family | `tags.tool_open` / `tool_close` | `tool_extraction_template` | Example |
| --- | --- | --- | --- |
| Qwen3, Qwen3.6 (ChatML JSON or XML) | `<tool_call>` / `</tool_call>` | unset (`chatml-json`, then `xml-function`) | `examples/llama/qwen3-0.6b.yaml`, `qwen3.6-35b.yaml` |
| Gemma | `<\|tool_call>` / `<tool_call\|>` | `gemma-call` | `examples/llama/gemma4-12b.yaml` |
| Mistral | `[TOOL_CALLS]` / `</s>` | unset | `examples/llama/mistral-7b.yaml` |
| GLM-4 (markerless) | none | `glm-name-json` | `examples/llama/glm-4-9b.yaml` |
| gpt-oss (Harmony) | channel headers, lists of alternatives | `harmony` | `examples/llama/gpt-oss-20b.yaml` |

Built-in templates receive `output` (the span) and `tools` (the request's tools as data) and
must render a JSON array of `{"name", "arguments"}` objects. `glm-name-json` and `harmony`
are never tried speculatively: their spans carry no wrapper, so they validate the leading
name against `tools` and must be named explicitly. An optional `"coerce"` member (`true` or a
list of argument names) marks string values recovered from untyped text; the HTTP layer
converts them to the declared JSON-schema types.

Inline Jinja works in the same key:

```yaml
tool_extraction_template: |
  {%- set blocks = output | regex_findall('(?s)<call>(.*?)</call>') -%}
  [{{ blocks | join(',') }}]
```

Tag sets can be named once under `workspace.tags:` and referenced by id from a step
(`tags: harmony-gpt-oss`); see [Templates](../../reference/templates/README.md).

### Shortlisting with `tool_select`

`examples/pipelines/tool-router.yaml` (pipeline `tool-router`) classifies the last user
message against the tools on the request with a GLiNER zero-shot model and injects only the
matches:

```yaml
- id: select_tools
  type: tool_select
  next_step: agent
  config:
    model_id: tool-router        # a gliner_classifier model, labels supplied per request
    batch_size: 4
    threshold: 0.3
    trim_descriptions: true      # inject a condensed description for the selected tools
    # always_include: [read_file]
```

Tools are scored in batches of `batch_size`, each with a synthetic `none_of_these` label; a
tool is kept when it clears `threshold` and outscores `none_of_these`. All batches electing
`none_of_these` leaves the shortlist empty and the next infer step injects no tools
(`always_include` applies only to a non-empty shortlist). A failed classify call fails open
for its batch.

```bash
./run-server.sh --workspace examples/pipelines/tool-router.yaml   # or: task run:tool-router
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model":"tool-router","messages":[{"role":"user","content":"What is the weather in Paris?"}],
  "tools":[{"type":"function","function":{"name":"get_weather","description":"Get the current weather for a city."}},
           {"type":"function","function":{"name":"send_email","description":"Send an email to a recipient."}},
           {"type":"function","function":{"name":"run_sql","description":"Run a SQL query against the analytics warehouse."}}]}'
```

### Repairing malformed calls

Extraction never fails a step; it publishes what happened:

| Field | Value |
| --- | --- |
| `<step>.tool_parse_failed` | `true` only when the model attempted a call (a span was captured, the engine finished with `tool_calls`, or tool markers leaked into the answer) and no call was extracted. `false` for prose. |
| `<step>.tool_parse_ok`, `<step>.tool_call_count` | Extraction result. |
| `<step>.parse_error` | Template or JSON error text, when one was thrown. |
| `<step>.attempted_tool` | The leading identifier of the span, minus a `functions.` prefix. |
| `<step>.finish_reason` | `tool_calls` when calls were extracted. |

`examples/pipelines/tool-repair.yaml` (gpt-oss) and `tool-repair-escalate.yaml` (Qwen3-0.6B,
then gpt-oss-20b) gate a loop on `tool_parse_failed`:

```yaml
- id: repair_gate
  type: loop
  next_step: done
  config:
    loopback_step: generate
    fallback_step: escalate          # a plain-text answer, or a bigger model
    max_iterations: 2
    condition: { type: equals, input_field: generate.tool_parse_failed, match_value: "false" }
    loopback_message:
      role: user
      content: "Your previous reply did not contain a valid tool call.{% if generate.attempted_tool %} You called '{{ generate.attempted_tool }}', which is not a declared tool.{% endif %}{% if generate.parse_error %} Parse error: {{ generate.parse_error }}.{% endif %} The ONLY valid tools are: {{ tool_names | join(', ') }}. Reply again with a single valid tool call."
    retry_sampling_params: { temperature: 0.2 }
```

`tool_names` lists the request's declared tools in every template. Gate on
`tool_parse_failed`, not `tool_parse_ok`: a plain answer (including the turn after a tool
result) must exit the gate. A truncated closing brace is repaired before any loop runs: the
extractor balances `{` / `[` and re-renders once.

### What the client receives

| Surface | Rendering |
| --- | --- |
| gRPC | `COMPLETED` with `finish_reason: FINISH_REASON_TOOL_CALLS` and `tool_calls[]`; the span also streamed as `STEP_ROLE_TOOL` deltas. |
| Chat Completions | `message.tool_calls[]` with `id: call_<hex>`, `type: function`, `function.{name, arguments}`; `finish_reason: tool_calls`; `content` holds the model's narration or `null`. Reasoning streams as `reasoning_content`. |
| Responses | A `function_call` output item per call after any reasoning and message items. |
| Extraction failed | `finish_reason: stop` and the raw span appended to `content`, so nothing is silently dropped. |

## Options

Keys this guide uses. Full tables: [`infer`](../../reference/steps/infer.md),
[`tool_select`](../../reference/steps/tool_select.md), [`loop`](../../reference/steps/loop.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `infer.inject_tools` | bool | `true` | Render the caller's tools into this step's prompt. |
| `infer.server_tools` | bool | `true` | Also inject the server tools of a todo pipeline (proto `expose_server_tools`). |
| `infer.tags.tool_open`, `tool_close` | string or list | unset | Tool span markers; lists give alternatives, longest match wins. |
| `infer.tool_extraction_template` | built-in name or Jinja | unset (built-ins in order) | `chatml-json`, `xml-function`, `gemma-call`, `glm-name-json`, `harmony`, or inline source. |
| `tool_select.model_id` | string | required | A `gliner_classifier` model. |
| `tool_select.input_field` | string | last user message | Text to classify. |
| `tool_select.batch_size`, `threshold` | int, float | `4`, `0.3` | Batching and minimum score. |
| `tool_select.always_include` | list | unset | Names unioned into a non-empty shortlist. |
| `tool_select.trim_descriptions`, `label_template`, `description_template` | bool, Jinja, Jinja | `false`, built-in condenser | Description condensation for labels and injection. |

## Notes

- A step with no `tags.tool_open` and no explicit extraction template never produces tool
  calls; the model's text is the answer. Markerless dialects need the explicit template, and
  the request (or a todo pipeline) must declare tools.
- Tool names and descriptions are caller-supplied and rendered into the prompt; the engine
  escapes the model's special tokens inside them.
- Leaked tool markers in the answer of a marker-based step count as a failed attempt
  (`parse_error: unrecognized tool-call markers in output`), so a repair loop retries instead
  of the client seeing raw syntax.
- A raw model id is not the pipeline: calling the bare model returns the unprocessed stream,
  markers included. Use the pipeline id for client traffic.
- Prior tool calls are re-wrapped in the step's tags when appended to the conversation, so the
  chat template re-renders them correctly on later turns; `ToolCall.id` pairs a tool result
  with its call.
- `strip_thinking` and the reasoning tags are independent of the tool tags; Harmony needs
  both sets with alternatives because one generation chains several channels.

## See also

- [Loops and chain-of-thought](../loops-and-cot/README.md): the gate mechanics.
- [Todos](../todos/README.md): server-executed tools and progress events.
- [Text generation](../text-generation/README.md): the `infer` step, how markers are matched.
- [Templates](../../reference/templates/README.md): named tag sets, chat and extraction templates.
- [HTTP API](../../api/http/README.md) and [gRPC API](../../api/grpc/README.md): request and response shapes.
- [Context fields](../../reference/context-fields.md): every signal field.
