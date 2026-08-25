# gRPC API

> The primary API: four services on port 9090, defined by the `.proto` files in `gravitee-singularitee-protocol` and implemented in `gravitee-singularitee-grpc`.

## Overview
The gRPC front is a Vert.x gRPC server built through gravitee-node's `VertxServerFactory`, so TLS
with hot-reloadable certificates, SNI, mTLS and the HAProxy PROXY protocol all come from the
`grpc.*` block in `gravitee.yml`. Four services share one port:

| Service | Proto | Role |
| --- | --- | --- |
| `GraviteeInferenceService` | `inference.proto` | Streaming text generation (`Infer`, `InferPipeline`) and classification. |
| `GraviteeVectorService` | `vector.proto` | Embeddings, cosine similarity, ranking, text similarity, reranking. |
| `GraviteeModelService` | `model.proto` | Read-only model catalogue. |
| `GraviteePipelineService` | `pipeline.proto` | Read-only pipeline catalogue. |

Models and pipelines are loaded once at startup from the workspace; there is no publish, update or
retire RPC. Server reflection is not registered: point tools at the proto files.

## Key types
- `GraviteeInferenceServiceImpl`, `GraviteeVectorServiceImpl`, `GraviteeModelServiceImpl`, `GraviteePipelineServiceImpl` (`gravitee-singularitee-grpc`, package `io.gravitee.singularitee.service`): the implementations, driven by both fronts.
- `GrpcServerComponent` (`gravitee-singularitee-standalone-container`): binds the services, the readiness gate, tracing and `GrpcBasicAuthHandler`.
- `InferResponse`: the streamed event envelope shared by `Infer` and `InferPipeline`.
- `FinishReason`, `StepRole`, `ResponseEventType`: the enums a streaming client switches on.
- `SingulariteeClient`: the Java client. See [Java client](../java-client/README.md).

## Usage

### Conventions

**Addressing.** Every request names its target by the logical id declared in the workspace:
`model_id` for models, `pipeline_id` for pipelines. Entries published with `visible: false` are
omitted from `ListModels` and `ListPipelines` (and reported with `hidden: true` by `GetModel` /
`GetPipeline`) but remain callable; hiding is an HTTP-front refusal, not a gRPC one.

**Readiness.** `GET /health` on the gRPC port answers `200 OK` before authentication and before
any model loads. Until the workspace has finished loading, every RPC is answered with HTTP `503`,
`grpc-status: 14` (`UNAVAILABLE`) and the message `Model server is still loading`.

**Authentication.** With `grpc.auth.enabled: true`, every call must carry
`authorization: Basic base64(username:password)` as metadata; users come from
`grpc.auth.users`. A missing or wrong credential gets a trailers-only `grpc-status: 16`
(`UNAUTHENTICATED`). Comparison is constant-time. `/health` is exempt.

**TLS.** `grpc.secured: true` enables TLS with the keystore under `grpc.ssl.keystore` (`JKS`,
`PEM`, `PKCS12`, `SELF-SIGNED`; `watch: true` hot-reloads on change). `grpc.ssl.clientAuth:
REQUIRED` with a `grpc.ssl.truststore` turns it into mTLS. ALPN is forced on: gRPC needs HTTP/2.
Build truststores with `keytool -importcert`; `scripts/gen-dev-certs.sh` produces a working set.

**Errors.** Unary RPCs fail the call when the target is missing or of the wrong kind; the status
message is `Model not found: <id>`, `Model '<id>' is not an embedding model`, `Model <id> is not
a classifier`, or `Model '<id>' is neither an embedding nor a reranker model`. Streaming RPCs
behave differently: an `Infer` against an unknown id or a non-text-generation model ends the
stream with no events at all, and a failure during generation (guard block, engine error, unknown
`previous_response_id`) arrives as a `RESPONSE_EVENT_TYPE_FAILED` event rather than a gRPC error.

**Streaming.** `Infer` and `InferPipeline` return a server stream of `InferResponse`:

```
CREATED -> (OUTPUT_TEXT_DELTA | PROGRESS)* -> COMPLETED | FAILED
```

Each event carries `step_role`. Direct `Infer` emits `STEP_ROLE_UNSPECIFIED` or `STEP_ROLE_OUTPUT`;
`InferPipeline` tags reasoning as `STEP_ROLE_THINKING`, tool-call payload as `STEP_ROLE_TOOL`, the
answer as `STEP_ROLE_OUTPUT`, and never streams `STEP_ROLE_INTERNAL` steps (except their thinking
channel when the step sets `stream_thinking: true`). A client disconnect cancels generation.
Slow consumers are cancelled after `ai.streaming.buffer-capacity` tokens of lag.

### GraviteeInferenceService

