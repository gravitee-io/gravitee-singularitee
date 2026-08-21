# HTTP API

> The opt-in OpenAI-compatible HTTP/JSON front (`gravitee-singularitee-http`, port 8080) over the same models and pipelines as the gRPC API.

## Overview
`gravitee-singularitee-http` serves the OpenAI wire format so the OpenAI SDKs and any
OpenAI-compatible client work against Singularitee directly. It is a second Vert.x server next
to the gRPC one, with its own port, TLS and auth (`http.*`), disabled by default. The layer is a
translator: it validates the payload against a per-endpoint JSON schema, resolves `model`, builds
the proto request, drives the local `GraviteeInferenceServiceImpl` or `GraviteeVectorServiceImpl`,
and renders the result back as OpenAI JSON or SSE. Metrics, tracing and cancel-on-disconnect are
inherited from the core.

The machine-readable schemas live in [openapi/](../../../openapi/README.md), one spec per API
type. This page covers the conventions and points each endpoint at its spec.

## Key types
- `HttpApiServerComponent` (`standalone-container`): reads `http.*`, builds the router (body handler, `/health`, readiness gate, auth, routes, error handlers).
- `OpenAiRoutes`: mounts every route bare and under `/v1`.
- `ModelOrPipelineResolver`: resolves `model` to an `InferRequest` or an `InferPipelineRequest`.
- `HandlerSupport`: parsing, schema validation, visibility check, modality check, error mapping.
- `ChatCompletionsHandler`, `CompletionsHandler`, `ResponsesHandler`, `EmbeddingsHandler`, `ClassifyHandler`, `RerankHandler`, `SimilarityHandler`, `ModelsHandler`.
- `InferRequestBuilder`, `PipelineRequestBuilder`: JSON to proto.
- `InferenceResponseFormatter` with `ChatCompletionsFormatter`, `LegacyCompletionsFormatter`, `ResponsesFormatter`: token stream to OpenAI JSON and SSE.
- `VertxSseWriter`: backpressured SSE writer.
- `BearerTokenAuthHandler`, `OpenAiError`, `JsonResponses`.
- `PayloadValidator` and `llm-schemas.json`: the request schemas.

## Usage

### Enabling

```yaml
http:
  enabled: true
  port: 8080
  host: 0.0.0.0
  expose-pipelines: true
  auth:
    enabled: true
    type: bearer
    tokens:
      - sk-local-changeme
```

