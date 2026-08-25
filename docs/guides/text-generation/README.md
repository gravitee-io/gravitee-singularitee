# Text Generation

> How to stream tokens from a published LLM: call the `Infer` RPC or `/v1/chat/completions` directly, or wrap the model in an `infer` pipeline step to control the prompt, sampling and reasoning output.

## Overview

Every text-generation call ends in a `TextGenEngine` bound to a model id (`llama_cpp` or `vllm`, or a `remote_llm` proxy). You reach it three ways:

| Path | When to use it |
| --- | --- |
| `GraviteeInferenceService.Infer` (gRPC) or `POST /v1/chat/completions` with the model id | Raw access: the caller's messages are rendered through the model's chat template and the token stream comes back unprocessed. |
| `InferPipeline` (gRPC) or `/v1/chat/completions` with a pipeline id | The `infer` step adds what clients expect: reasoning split from the answer, tool-call extraction, a default system prompt, sampling defaults. |
| Java: `SingulariteeClient.infer` / `inferPipeline` | Same two RPCs from a JVM. |

Use the pipeline id for client-facing traffic. The bare model id returns dialect markers (for example Harmony channel headers) inside `content`.

The stream is `CREATED`, then `OUTPUT_TEXT_DELTA` events, then `COMPLETED` (usage, performance, `finish_reason`) or `FAILED`. Each delta carries a `step_role`: `OUTPUT` for the answer, `THINKING` for reasoning, `TOOL` for a tool-call span.

## Key types

- `TextGenEngine` (inference-api): `rxAddSequence`, `rxStream`, `chatTemplateString`, `contextSize`.
- `TextGenRequest`: `prompt` or `messages`, sampling fields, `stop`, `reasoningTags` / `toolCallTags`, `templateContext`, `lora`.
- `InferStepExecutor` (engine): renders the step prompt with Jinja, streams through `TokenCaptureStream`, writes `{step_id}.output`, appends the assistant turn, accumulates usage.
- `Jinja4jChatTemplateRenderer`: renders messages through the model's own chat template; `JinjaRenderer`: renders step-level templates against the pipeline context.
- Protos: `InferRequest`, `InferPipelineRequest`, `InferResponse`, `SamplingParams`, `TagConfig` (`inference.proto`). The step's own config is `InferStepConfig`, a record in the infer plugin.

## Usage

### Start a server

```bash
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml
```

This publishes the model `llm` and the pipeline `agent`. Enable the HTTP API with `GRAVITEE_HTTP_ENABLED=true` (gRPC is always on).

### Call it

HTTP, through the pipeline:

```bash
curl -s localhost:8080/v1/chat/completions -H 'content-type: application/json' -d '{
  "model": "agent",
  "messages": [{"role": "user", "content": "What is the capital of France?"}],
  "max_tokens": 128,
  "stream": false
}' | jq
```

gRPC, directly against the model:

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{
    "model_id": "llm",
    "messages": {"messages": [
      {"role": "ROLE_SYSTEM", "content": "You are terse."},
      {"role": "ROLE_USER",   "content": "What is the capital of France?"}
    ]},
    "sampling_params": {"max_tokens": 128, "temperature": 0.7}
  }' localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/Infer
```

Java:

```java
var client = new SingulariteeClient("localhost", 9090);
var request = InferPipelineRequest.newBuilder()
  .setPipelineId("agent")
  .setMessages(ChatMessageList.newBuilder()
    .addMessages(ChatMessage.newBuilder().setRole(Role.ROLE_USER).setContent("Hello")))
  .setSamplingParams(SamplingParams.newBuilder().setMaxTokens(128))
  .build();