| RPC | Request | Response |
| --- | --- | --- |
| `Infer` | `InferRequest` | `stream InferResponse` |
| `InferPipeline` | `InferPipelineRequest` | `stream InferResponse` |
| `Classify` | `ClassifyRequest { model_id, text, repeated ClassifyLabel labels }` | `ClassifyResponse` |
| `ClassifyBatch` | `ClassifyBatchRequest { model_id, repeated string texts, repeated ClassifyLabel labels }` | `ClassifyBatchResponse { repeated ClassifyResponse results }` |

`InferRequest`:

| Field | Type | Purpose |
| --- | --- | --- |
| `model_id` | string | Target text-generation model. |
| `prompt` / `messages` | oneof string / `ChatMessageList` | A raw prompt is sent as-is; `messages` are rendered through the model's chat template. |
| `sampling_params` | `SamplingParams` | `max_tokens` (0 = engine default), `temperature` (negative = engine default), `top_p`, `presence_penalty`, `frequency_penalty`, `seed` (0 = random), `top_logprobs` (0 = off). |
| `reasoning_tags`, `tool_call_tags` | `TagConfig` | `open_tag`, `close_tag`, `open_tag_alternatives`, `close_tag_alternatives`, `repeatable`. Routes tokens to the reasoning or tool channel. |
| `stop` | repeated string | Stop strings. |
| `lora` | `LoraConfig` | `lora_name`, `lora_path`. |
| `tools_json` | repeated string | Tool definitions in function-calling JSON, injected through the chat template. |
| `template_context` | `google.protobuf.Struct` | Extra chat-template variables (`enable_thinking`, `reasoning_effort`). Ignored with `prompt`. |
| `request_id` | string | Echoed in `ResponseCreated.response_id`. |
| `cache_key` | string | Cache-affinity key; requests sharing it reuse the same KV slot. |

`InferPipelineRequest`:

| Field | Type | Purpose |
| --- | --- | --- |
| `pipeline_id` | string | Target pipeline. |
| `prompt` / `messages` | oneof | Same as `InferRequest`. |
| `sampling_params` | `SamplingParams` | Override applied to the pipeline's infer steps. |
| `context` | map<string,string> | Seed values readable as `{{context.<key>}}`; `reasoning_effort` and `instructions` are conventional keys. |
| `tools` | repeated `ToolDefinition` | `name`, `description`, `parameters` (`ToolParameterDef { name, type, description, required }`), `template`. Rendered through `{{tools}}`. |
| `request_id` | string | Echoed back; also the id a stored conversation is kept under. |
| `cache_key` | string | As above. |
| `previous_response_id` | string | Continue a stored conversation; an unknown id fails the stream with `error_code: previous_response_not_found`. |
| `store` | optional bool | Persist the transcript under `request_id` (default true when a `request_id` is present). |

`ChatMessage { Role role, string content, repeated MediaContent media, repeated ToolCall tool_calls, string tool_call_id, string name }`.
`Role` is `ROLE_SYSTEM`, `ROLE_USER`, `ROLE_ASSISTANT`, `ROLE_TOOL`. `MediaContent { MediaType media_type, bytes data }`
carries base64 text as bytes; `MediaType` is `IMAGE_JPEG`, `IMAGE_PNG`, `IMAGE_GIF`, `IMAGE_BMP`, `AUDIO_WAV` or `APPLICATION_OCTET`.

`InferResponse { ResponseEventType event_type, oneof event, StepRole step_role }`:

| `event_type` | Payload | Fields |
| --- | --- | --- |
| `RESPONSE_EVENT_TYPE_CREATED` | `ResponseCreated` | `response_id`, `model` |
| `RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA` | `ResponseOutputTextDelta` | `delta`, `item_index`, `part_index`, `repeated PositionLogprobs logprobs` (only with `top_logprobs > 0`) |
| `RESPONSE_EVENT_TYPE_PROGRESS` | `ResponseProgress` | `step_id`, `repeated TodoItem todos { id, title, status, proof }`, `completed`, `total` |
| `RESPONSE_EVENT_TYPE_COMPLETED` | `ResponseCompleted` | `TokenUsage usage { prompt_tokens, completion_tokens, reasoning_tokens, tool_tokens }`, `InferencePerformance performance`, `FinishReason finish_reason`, `repeated ToolCall tool_calls { name, arguments_json, coercible_args, id }` |
| `RESPONSE_EVENT_TYPE_FAILED` | `ResponseFailed` | `error_code` (`content_filter`, `previous_response_not_found`, `server_error`), `error_message` |

`FinishReason`:

| Value | Meaning |
| --- | --- |
| `FINISH_REASON_STOP` | EOS or a stop string. |
| `FINISH_REASON_LENGTH` | `max_tokens` reached. |
| `FINISH_REASON_TOOL_CALLS` | The model emitted a tool call. |
| `FINISH_REASON_GUARD_BLOCKED` | A guard step blocked the request. |
| `FINISH_REASON_BREAK_CONDITION` | A `break` step halted the pipeline. |
| `FINISH_REASON_MAX_ITERATIONS` | A `loop` step hit `max_iterations`. |
| `FINISH_REASON_CANCELLED` | Client disconnect or slow-consumer cancellation. |
| `FINISH_REASON_STALLED` | The backend stalled and the sequence was force-failed; output is truncated. |

Tags 4 and 5 are reserved and never emitted.

`StepRole`: `STEP_ROLE_UNSPECIFIED` (treated as output), `STEP_ROLE_THINKING`, `STEP_ROLE_OUTPUT`,
`STEP_ROLE_INTERNAL` (never streamed), `STEP_ROLE_TOOL` (bare tool-call payload).

`ClassifyResponse { top_label, top_score, map<string,float> all_scores, repeated ClassifyResult results }`;
`ClassifyResult { label, score, optional token, optional start, optional end }` carries character
offsets for token-classification models. `ClassifyLabel { name, description }` overrides the
label set per request on GLiNER models.

### GraviteeVectorService

| RPC | Request | Response |
| --- | --- | --- |
| `Embed` | `EmbedRequest { model_id, text }` | `EmbedResponse { FloatVector embedding, token_count }` |
| `EmbedBatch` | `EmbedBatchRequest { model_id, repeated texts }` | `EmbedBatchResponse { repeated EmbedBatchItem items { embedding, token_count } }` |
| `CosineSimilarity` | `CosineSimilarityRequest { FloatVector a, FloatVector b }` | `CosineSimilarityResponse { score }` |
| `Rank` | `RankRequest { FloatVector query, repeated FloatVector candidates, top_k }` | `RankResponse { repeated RankedResult { index, score } }` |
| `TextSimilarity` | `TextSimilarityRequest { model_id, repeated input, repeated candidates, SimilarityMode mode }` | `TextSimilarityResponse { repeated scores, input_count, candidate_count, total_tokens }` |
| `TextRerank` | `TextRerankRequest { model_id, query, repeated documents, top_k }` | `TextRerankResponse { repeated TextRerankResult { index, score }, total_tokens }` |

`SimilarityMode` is `SIMILARITY_MODE_CROSS` (every pair; `scores` is a flat row-major
`input_count x candidate_count` matrix) or `SIMILARITY_MODE_ZIPPED` (positional pairs; equal
lengths required). `top_k: 0` returns every candidate, sorted. `TextRerank` accepts an embedding
model (cosine ranking) or a reranker model (cross-encoder scoring).

### GraviteeModelService

| RPC | Request | Response |
| --- | --- | --- |
| `GetModel` | `GetModelRequest { model_id }` | `GetModelResponse` |
| `ListModels` | `ListModelsRequest {}` | `ListModelsResponse { repeated GetModelResponse models }` |

`GetModelResponse`:

| Field | Purpose |
| --- | --- |
| `model_id`, `model_name` | Logical id and HuggingFace repo or path. |
| `model_type` | `ModelType` enum: `LLAMA_CPP`, `VLLM`, `ONNX_CLASSIFIER`, `ONNX_EMBEDDING`, `GLINER_CLASSIFIER`, `GLINER_NER`, `ONNX_RERANKER`, `LLAMA_CPP_EMBEDDING`, `LLAMA_CPP_RERANKER`. |
| `status` | `MODEL_STATUS_ACTIVE` or `MODEL_STATUS_ERROR`. |
| `chat_template`, `bos_token`, `eos_token` | Template metadata, so a remote client can render prompts locally. |
| `task` | `text-generation`, `text-classification`, `token-classification`, `feature-extraction`, `reranking`; empty when unknown. |
| `hidden` | `true` for `visible: false` entries. |
| `input_modalities` | `text` plus `image` and/or `audio`; empty reads as text-only. |

### GraviteePipelineService

| RPC | Request | Response |
| --- | --- | --- |
| `GetPipeline` | `GetPipelineRequest { pipeline_id }` | `GetPipelineResponse { Pipeline pipeline, PipelineStatus status }` |
| `ListPipelines` | `ListPipelinesRequest {}` | `ListPipelinesResponse { repeated GetPipelineResponse pipelines }` |