The OpenAI SDKs need only a base URL and key:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="sk-local-changeme")
```

### Conventions

**Routes.** Every route is served under `/v1` and bare: `/v1/chat/completions` and
`/chat/completions` are the same handler. `/health` has no `/v1` form.

**Model resolution.** The `model` field resolves in this order:

1. `pipeline:<id>`: the pipeline registry only.
2. A visible model whose engine is a text-generation engine.
3. A visible pipeline with that id.
4. Otherwise `400` with `code: model_not_found`.

`/v1/embeddings`, `/v1/classify`, `/v1/rerank` and `/v1/similarity` take a model id only; the
service behind them reports a wrong task as `400` `unsupported_model`. Entries published with
`visible: false` never resolve on any route and answer exactly like an undeclared id.

**Validation.** Payloads are checked against `llm-schemas.json`: required fields, types and
enums are enforced, unknown fields are accepted, and every violation is joined into one `400`
message. Invalid JSON is a `400` as well.

**Error envelope.** Every error is `{"error":{"message","type","param","code"}}`; `param` and
`code` are always present and `null` when not applicable.

| HTTP | `type` | `code` | When |
| --- | --- | --- | --- |
| 400 | `invalid_request_error` | `invalid_request_error` | Invalid JSON, schema violation, missing `model` / `input` / `query` / `documents` / `candidates`, `zipped` length mismatch, remote media URL. |
| 400 | `invalid_request_error` | `model_not_found` | `model` does not resolve or is hidden. |
| 400 | `invalid_request_error` | `unsupported_model` | The model exists but serves another task. |
| 400 | `invalid_request_error` | `unsupported_modality` | Image or audio attached to a target that reads text only (`param` is `messages` or `input`). |
| 401 | `invalid_request_error` | `invalid_api_key` | Missing or wrong bearer token; `WWW-Authenticate: Bearer` is set. |
| 404 | `invalid_request_error` | `model_not_found` | `GET /v1/models/{id}` for an unknown or hidden id. |
| 404 | `invalid_request_error` | `not_found` | Unknown path. |
| 405 | `invalid_request_error` | `method_not_allowed` | Known path, wrong method. |
| 500 | `internal_error` | `internal_error` | Unexpected failure. |
| 503 | `server_error` | `model_not_ready` | Workspace still loading. |

Failures that happen during generation are not HTTP errors: Chat and Completions end the choice
with `finish_reason: "content_filter"` (guard block) or `"stop"`; the Responses API returns
`status: "failed"` with `error.code` `previous_response_not_found` or `server_error`.

**Streaming.** `stream: true` switches the response to `text/event-stream`. The stream opens
with a `: stream-open` comment frame, then `data: <json>\n\n` frames. Chat Completions and
Completions end with `data: [DONE]`; with `stream_options.include_usage` a final chunk with empty
`choices` and a `usage` object precedes it. The Responses API emits typed `response.*` events
with a `sequence_number` and no `[DONE]`. The writer requests one event at a time and pauses on
a full write queue, so a slow client throttles its own stream.

**Cancel on disconnect.** Closing the connection cancels generation on the engine.

**Authentication.** `http.auth.enabled: true` requires `Authorization: Bearer <token>` on every
route except `/health`. Tokens are compared as SHA-256 digests in constant time. Binding to a
non-loopback host without auth logs a warning at startup.

**Readiness.** `/health` returns `200 OK` before auth and before models load. Every other route
returns `503` `model_not_ready` until the workspace has loaded; poll `/v1/models`.

**Finish reasons.** The closed set is `stop`, `length`, `tool_calls`, `content_filter`.
`FINISH_REASON_GUARD_BLOCKED` maps to `content_filter`; `BREAK_CONDITION`, `MAX_ITERATIONS`,
`CANCELLED` and `STALLED` map to `stop`.

**Modalities.** `/v1/models` reports `input_modalities` for entries that read more than text.
A request attaching `image_url` or `input_audio` parts to a target that does not accept them is
refused with `unsupported_modality` before inference. Media must be a `data:` URL or bare base64;
`http(s)://` URLs are rejected. Decodable formats: JPEG, PNG, GIF, BMP, WAV.

### Endpoints

#### POST /v1/chat/completions
Spec: [text-generation.openapi.yaml](../../../openapi/text-generation.openapi.yaml).
Streaming and buffered chat, tool calls, `reasoning_content` (text produced inside the reasoning
tags), `logprobs` / `top_logprobs`, `reasoning_effort`, `prompt_cache_key` (falls back to
`user`). `n` is ignored. With `tools`, the stream is held until tool-call markup is parsed and
tool calls arrive as whole `delta.tool_calls` chunks.

```bash
curl -N localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pipeline:agent","stream":true,"stream_options":{"include_usage":true},
       "messages":[{"role":"user","content":"Summarize Dune"}]}'
```

#### POST /v1/completions
Spec: [text-generation.openapi.yaml](../../../openapi/text-generation.openapi.yaml).
Raw prompt, no chat template. An array `prompt` is joined with newlines.

```bash
curl localhost:8080/v1/completions \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"llm","prompt":"The capital of France is","max_tokens":16}'
```

#### POST /v1/responses
Spec: [text-generation.openapi.yaml](../../../openapi/text-generation.openapi.yaml).
Typed `response.*` SSE events, `instructions`, `input` as a string or item list (including
`function_call` / `function_call_output` replay items), stored conversations on pipeline targets
(`previous_response_id`, `store`), and `gravitee.progress` events from pipelines with a `todo`
step (see [Engine-managed to-dos](../../guides/todos/README.md)).

```bash
curl -N localhost:8080/v1/responses \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pipeline:agent","stream":true,"input":"Plan a three-day trip to Lisbon"}'
```

#### POST /v1/embeddings
Spec: [embeddings.openapi.yaml](../../../openapi/embeddings.openapi.yaml).
`encoding_format` `float` or `base64` (little-endian float32); `dimensions` is ignored.