client.inferPipeline(request)
  .filter(r -> r.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
  .map(r -> r.getResponseOutputTextDelta().getDelta())
  .blockingForEach(System.out::print);
```

### Write the `infer` step

The smallest pipeline passes the caller's messages through untouched (`examples/modular/pipelines/infer.yaml`):

```yaml
pipelines:
  - id: infer-pipeline
    entry: infer
    steps:
      - id: infer
        type: infer
        role: output
        config:
          model_id: llm
          output_field: infer.output
          sampling:
            max_tokens: 512
```

### Separate reasoning from the answer

Declare the model's reasoning markers. The span is routed to the `THINKING` stream and surfaces as `reasoning_content` on the HTTP API; the answer stays in `content` (`examples/modular/pipelines/reasoning.yaml`):

```yaml
config:
  model_id: llm
  tags:
    reasoning_open: "<think>"
    reasoning_close: "</think>"
```

Add `strip_thinking: true` to drop the reasoning instead of streaming it. To stop the model from reasoning at all, pass the chat-template variable instead: `context: { enable_thinking: false }`. The two are independent: one controls whether the model is asked to think, the other whether the thinking is shown.

Dialects with several markers list them (`examples/llama/gpt-oss-20b.yaml`), and a marker set used by several steps is declared once under `workspace.tags:` and referenced by id: `tags: harmony`.

### Control the prompt

Override the messages with Jinja templates over the pipeline context, or set a default system prompt that applies only when the caller sends none:

```yaml
config:
  model_id: llm
  system: "You are a support assistant for Acme."
  prompt:
    messages:
      - role: system
        content: "Answer using only: {{retrieve.output}}"
      - role: user
        content: "{{prompt}}"
```

To bypass the chat template entirely (you then own every special token), give a raw template with `prompt.template`, `prompt.template_file` or `prompt.template_id` (one of the three). Context fields available to templates are listed in [Context fields](../../reference/context-fields.md).

### Override sampling per request

Request-level `sampling_params` (gRPC) or `max_tokens` / `temperature` / `top_p` (HTTP) win over the step's `sampling` block field by field; a zero value keeps the step value. On a pipeline they apply to the first infer step reached.

## Options

Keys used in this guide. The full list is in the [infer step reference](../../reference/steps/infer.md).

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `model_id` | string | required | Id of a published text-generation model. |
| `output_field` | string | `<step_id>.output` | Context key receiving the generated text. |
| `prompt.messages` | list | caller's messages | Jinja-templated message list. |
| `prompt.template` / `template_file` / `template_id` | string | unset | Raw Jinja prompt; bypasses the chat template. |
| `system` | string | unset | Default system prompt, used only when the request has no system message. |
| `sampling.max_tokens` / `temperature` / `top_p` / `stop` | mixed | engine defaults | Sampling and stop strings. |
| `tags` | map or id | unset | `reasoning_open` / `reasoning_close` / `tool_open` / `tool_close` (string or list), `reasoning_repeatable`. |
| `strip_thinking` | bool | `false` | Remove the reasoning span instead of streaming it as `THINKING`. |
| `context` | map | empty | Extra chat-template variables (`enable_thinking`, `reasoning_effort`). |
| `role` (step level) | string | `output` | `output` streams; `thinking` streams tagged as reasoning; `internal` streams nothing and is not added to the conversation. |

## Notes

- `finish_reason` is `stop`, `length` or `tool_calls`. Hitting `max_tokens` or the context window yields `length`; a generation that ends inside the tool channel yields `tool_calls`. Guard rejections are `FINISH_REASON_GUARD_BLOCKED` on gRPC and `content_filter` on HTTP.
- Markers are matched at the start of a run, longest first; a variant the model emits but you did not configure leaks into the output as text. Configure every form the model emits. By default a reasoning channel opens once per generation; set `reasoning_repeatable: true` for dialects that re-enter it.
- A pre-rendered `prompt` on `InferRequest` is never re-templated, so `template_context` is ignored with it.
- `internal` steps (judges, routers) do not append to the conversation; `output` and `thinking` steps do. Per-pipeline usage in `COMPLETED` sums every infer step.
- `./run-server.sh --debug` logs the prompt after chat-template rendering, which is the fastest way to see what the model received.
- Budget-aware stopping (`eog_ramp_start` / `eog_ramp_max_bias`) and vLLM-specific options are model-level settings: see the [llama_cpp](../../reference/models/llama_cpp.md) and [vllm](../../reference/models/vllm.md) references.

## See also

- [Pipelines](../../concepts/pipelines/README.md): the DAG the `infer` step runs in.
- [infer step reference](../../reference/steps/infer.md) and [Context fields](../../reference/context-fields.md).
- [Templates](../../reference/templates/README.md): workspace `templates:` and `template_id`.
- [Multimodal](../multimodal/README.md): attaching images and audio to messages.
- [Loops and chain-of-thought](../loops-and-cot/README.md): multi-step generation.
- [HTTP API](../../api/http/README.md), [gRPC API](../../api/grpc/README.md), [Java client](../../api/java-client/README.md).
- [OpenAPI: text generation](../../../openapi/text-generation.openapi.yaml).
