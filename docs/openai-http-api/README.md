# OpenAI HTTP API

> An opt-in OpenAI-compatible HTTP/JSON server (`gravitee-singularitee-http`) over the same models and pipelines as the gRPC API — chat completions, embeddings, and Gravitee classify/rerank/similarity extensions.

## Overview
The `gravitee-singularitee-http` module serves a native OpenAI-compatible API so standard clients
(the OpenAI SDKs, LangChain, `curl`) integrate directly — no Gravitee gateway required. It runs as
a second Vert.x server next to the gRPC server, on its own port with independent TLS and auth
(config prefix `http.*`, default port `8080`, disabled by default). The layer is a thin JSON⇄proto
translator: handlers validate the payload against per-endpoint JSON schemas, resolve the `model`
field to a text-gen model or a pipeline, drive the same local `GraviteeInferenceServiceImpl` /
`GraviteeVectorServiceImpl` services the gRPC server uses, and format the token stream back as
OpenAI chunks or a buffered JSON response — so metrics, tracing, and cancel-on-disconnect are all
inherited from the core.

## Key types
- `HttpApiServerComponent` — standalone container component: reads `http.*` config, builds the router (body handler → `/health` → readiness gate → auth → routes → error handlers), wraps it in a tracing handler.
- `OpenAiRoutes` — mounts every endpoint both bare and under `/v1` (OpenAI SDKs call `/v1/...`).
- `ModelOrPipelineResolver` — maps the `model` field to an `InferRequest` (text-gen model) or `InferPipelineRequest` (pipeline); `pipeline:` prefix forces the pipeline namespace.
- `ChatCompletionsHandler`, `CompletionsHandler`, `ResponsesHandler`, `EmbeddingsHandler`, `ClassifyHandler`, `RerankHandler`, `SimilarityHandler`, `ModelsHandler` — one handler per endpoint.
- `Dispatch` — starts the inference through a `WriteStreamTokenAdapter` and wires `rc.response().closeHandler(...)` to `onClientDisconnect()`, cancelling generation when the client goes away.
- `InferenceResponseFormatter` — token stream → OpenAI chat/completions/responses events; renders thinking deltas as `reasoning_content` and parses tool-call markup into structured `delta.tool_calls`.
- `VertxSseWriter` — backpressure-coupled SSE writer (`text/event-stream`).
- `PayloadValidator` / `SchemaName` — Draft 2020-12 JSON-schema validation from `/llm-schemas.json`.
- `BearerTokenAuthHandler` — constant-time SHA-256 bearer-token check, 401 with the OpenAI error envelope.
- `OpenAiError` / `JsonResponses` — the `{"error":{message,type,param,code}}` envelope.

## Usage

### Enabling it (`gravitee.yml`)

```yaml
http:
  enabled: true                 # default false — the component is a no-op when disabled
  port: 8080
  host: 0.0.0.0
  secured: false
  ssl: { ... }                  # same structure as grpc.ssl (keystore/truststore, SNI, mTLS)
  expose-pipelines: true        # list pipelines on /v1/models and accept pipeline ids
  auth:
    enabled: true
    type: bearer                # only "bearer" is supported
    tokens:
      - sk-local-changeme
```

### Endpoints

| Method & path | Description |
| --- | --- |
| `POST /v1/chat/completions` | Chat completions — streaming + non-streaming, tool calls, `reasoning_content` |
| `POST /v1/completions` | Legacy text completions |
| `POST /v1/responses` | OpenAI Responses API (typed `response.*` SSE events) |
| `POST /v1/embeddings` | Embeddings (`float` or `base64` encoding) |
| `GET /v1/models`, `GET /v1/models/{id}` | List / get models (and pipelines when `expose-pipelines: true`) |
| `POST /v1/classify` | Classification — fixed-label, token-level NER spans, or GLiNER zero-shot via `labels` *(Gravitee extension)* |
| `POST /v1/rerank` | Cohere-style reranking *(Gravitee extension)* |
| `POST /v1/similarity` | Text similarity, `cross` or `zipped` mode *(Gravitee extension)* |

Every path is also served without the `/v1` prefix. `GET /health` always returns 200,
unauthenticated, before the readiness gate.

### Model vs. pipeline resolution
The `model` field resolves in this order (model ids and pipeline ids are separate namespaces):
1. `pipeline:<id>` — forces the pipeline lookup (`InferPipeline`).
2. Otherwise, a registered model whose engine is a `TextGenEngine` wins (`Infer`).
3. Otherwise, a bare pipeline id is the fallback.
4. Unresolved → `400` with code `model_not_found`.

### curl