```bash
curl localhost:8080/v1/embeddings \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"text-embedding","input":["hello world","bonjour"]}'
```

#### POST /v1/classify
Spec: [classification.openapi.yaml](../../../openapi/classification.openapi.yaml).
Sequence labels, token spans with character offsets, and per-request `labels` for zero-shot
models.

```bash
curl localhost:8080/v1/classify \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pii","input":"my email is a@b.com","labels":[{"name":"email"},{"name":"phone"}]}'
```

#### POST /v1/rerank
Spec: [embeddings.openapi.yaml](../../../openapi/embeddings.openapi.yaml).
Documents sorted by score; `top_k: 0` returns all; `return_documents` echoes the text.

```bash
curl localhost:8080/v1/rerank \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"reranker","query":"what is a neutron star","documents":["A dense stellar remnant.","A recipe for bread."],"top_k":1}'
```

#### POST /v1/similarity
Spec: [embeddings.openapi.yaml](../../../openapi/embeddings.openapi.yaml).
`mode: cross` (matrix, default) or `mode: zipped` (pairs, equal lengths).

```bash
curl localhost:8080/v1/similarity \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"text-embedding","input":["cat"],"candidates":["kitten","truck"]}'
```

#### GET /v1/models, GET /v1/models/{model}
Spec: [discovery.openapi.yaml](../../../openapi/discovery.openapi.yaml).
Visible models, then visible pipelines when `http.expose-pipelines` is on. `type` is the task
slug (`text-generation`, `text-classification`, `token-classification`, `feature-extraction`,
`reranking`); `input_modalities` appears for entries that read more than text.

```bash
curl localhost:8080/v1/models -H 'Authorization: Bearer sk-local-changeme'
curl localhost:8080/v1/models/pii -H 'Authorization: Bearer sk-local-changeme'
```

#### GET /health
Spec: [discovery.openapi.yaml](../../../openapi/discovery.openapi.yaml). Liveness only.

```bash
curl -i localhost:8080/health
```

### Smoke tests

```bash
BASE_URL=http://localhost:8080/v1 MODEL=agent uv run --with openai examples/scripts/openai_test.py
BASE_URL=http://localhost:8080/v1 API_KEY=sk-local-changeme uv run --with requests examples/scripts/classify_test.py
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `http.enabled` | boolean | `false` | Start the HTTP API. |
| `http.port` | int | `8080` | Listen port. |
| `http.host` | string | `0.0.0.0` | Bind address. |
| `http.idleTimeout` | int (s) | `0` | Connection idle timeout; 0 disables. |
| `http.tcpKeepAlive` | boolean | `true` | TCP keep-alive. |
| `http.secured` / `http.ssl.*` | boolean / block | `false` | TLS; same structure as `grpc.ssl`. |
| `http.expose-pipelines` | boolean | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |
| `http.auth.enabled` | boolean | `false` | Bearer auth. |
| `http.auth.type` | string | `bearer` | Only `bearer` is supported. |
| `http.auth.tokens` | list | unset | Accepted tokens; an empty list with auth enabled fails startup. |
| `ai.conversations.ttl` | int (s) | `3600` | Idle lifetime of stored Responses conversations; `0` disables. |

## Notes
- A bare text-generation model id returns that model's raw token stream. Channel dialects
  (gpt-oss / Harmony) are handled by the pipeline step's `tags:`, so clients should address the
  pipeline.
- Chat Completions is stateless (full message replay). Only Responses on a pipeline target stores
  conversations.
- `gravitee.progress` is a vendor extension on the Responses stream and is never an output item,
  so it cannot be replayed back as history. Chat Completions drops progress events.
- The Responses API keeps reasoning streaming live even when `tools` hold the content back.
- `max_completion_tokens` is accepted by validation but `max_tokens` (or `max_output_tokens` on
  Responses) is the value applied.

## See also
- [API overview](../README.md)
- [OpenAPI specifications](../../../openapi/README.md)
- [gRPC API](../grpc/README.md)
- [Multimodal](../../guides/multimodal/README.md)
- [Engine-managed to-dos](../../guides/todos/README.md)
- [Observability](../../operations/observability/README.md)
