# Structured output

> Constrain decoding so the text of a generation always matches a format: a JSON Schema, any JSON object, one of a list of strings, a regular expression or a GBNF grammar, sent by the caller with the request, over HTTP or gRPC.

## Overview

Structured output is constrained decoding: tokens that would break the format are never
sampled, so the generated text matches it by construction. Five formats exist:

| Format | The text is |
| --- | --- |
| `json_schema` | A JSON document valid against a JSON Schema. |
| `json_object` | Any syntactically valid JSON object. |
| `choice` | Exactly one of the listed strings. |
| `regex` | A string the regular expression matches in full. |
| `grammar` | A derivation of a GBNF grammar. |

The format travels with the request; nothing is declared in the workspace:

1. **gRPC.** `StructuredOutputFormat structured_output` on `InferRequest` and
   `InferPipelineRequest`. All five formats.
2. **HTTP, OpenAI-compatible.** `response_format` on `/v1/chat/completions` and `text.format` on
   `/v1/responses`. Types `text`, `json_object` and `json_schema` only; `choice`, `regex` and
   `grammar` are gRPC only.

**Scope.** On a pipeline, the format applies to the `role: output` step only. Other steps
(guards, routers, internal steps) never inherit it. On a bare model id it applies directly.

**Engines.**

| Format | `llama_cpp` | `vllm` |
| --- | --- | --- |
| `json_schema` | Compiled to a GBNF grammar by the server. | Native. |
| `json_object` | Built-in JSON grammar. | Native. |
| `choice` | Compiled to GBNF. | Native. |
| `grammar` | Native GBNF. | Native (GBNF-style EBNF; the start rule must be named `root`). |
| `regex` | Not supported; the request is refused. | Native. |

`remote_llm` forwards the format to the remote server. The vLLM path is untested here.

## Key types

- `StructuredOutput` (`inference-api`): the sealed engine-side model (`JsonSchema`, `JsonObject`, `Choice`, `Regex`, `Grammar`); `UnsupportedStructuredOutputException` is what an engine raises for a format it cannot enforce.
- `StructuredOutputFormat` (`inference.proto`): the wire message; `StructuredOutputs` (`engine-api`) converts it.
- `TextGenRequestFactory` (`plugin-infer`): hands the caller's format to the `role: output` step only.
- `TextGenEngine.checkStructuredOutput`: lets a front refuse a request before it is queued.
- `GbnfCompiler`, `JsonSchemaToGbnf`, `GbnfValidator` (`inference-llama-cpp`): compile and validate the llama.cpp grammar.
- `ResponseFormatParser`, `ModelOrPipelineResolver` (`http`): translate `response_format` / `text.format` and refuse what the target cannot honour.

## Usage

The snippets run against `examples/llama/qwen3-0.6b-structured.yaml`:

```bash
./run-server.sh --workspace examples/llama/qwen3-0.6b-structured.yaml
```

It publishes one model (`llm`) and one pipeline (`agent`); both answer in free text until the
caller sends a format.

### Chat Completions

```bash
curl -s localhost:8080/v1/chat/completions -H 'Content-Type: application/json' -d '{
  "model": "agent",
  "messages": [{"role": "user", "content": "Invent a fantasy character."}],
  "response_format": {"type": "json_schema", "json_schema": {"name": "character",
    "schema": {"type": "object", "required": ["name", "level"],
      "properties": {"name": {"type": "string"}, "level": {"type": "integer"}}}}}
}' | jq -r '.choices[0].message.content'
```

`response_format` is `{"type":"text"}` (no constraint), `{"type":"json_object"}`, or
`{"type":"json_schema","json_schema":{"name","strict","schema"}}`. `name` is required. `strict`
is accepted and changes nothing: decoding enforces the schema either way.

### Responses

Same three types under `text.format`, with `name`, `schema` and `strict` flattened on the format
object (no nested `json_schema`):

```bash
curl -s localhost:8080/v1/responses -H 'Content-Type: application/json' -d '{
  "model": "agent",
  "input": "Invent a fantasy character.",
  "text": {"format": {"type": "json_schema", "name": "character",
    "schema": {"type": "object", "required": ["name", "level"],
      "properties": {"name": {"type": "string"}, "level": {"type": "integer"}}}}}
}' | jq
```

### OpenAI SDK

The SDK parsing helpers work, streaming included:

```python
from openai import OpenAI
from pydantic import BaseModel

class Character(BaseModel):
    name: str
    level: int

client = OpenAI(base_url="http://localhost:8080/v1", api_key="unused")

chat = client.chat.completions.parse(
    model="agent",
    messages=[{"role": "user", "content": "Invent a fantasy character."}],
    response_format=Character,
)
print(chat.choices[0].message.parsed)

response = client.responses.parse(
    model="agent",
    input="Invent a fantasy character.",
    text_format=Character,
)
print(response.output_parsed)
```

### gRPC

```bash
grpcurl -plaintext -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"Is water wet? Answer yes or no.","structured_output":{"choice":{"values":["yes","no"]}}}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/InferPipeline
```

The other kinds: `{"json_schema":"<schema as a JSON string>"}`, `{"json_object":true}`,
`{"regex":"..."}`, `{"grammar":{"text":"root ::= ...","root":"root"}}`.

### JSON Schema subset on llama.cpp

`JsonSchemaToGbnf` compiles the schema into a grammar whose every derivation validates
against it.

| | Keywords |
| --- | --- |
| Enforced | `type` (single or list), `properties` / `required`, `items` with `minItems` / `maxItems`, `minLength` / `maxLength`, `enum`, `const`, `anyOf`, `oneOf`, single-entry `allOf`, local `$ref` (`#/$defs/...`, `#/definitions/...`), recursion included. |
| Ignored (annotations) | `title`, `description`, `default`, `examples`, `format`, `$schema`, `$id`, `$defs`, `definitions`, `deprecated`, `readOnly`, `writeOnly`, `$comment`, `additionalProperties`. |
| Refused | Any other keyword that restricts values: `pattern`, `minimum`, `maximum`, `multipleOf`, `patternProperties`, tuple `items`, multi-entry `allOf`, remote `$ref`, and so on. The request fails rather than the keyword being silently ignored. |

Objects are emitted closed: declared properties only, in declared order, required ones first
then optional ones. This also satisfies schemas that allow extra properties. Whitespace between
JSON tokens is bounded, and nothing can follow the finished value except end of text.

### Errors

**HTTP.** `400` `invalid_request_error`, raised before anything is queued. `param` is
`response_format` (chat), `response_format.json_schema` (missing `name` or `schema`) or
`text.format` (responses).

| Case |
| --- |
| Malformed field, or an unknown `type`. |
| A schema keyword that cannot be enforced. |
| A format the target engine cannot enforce. |
| A pipeline with no text-generation output step. |
| `response_format` combined with `tools`. |

**gRPC.** The stream ends with a `RESPONSE_EVENT_TYPE_FAILED` event whose `error_code` is
`invalid_request_error` (instead of `server_error`) and a message such as
`regex structured output is not supported by the llama.cpp engine; use a grammar` or
`grammar: undefined rules [missing]`.

**Truncation.** A generation cut by `max_tokens` returns truncated text with
`finish_reason: length`. There is no dedicated finish reason; size `max_tokens` for the
expected document.

## Options

`StructuredOutputFormat` (gRPC), exactly one kind:

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `json_schema` | string | unset | A JSON Schema document, serialized as JSON. |
| `json_object` | bool | unset | `true`: any syntactically valid JSON object. |
| `choice.values` | list of string | unset | The allowed answers. |
| `regex` | string | unset | A regular expression the whole text must match (vLLM only). |
| `grammar.text` | string | unset | GBNF grammar text. |
| `grammar.root` | string | `root` | Start rule of the grammar. |
| `name` | string | unset | Schema name (OpenAI `json_schema.name`). Informational. |

Over HTTP, `response_format` and `text.format` are described under Usage.

## Notes

- The format applies from the first token, so reasoning must be off on the step that receives it
  (`context: { enable_thinking: false }`). Otherwise the reasoning block would have to match
  the format too.
- Not combinable with tools: a format applied from the first token would make every tool call
  impossible.
- Not available on a llama.cpp model loaded with speculative decoding: the per-request sampler
  is bypassed there, and the request is refused.
- `json_object` only guarantees syntax. The prompt must still ask for JSON and say which fields.
- With a schema, the prompt should still describe the wanted fields: the constraint guarantees
  shape, not quality.
- On vLLM a grammar's start rule must be named `root`.

## See also

- [`infer`](../../reference/steps/infer.md)
- [HTTP API](../../api/http/README.md)
- [gRPC API](../../api/grpc/README.md)
- [text-generation.openapi.yaml](../../../openapi/text-generation.openapi.yaml)
- [Text generation](../text-generation/README.md)
- [Tool calling](../tool-calling/README.md)
