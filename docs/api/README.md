# APIs

> Two fronts over one server: the gRPC API (primary, port 9090) and the OpenAI-compatible HTTP API (opt-in, port 8080), plus the Java client for the gRPC side.

## Overview
Singularitee serves the same models and pipelines through two network fronts:

| | gRPC API | HTTP API |
| --- | --- | --- |
| Role | Primary. What the gateway connector and other Singularitee servers speak. | Opt-in. For OpenAI SDKs, `curl`, and any client that speaks the OpenAI wire format. |
| Default port | `9090` (`grpc.port`) | `8080` (`http.port`), `http.enabled: false` by default |
| Contract | `gravitee-singularitee-protocol/src/main/proto/**/*.proto` | [openapi/](../../openapi/README.md), one spec per API type |
| Module | `gravitee-singularitee-grpc` (service impls), bound by `GrpcServerComponent` | `gravitee-singularitee-http`, bound by `HttpApiServerComponent` |
| Reference | [gRPC API](./grpc/README.md) | [HTTP API](./http/README.md) |

Both fronts are thin: they share the `ModelRegistry` and `PipelineRegistry` loaded from the
workspace, the engines behind them, the `GraviteeInferenceServiceImpl` and
`GraviteeVectorServiceImpl` service objects, the inference metrics and OpenTelemetry spans, and
the readiness gate. The HTTP layer translates JSON to the proto messages and drives the same local
service objects the gRPC server exposes; it holds no inference logic of its own.

## Key types
- `GraviteeInferenceService`, `GraviteeVectorService`, `GraviteeModelService`, `GraviteePipelineService`: the four gRPC services.
- `SingulariteeClient` (`gravitee-singularitee-client`): the Java client over those services. See [Java client](./java-client/README.md).
- `OpenAiRoutes` and the handlers in `gravitee-singularitee-http`: the HTTP front.
- `ReadinessState`: shared readiness flag, flipped once the workspace has loaded.

## Usage

### Conventions side by side

| Concern | gRPC API | HTTP API |
| --- | --- | --- |
| Addressing | `model_id` / `pipeline_id` fields; separate RPCs for models (`Infer`) and pipelines (`InferPipeline`). | One `model` field: a model id, `pipeline:<id>`, or a bare pipeline id (resolved in that order). |
| Streaming | Server stream of `InferResponse` events: `CREATED`, `OUTPUT_TEXT_DELTA`*, `PROGRESS`*, then `COMPLETED` or `FAILED`. | SSE `data:` frames. Chat and Completions end with `data: [DONE]`; the Responses API ends with `response.completed` or `response.failed` and no sentinel. |
| Errors | gRPC status codes: `UNAVAILABLE` (14) while loading, `UNAUTHENTICATED` (16) on bad credentials; unary calls fail with a message such as `Model not found: <id>`; generation failures arrive as a `FAILED` event. | OpenAI envelope `{"error":{"message","type","param","code"}}` with HTTP `400`, `401`, `404`, `405`, `500`, `503`. Generation failures are rendered into the response (`finish_reason`, `status: failed`). |
| Auth | HTTP Basic in the `authorization` metadata header (`grpc.auth.enabled`, `grpc.auth.users`). | Bearer token in the `Authorization` header (`http.auth.enabled`, `http.auth.tokens`). |
| TLS | `grpc.secured: true` + `grpc.ssl.*` (keystore, truststore, `clientAuth` for mTLS, hot-reload). | `http.secured: true` + `http.ssl.*`, same structure. |
| Readiness | `GET /health` on the gRPC port answers `200` at once; RPCs get `503` with `grpc-status: 14` until the workspace has loaded. | `GET /health` answers `200` at once; every other route answers `503` `model_not_ready` until loaded. Poll `/v1/models`. |
| Finish reasons | `FinishReason` enum: `STOP`, `LENGTH`, `TOOL_CALLS`, `GUARD_BLOCKED`, `BREAK_CONDITION`, `MAX_ITERATIONS`, `CANCELLED`, `STALLED`. | Closed OpenAI set: `stop`, `length`, `tool_calls`, `content_filter`. `GUARD_BLOCKED` maps to `content_filter`; `BREAK_CONDITION`, `MAX_ITERATIONS`, `CANCELLED` and `STALLED` map to `stop`. |
| Recovery | Repair loops, retries and escalation run inside the pipeline; the caller only sees the final `finish_reason`, repaired `tool_calls` and `PROGRESS` events for todo plans. The signals and configuration are in [Tool calling](../guides/tool-calling/README.md), [Loops and chain-of-thought](../guides/loops-and-cot/README.md) and [Context fields](../reference/context-fields.md). | Same: the SSE stream carries the final outcome; `gravitee.progress` events expose plan progress. |
| Visibility | `visible: false` entries are omitted from `ListModels` / `ListPipelines` but `GetModel`, `GetPipeline`, `Infer` and `InferPipeline` still answer for them. | `visible: false` entries are absent from `/v1/models`, `404` on `/v1/models/{id}`, and `400` `model_not_found` on every other route. |
| Multimodal | `ChatMessage.media` with `MediaContent { media_type, data }`. | `image_url` / `input_audio` content parts; media the target cannot read is refused with `unsupported_modality`. |
| Discovery | `ListModels`, `GetModel`, `ListPipelines`, `GetPipeline`. | `GET /v1/models`, `GET /v1/models/{id}`; pipelines listed when `http.expose-pipelines: true`. |

### Picking a front
- Gateway connectors and server-to-server composition use gRPC through `SingulariteeClient`
  (see [Remote models and multi-server](../guides/remote-and-multi-server/README.md)).
- Applications already written against the OpenAI API use the HTTP front; point the SDK's
  `base_url` at `http://<host>:8080/v1`.

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `grpc.port` / `grpc.host` | int / string | `9090` / `0.0.0.0` | gRPC listener. |
| `grpc.secured`, `grpc.ssl.*` | boolean / block | `false` | TLS and mTLS for gRPC. |
| `grpc.auth.enabled`, `grpc.auth.users.<name>` | boolean / map | `false` | HTTP Basic over gRPC metadata. |
| `http.enabled` | boolean | `false` | Start the HTTP API. |
| `http.port` / `http.host` | int / string | `8080` / `0.0.0.0` | HTTP listener. |
| `http.secured`, `http.ssl.*` | boolean / block | `false` | TLS for HTTP. |
| `http.auth.enabled`, `http.auth.tokens` | boolean / list | `false` | Bearer tokens. |
| `http.expose-pipelines` | boolean | `true` | List pipelines on `/v1/models` and accept pipeline ids as `model`. |

Every key is documented in [Configuration](../reference/configuration.md) and accepts a `-D` system property or a `GRAVITEE_`-prefixed environment variable
(`grpc.port` becomes `GRAVITEE_GRPC_PORT`). Defaults live in
`gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/src/main/resources/config/gravitee.yml`.

## Notes
- Ports bind before models load. A TCP probe on either port proves nothing about readiness.
- One process can serve both fronts at once; they are independent listeners with independent
  TLS and auth settings over the same registries.
- A bare model id on the HTTP front returns the raw token stream of that model. For models with
  a channel dialect (gpt-oss / Harmony) the channel handling lives in the pipeline's step
  configuration, so clients should call the pipeline id.

## See also
- [gRPC API](./grpc/README.md)
- [HTTP API](./http/README.md)
- [Java client](./java-client/README.md)
- [OpenAPI specifications](../../openapi/README.md)
- [Configuration reference](../reference/configuration.md)
- [Observability](../operations/observability/README.md)