`Pipeline { pipeline_id, pipeline_name, task, hidden, input_modalities }`: discovery metadata only. The DAG (steps, edges, step configs) is server-internal, built from the workspace YAML, and never crosses the wire.
Steps are not on the wire. (Historical note: each step used to carry one of
`infer_config`, `classify_config`, `embed_config`, `route_config`, `sub_pipeline`,
`guard_config`, `break_config`, `loop_config`, `llm_guard_config`, `regex_guard_config`,
`tool_select_config`, `todo_config`. The step vocabulary is documented in
[Pipelines](../../concepts/pipelines/README.md).

### grpcurl

```bash
PROTO=gravitee-singularitee-protocol/src/main/proto
PKG=io.gravitee.singularitee.protocol

# List models
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 $PKG.GraviteeModelService/ListModels

# Streaming inference: one JSON object per event
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"model_id":"llm","messages":{"messages":[{"role":"ROLE_USER","content":"Say hi"}]},"sampling_params":{"max_tokens":32}}' \
  localhost:9090 $PKG.GraviteeInferenceService/Infer

# Pipeline
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"Summarize Dune"}' \
  localhost:9090 $PKG.GraviteeInferenceService/InferPipeline

# Classification
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"model_id":"pii","text":"mail me at a@b.com"}' \
  localhost:9090 $PKG.GraviteeInferenceService/Classify

# Embeddings
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/vector.proto \
  -d '{"model_id":"text-embedding","text":"hello"}' \
  localhost:9090 $PKG.GraviteeVectorService/Embed

# With basic auth
grpcurl -plaintext -H "authorization: Basic $(printf 'admin:adminadmin' | base64)" \
  -import-path $PROTO -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 $PKG.GraviteeModelService/ListModels

# With TLS (drop -plaintext; -cacert for a private CA; -cert/-key for mTLS)
grpcurl -cacert certs/ca.pem -import-path $PROTO -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 $PKG.GraviteeModelService/ListModels
```

## Options

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `grpc.port` | int | `9090` | Listen port. |
| `grpc.host` | string | `0.0.0.0` | Bind address. |
| `grpc.alpn` | boolean | `true` | HTTP/2 ALPN; forced on when `secured` is true. |
| `grpc.compressionSupported` | boolean | `false` | gRPC message compression. |
| `grpc.idleTimeout` | int (s) | `0` | Connection idle timeout; 0 disables. |
| `grpc.tcpKeepAlive` | boolean | `true` | TCP keep-alive. |
| `grpc.secured` | boolean | `false` | Enable TLS. |
| `grpc.ssl.sni` | boolean | `false` | Server Name Indication. |
| `grpc.ssl.clientAuth` | string | `NONE` | `NONE`, `REQUEST`, `REQUIRED` (mTLS). |
| `grpc.ssl.keystore.type` / `.path` / `.password` / `.watch` | block | unset | `JKS`, `PEM`, `PKCS12`, `SELF-SIGNED`; `watch: true` hot-reloads. |
| `grpc.ssl.truststore.type` / `.path` / `.password` | block | unset | `JKS`, `PEM`, `PKCS12`, `PEM-FOLDER`. |
| `grpc.ssl.tlsProtocols` | string | unset | Allowed TLS versions, e.g. `TLSv1.2,TLSv1.3`. |
| `grpc.haproxy.proxyProtocol` | boolean | `false` | HAProxy PROXY protocol. |
| `grpc.auth.enabled` | boolean | `false` | HTTP Basic auth on every RPC. |
| `grpc.auth.type` | string | `basic` | Only `basic` is supported. |
| `grpc.auth.users.<name>` | map | unset | `username: password`; also `GRAVITEE_GRPC_AUTH_USERS_<USER>`. |
| `grpc.client.ssl.*` | block | unset | Outbound trust and key material for `remote:` endpoints with `ssl: true`. |
| `ai.streaming.buffer-capacity` | int | `256` | Tokens a slow client may lag before its stream is cancelled. |

## Notes
- `grpc.auth.enabled: true` with an empty `grpc.auth.users` fails startup.
- Sampling zero-values mean "engine default" (`max_tokens: 0`, `top_p: 0`, `seed: 0` = random);
  `temperature` uses a negative value for the engine default because `0` is valid greedy decoding.
- `TokenUsage` has no `total_tokens` (tag 5 is reserved); sum `prompt_tokens` and `completion_tokens`.
- `Infer` against a bare model returns raw tokens. Dialect handling (channel markers, tool tags)
  lives in the pipeline step's `tags:`; call the pipeline for client-facing traffic.
- `protocol` is a published contract: add fields and enum values, never renumber or reuse a tag.
  Retired values are tombstoned with `reserved`.
- Java reads zero entries from cert-only PKCS12 bundles produced by `openssl pkcs12 -export -nokeys`;
  mTLS then rejects valid clients. Build truststores with `keytool -importcert`.

## See also
- [API overview](../README.md)
- [Java client](../java-client/README.md)
- [HTTP API](../http/README.md)
- [Pipelines](../../concepts/pipelines/README.md)
- [Remote models and multi-server](../../guides/remote-and-multi-server/README.md)
- [Observability](../../operations/observability/README.md)
