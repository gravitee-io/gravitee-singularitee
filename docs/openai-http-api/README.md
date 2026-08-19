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
| `GET /v1/models`, `GET /v1/models/{id}` | List / get published models (and pipelines when `expose-pipelines: true`) |
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

Entries the workspace marked `visible: false` never resolve: they are absent from
`/v1/models`, `404` on `/v1/models/{id}`, and answer `model_not_found` on every
inference route — the same reply an id that was never declared gets. They stay
callable as pipeline dependencies and over gRPC, so hiding a model is how you
publish a pipeline without publishing the parts it is built from.

### What `/v1/models` says an entry is
`type` carries the entry's **task** — `text-generation`, `text-classification`,
`token-classification`, `feature-extraction`, `reranking` — and pipelines are
described the same way as models. A pipeline is not labelled as a pipeline: the
field answers "which endpoint does this belong on", and that is settled by the
surface it serves, not by whether one model or a guarded, routed DAG produced the
answer. A pipeline reads as anything else only if its workspace says so, by
declaring `task:` outright. When no task can be determined the field is omitted.

`input_modalities` lists what the entry will read — `["text","image"]` for a vision
model — and is omitted for the text-only majority. It is deliberately separate from
`type`: a VLM is still `text-generation` and still belongs on `/chat/completions`;
modality says what you may attach, not where to send it.

Attaching media the target cannot read is refused before inference, on
`/v1/chat/completions` and `/v1/responses`:

```json
{ "error": { "message": "The model `llm` does not accept image input (accepts: text)",
             "type": "invalid_request_error", "param": "messages",
             "code": "unsupported_modality" } }
```

See [Multimodal](../multimodal/README.md) for where each backend's answer comes from.

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
| `http.expose-pipelines` | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. Per-entry `visible: false` hides individually on top of this switch. |
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
- **Stored conversations (Responses API, pipeline targets)**: every `/v1/responses` pipeline
  response carries a unique `resp_…` id and is stored server-side (idle TTL
  `ai.conversations.ttl`, default 1 h) unless the request sets `store: false`. The next turn
  sends `previous_response_id` + only the new `input` — the server prepends its own curated
  transcript (internal tool turns and the todo plan included), exactly the OpenAI
  continuation model. An unknown/expired id fails with `previous_response_not_found` rather
  than silently dropping history. Chat Completions remains stateless (message replay);
  direct-model Responses targets remain stateless too.
- **`gravitee.progress` (Responses API only, vendor extension)**: pipelines with a `todo` step interleave auxiliary progress events in the `/v1/responses` stream — `{"type":"gravitee.progress","sequence_number":n,"step_id":…,"todos":[{id,title,status}…],"completed":n,"total":n,"text":"1. [x] …\n2. [>] …"}` (`text` is a preformatted multi-line plan view — `[x]` done, `[>]` in progress, `[ ]` pending — so clients can print it directly). This is **not** an OpenAI event type: it is deliberately `gravitee.`-namespaced, follows the same `type`+`sequence_number` envelope as the canonical `response.*` events, and conforming clients skip it per OpenAI's ignore-unknown-event-types guidance. It is a side-channel by design — NOT an output item — so the internal `set_todos`/`complete_todo` calls never become client conversation state and can never be replayed back as history (output items would be; see [Engine-managed to-dos](../todos/README.md) for the full rationale). Chat Completions drops progress events entirely: that schema is a strict closed set and stays 100% conformant.
- **Live reasoning on the buffered path**: when tools are declared, `/v1/responses` streaming buffers content until tool-call parsing, but `response.reasoning_summary_text.delta` events stream LIVE (single reasoning item at `output_index 0`, closed before the final item train). Internal pipeline steps contribute deltas only when their config sets `stream_thinking: true`.
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
