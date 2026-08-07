# Singularitee — OpenAI-compatible HTTP API

A native OpenAI-compatible HTTP/JSON API over Singularitee's models and pipelines, so clients
(the OpenAI SDKs, LangChain, `curl`, …) can integrate directly — no Gravitee gateway in front.

It runs as a second Vert.x server alongside the gRPC server, sharing the same model/pipeline
registries and engines. The HTTP layer is a thin JSON⇄proto translator that drives the same local
inference services the gRPC server uses (so metrics, tracing and cancel-on-disconnect are inherited).

## Enabling it

Opt-in via the `http:` block in `gravitee.yml` (disabled by default):

```yaml
http:
  enabled: true
  port: 8080
  host: 0.0.0.0
  expose-pipelines: true        # list pipelines on /v1/models and accept pipeline ids
  auth:
    enabled: true
    type: bearer
    tokens:
      - sk-local-changeme
```

TLS uses the same `secured` / `ssl` structure as the `grpc:` block. When the server binds to a
non-loopback host without auth, a warning is logged.

## Endpoints

| Method & path | Description |
|---|---|
| `POST /v1/chat/completions` | Chat completions (streaming + non-streaming, tool calls, `reasoning_content`) |
| `POST /v1/completions` | Legacy text completions |
| `POST /v1/responses` | OpenAI Responses API |
| `POST /v1/embeddings` | Embeddings (`float` or `base64` encoding) |
| `GET  /v1/models`, `GET /v1/models/{id}` | List / get models (and pipelines) |
| `POST /v1/classify` | Classification — fixed-label, token-level NER spans, or GLiNER zero-shot (`labels`) *(Gravitee extension)* |
| `POST /v1/rerank` | Cohere-style reranking *(Gravitee extension)* |
| `POST /v1/similarity` | Text similarity, `cross` or `zipped` *(Gravitee extension)* |

Every path is also served without the `/v1` prefix. The `model` field selects a text-gen model, or a
pipeline when prefixed with `pipeline:` (or a bare pipeline id when there is no model of that name).
Model `<think>…</think>` reasoning is surfaced as OpenAI `reasoning_content`.

## Examples

```bash
# Streaming chat
curl -N localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"llm","stream":true,"messages":[{"role":"user","content":"Say hi"}]}'

# Embeddings
curl localhost:8080/v1/embeddings \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"embedding","input":"hello world"}'

# Zero-shot classification (Gravitee extension)
curl localhost:8080/v1/classify \
  -H 'Authorization: Bearer sk-local-changeme' -H 'content-type: application/json' \
  -d '{"model":"pii","input":"my email is a@b.com","labels":[{"name":"email"},{"name":"phone"}]}'

# Models
curl localhost:8080/v1/models -H 'Authorization: Bearer sk-local-changeme'
```

With the Python OpenAI SDK:

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8080/v1", api_key="sk-local-changeme")
client.chat.completions.create(model="llm", messages=[{"role": "user", "content": "hi"}], stream=True)
client.embeddings.create(model="embedding", input="hello")
client.models.list()
```

## Errors

Errors use the OpenAI envelope `{"error":{"message","type","param","code"}}`: `400` for invalid
payloads / unknown model (`model_not_found`); `401` for auth; `404` for unknown path / model id;
`500` for internal errors.
