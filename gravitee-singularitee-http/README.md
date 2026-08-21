# gravitee-singularitee-http

OpenAI-compatible HTTP/JSON API over the models and pipelines of a Singularitee server.

The module is a translator: it validates each payload against `src/main/resources/llm-schemas.json`,
resolves the `model` field to a model or a pipeline, builds the proto request, drives the same
local inference and vector services the gRPC server uses, and renders the result as OpenAI JSON
or SSE. It holds no inference logic. It runs as a second Vert.x server (`http.*` in
`gravitee.yml`, port `8080`, `http.enabled: false` by default).

Routes, served bare and under `/v1`:

| Route | Handler |
| --- | --- |
| `POST /v1/chat/completions` | `ChatCompletionsHandler` |
| `POST /v1/completions` | `CompletionsHandler` |
| `POST /v1/responses` | `ResponsesHandler` |
| `POST /v1/embeddings` | `EmbeddingsHandler` |
| `POST /v1/classify` | `ClassifyHandler` |
| `POST /v1/rerank` | `RerankHandler` |
| `POST /v1/similarity` | `SimilarityHandler` |
| `GET /v1/models`, `GET /v1/models/{model}` | `ModelsHandler` |

`/health` and the readiness gate are mounted by `HttpApiServerComponent` in the standalone
container.

Documentation:

- [HTTP API reference](../docs/api/http/README.md): conventions, error codes, one section per endpoint.
- [OpenAPI specifications](../openapi/README.md): one spec per API type.
- [API overview](../docs/api/README.md): how this front relates to the gRPC API.