```bash
# Streaming chat
curl -N localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"llm","stream":true,"messages":[{"role":"user","content":"Say hi"}]}'

# Pipeline via the pipeline: prefix, with usage in the final chunk
curl -N localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pipeline:reasoning-pipeline","stream":true,
       "stream_options":{"include_usage":true},
       "messages":[{"role":"user","content":"Summarize Dune"}]}'

# Embeddings
curl localhost:8080/v1/embeddings \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"embedding","input":"hello world"}'

# Zero-shot classification (Gravitee extension, GLiNER per-request labels)
curl localhost:8080/v1/classify \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pii","input":"my email is a@b.com","labels":[{"name":"email"},{"name":"phone"}]}'

# Models
curl localhost:8080/v1/models -H 'Authorization: Bearer sk-local-changeme'
```

### Python OpenAI SDK

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="sk-local-changeme")

stream = client.chat.completions.create(
    model="llm",
    messages=[{"role": "user", "content": "hi"}],
    stream=True,
)
for chunk in stream:
    delta = chunk.choices[0].delta
    print(delta.content or getattr(delta, "reasoning_content", "") or "", end="")

client.embeddings.create(model="embedding", input="hello")
client.models.list()
```

### Smoke tests
Two ready-made scripts under `scripts/` exercise the surface end-to-end (both work against the
native API or a Gravitee gateway in front of it):

```bash
# Chat completions + Responses API, stream and non-stream, prints reasoning_content
BASE_URL=http://localhost:8080/v1 MODEL=reasoning-pipeline uv run --with openai examples/scripts/openai_test.py

# /v1/classify: PII + guardrails checks with coloured output
BASE_URL=http://localhost:8080/v1 API_KEY=sk-local-changeme uv run --with requests python examples/scripts/classify_test.py
```

## Options

| Key | Default | Purpose |
| --- | --- | --- |
| `http.enabled` | `false` | Opt-in switch; when false `HttpApiServerComponent` does nothing. |
| `http.port` | `8080` | Listen port (independent of `grpc.port`). |
| `http.host` | `0.0.0.0` | Bind address. |
| `http.secured` / `http.ssl.*` | `false` / — | TLS; same structure as the `grpc.ssl` block (keystore, truststore, SNI, mTLS, hot-reload). |
| `http.expose-pipelines` | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |
| `http.auth.enabled` | `false` | Bearer API-key auth. |
| `http.auth.type` | `bearer` | Only `bearer` is supported (anything else fails startup). |
| `http.auth.tokens` | — | YAML list of accepted API keys; empty list with auth enabled fails startup. |

Request-level knobs: `stream` (default `false`), `stream_options.include_usage` (default `false`;
adds a final usage chunk), `encoding_format` on `/v1/embeddings` (`float` or `base64`), `mode` on
`/v1/similarity` (`cross` or `zipped`).

## Notes
- **SSE backpressure is real**: `VertxSseWriter` requests exactly one event at a time and pauses via `drainHandler` when the HTTP write queue is full, so a slow client throttles the token producer instead of buffering unboundedly.
- **Client disconnect cancels generation**: `Dispatch.drive` registers `rc.response().closeHandler → adapter.onClientDisconnect()`, propagating the cancel into the engine — no tokens are wasted on closed connections.
- **`reasoning_content`**: model `<think>…</think>` output is surfaced as OpenAI-style `reasoning_content` in both streamed deltas and buffered messages.
- **Tool calls buffer the stream**: when the request carries `tools`, `ChatCompletionsHandler` switches to `chatBufferedStreamEvents` — the whole answer is accumulated, tool-call markup is parsed, and structured `delta.tool_calls` chunks are emitted with `finish_reason: "tool_calls"` instead of leaking raw markup token-by-token.
- **Validation is lenient by design**: schemas allow `additionalProperties` so SDK-added fields don't break requests; they enforce required fields, types, and enums, and errors return all messages joined in one 400.
- **Error envelope**: everything uses `{"error":{"message","type","param","code"}}` — `400` for invalid payloads and unknown models (`model_not_found`), `401` (`invalid_api_key`), `404` (`not_found`), `405`, `500` (`internal_error`), and `503` (`model_not_ready`) while the workspace is still loading.
- **Bearer comparison is constant-time** over SHA-256 digests (no early exit across configured keys), so neither key length nor content leaks via timing.
- **Unprotected non-loopback bind warns at startup**: binding to a non-loopback host with `http.auth.enabled: false` logs a prominent warning.
- **Tracer ownership**: the OpenTelemetry tracer is started/stopped by `GrpcServerComponent`; the HTTP component only opens per-request `SERVER` spans on it (continuing inbound `traceparent`).

## See also
- [gRPC API & Client](../grpc-api-and-client/README.md) — the underlying protocol this module translates to/from.
- [Getting Started](../getting-started/README.md) — running the standalone server.
- [Pipelines](../pipelines/README.md) — what a `pipeline:` model id executes.
- [Classification](../classification/README.md) — the engines behind `/v1/classify`.
- [Embeddings & Reranking](../embeddings-and-reranking/README.md) — `/v1/embeddings`, `/v1/rerank`, `/v1/similarity`.
- [Observability](../observability/README.md) — tracing and metrics shared with the gRPC path.
