# gRPC API

> The primary API: five services on port 9090, defined by the `.proto` files in `gravitee-singularitee-protocol` and implemented in `gravitee-singularitee-grpc`.

## Overview
The gRPC front is a Vert.x gRPC server built through gravitee-node's `VertxServerFactory`, so TLS
with hot-reloadable certificates, SNI, mTLS and the HAProxy PROXY protocol all come from the
`grpc.*` block in `gravitee.yml`. Five services share one port:

| Service | Proto | Role |
| --- | --- | --- |
| `GraviteeInferenceService` | `inference.proto` | Streaming text generation (`Infer`, `InferPipeline`) and classification. |
| `GraviteeVectorService` | `vector.proto` | Embeddings, cosine similarity, ranking, text similarity, reranking. |
| `GraviteeDecisionService` | `decision.proto` | Typed questions (choice, score, noul) answered by a decision model or a decision pipeline. |
| `GraviteeModelService` | `model.proto` | Read-only model catalogue. |
| `GraviteePipelineService` | `pipeline.proto` | Read-only pipeline catalogue. |

Models and pipelines are loaded once at startup from the workspace; there is no publish, update or
retire RPC. Server reflection is not registered: point tools at the proto files.

## Key types
- `GraviteeInferenceServiceImpl`, `GraviteeVectorServiceImpl`, `GraviteeDecisionServiceImpl`, `GraviteeModelServiceImpl`, `GraviteePipelineServiceImpl` (`gravitee-singularitee-grpc`, package `io.gravitee.singularitee.service`): the implementations, driven by both fronts.
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

**Errors.** Unary RPCs fail the call with a status code and a message:

| Status | When | Example message |
| --- | --- | --- |
| `NOT_FOUND` (5) | Unknown model or pipeline id. | `Model not found: <id>`, `Pipeline not found: <id>` |
| `INVALID_ARGUMENT` (3) | Target of the wrong kind, or a rejected argument. | `Model '<id>' is not an embedding model`, `Model <id> is not a classifier`, `Model '<id>' is neither an embedding nor a reranker model`, `Vectors must have the same length` |
| `RESOURCE_EXHAUSTED` (8) | A slow client fell too far behind the stream buffer. | the buffer's message |
| `INTERNAL` (13) | Any other failure: engine, native, I/O, or an error the server did not write for the caller. | `Internal error (ref 1a2b3c4d)`; the server log carries the full error under the same ref. |

Streaming RPCs
behave differently: an `Infer` against an unknown id or a non-text-generation model ends the
stream with no events at all, and a failure during generation (guard block, engine error, unknown
`previous_response_id`) arrives as a `RESPONSE_EVENT_TYPE_FAILED` event rather than a gRPC error.
`GraviteeDecisionService` maps its failures to proper status codes, see
[GraviteeDecisionService](#graviteedecisionservice).

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
| `lora` | `LoraConfig` | `lora_name`, `lora_path`. A `lora_path` is refused with `invalid_request_error` unless `ai.inference.request-lora.enabled` is set. |
| `tools_json` | repeated string | Tool definitions in function-calling JSON, injected through the chat template. |
| `template_context` | `google.protobuf.Struct` | Extra chat-template variables (`enable_thinking`, `reasoning_effort`). Ignored with `prompt`. |
| `structured_output` | `StructuredOutputFormat` | Tag 53. Constrains decoding to a format, see below. |
| `request_id` | string | Echoed in `ResponseCreated.response_id`. |
| `cache_key` | string | Cache-affinity key; requests sharing it reuse the same KV slot. |

`InferPipelineRequest`:

| Field | Type | Purpose |
| --- | --- | --- |
| `pipeline_id` | string | Target pipeline. |
| `prompt` / `messages` | oneof | Same as `InferRequest`. |
| `sampling_params` | `SamplingParams` | Override applied to the pipeline's infer steps. |
| `context` | map<string,string> | Seed values readable as `{{context.<key>}}`; `reasoning_effort` and `instructions` are conventional keys. Keys the pipeline writes itself (step ids, `output_field` roots, `prompt`, `todos`, `conversation`, `__*`) are ignored; see [context fields](../../reference/context-fields.md). |
| `tools` | repeated `ToolDefinition` | `name`, `description`, `parameters` (`ToolParameterDef { name, type, description, required }`), `template`. Rendered through `{{tools}}`. |
| `request_id` | string | Echoed back; also the id a stored conversation is kept under. |
| `cache_key` | string | As above. |
| `previous_response_id` | string | Continue a stored conversation; an unknown id fails the stream with `error_code: previous_response_not_found`. |
| `store` | optional bool | Persist the transcript under `request_id` (default true when a `request_id` is present). |
| `structured_output` | `StructuredOutputFormat` | Tag 41. Applies to the `role: output` step only; other steps are untouched. |

`StructuredOutputFormat { oneof kind, string name = 10 }` constrains decoding so the text always
matches the format; `name` is informational (the OpenAI `json_schema.name`).

| `kind` | Type | The text is |
| --- | --- | --- |
| `json_schema = 1` | string | Valid against this JSON Schema, serialized as JSON. |
| `json_object = 2` | bool | Any syntactically valid JSON object. |
| `choice = 3` | `StructuredOutputFormat.ChoiceList { repeated string values }` | Exactly one of the listed strings. |
| `regex = 4` | string | A full match of the expression. vLLM only; `llama_cpp` refuses it. |
| `grammar = 5` | `StructuredOutputFormat.Grammar { string text, string root }` | A derivation of the GBNF grammar; empty `root` means `root`. |

A format the target cannot enforce ends the stream with a `FAILED` event, `error_code:
invalid_request_error`. Engine support and the JSON Schema subset are in
[Structured output](../../guides/structured-output/README.md).

`ChatMessage { Role role, string content, repeated MediaContent media, repeated ToolCall tool_calls, string tool_call_id, string name }`.
`Role` is `ROLE_SYSTEM`, `ROLE_USER`, `ROLE_ASSISTANT`, `ROLE_TOOL`. `MediaContent { MediaType media_type, bytes data }`
carries base64 text as bytes; `MediaType` is `IMAGE_JPEG`, `IMAGE_PNG`, `IMAGE_GIF`, `IMAGE_BMP`, `AUDIO_WAV` or `APPLICATION_OCTET`. Media the target does not accept
(see `input_modalities`) fails the stream with `error_code: unsupported_modality`; `APPLICATION_OCTET`
counts as an image.

`InferResponse { ResponseEventType event_type, oneof event, StepRole step_role }`:

| `event_type` | Payload | Fields |
| --- | --- | --- |
| `RESPONSE_EVENT_TYPE_CREATED` | `ResponseCreated` | `response_id`, `model` |
| `RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA` | `ResponseOutputTextDelta` | `delta`, `item_index`, `part_index`, `repeated PositionLogprobs logprobs` (only with `top_logprobs > 0`) |
| `RESPONSE_EVENT_TYPE_PROGRESS` | `ResponseProgress` | `step_id`, `repeated TodoItem todos { id, title, status, proof }`, `completed`, `total` |
| `RESPONSE_EVENT_TYPE_COMPLETED` | `ResponseCompleted` | `TokenUsage usage { prompt_tokens, completion_tokens, reasoning_tokens, tool_tokens }`, `InferencePerformance performance`, `FinishReason finish_reason`, `repeated ToolCall tool_calls { name, arguments_json, coercible_args, id }` |
| `RESPONSE_EVENT_TYPE_FAILED` | `ResponseFailed` | `error_code` (`content_filter`, `previous_response_not_found`, `invalid_request_error`, `unsupported_modality`, `server_error`), `error_message`. A `server_error` message is generic, `Inference failed (ref 1a2b3c4d)`; the server log carries the full error under the same ref. |

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
| `FINISH_REASON_START_FAILED` | A queued sequence could not start when a slot freed up; no output was generated. |

Tags 4 and 5 are reserved and never emitted.

`StepRole`: `STEP_ROLE_UNSPECIFIED` (treated as output), `STEP_ROLE_THINKING`, `STEP_ROLE_OUTPUT`,
`STEP_ROLE_INTERNAL` (never streamed), `STEP_ROLE_TOOL` (bare tool-call payload).

`ClassifyResponse { top_label, top_score, map<string,float> all_scores, repeated ClassifyResult results }`;
`ClassifyResult { label, score, optional token, optional start, optional end }` carries character
offsets for token-classification models. `ClassifyLabel { name, description }` overrides the
label set per request on GLiNER models and on decision models published with `as_classifier`;
labels a decision classifier refuses (duplicates, too many, none when it has no labels of its
own) answer `INVALID_ARGUMENT`.

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

### GraviteeDecisionService

| RPC | Request | Response |
| --- | --- | --- |
| `Decide` | `DecideRequest { oneof target { model_id, pipeline_id }, oneof input { TemplatedInput templated, RawInput raw }, include_raw_scores }` | `DecideResponse { model_id, repeated DecisionAnswer answers, TokenUsage usage, DecisionBlocked blocked }` |
| `DecideBatch` | `DecideBatchRequest { oneof target, repeated DecideBatchState states, repeated DecisionQuestion questions, include_raw_scores }` | `DecideBatchResponse { repeated DecideResponse results }` |

A target is a decision model (`llama_cpp_decision`, `vllm_decision`, `gliner_decision`, `remote_decision`) or a pipeline whose task is
`decision` (its `role: output` step is a `decide` step). Templated input lets the server render
the prompt from the model's prompt source; raw input sends prompts the caller already rendered.
Which of the two a model accepts is in `GetModelResponse.decision`.

| Message | Fields |
| --- | --- |
| `TemplatedInput` | `oneof state { text, google.protobuf.Value json, ChatMessageList messages }`, `repeated MediaContent images`, `repeated DecisionQuestion questions` (empty for a pipeline whose decide step declares its own). |
| `DecisionQuestion` | `id`, `google.protobuf.Value instructions`, `oneof kind { ChoiceQuestion choice { repeated DecisionOption options { key, description } }, ScoreQuestion score { repeated Value levels }, NoulQuestion noul { optional when_true, optional when_false } }`. |
| `RawInput` | `repeated RawPrompt prompts`. |
| `RawPrompt` | `question_id`, `oneof kind { RawChoice { option_keys }, RawScore { levels }, RawNoul }`, `oneof body { text, TokenList tokens }`. Text is tokenized with special tokens parsed and no BOS. |
| `DecisionAnswer` | `question_id`, `oneof result { ChoiceAnswer { choice, probabilities, confidence }, ScoreAnswer { score, mode, probabilities, confidence }, NoulAnswer { probability_true } }`, `repeated RawScores raw_scores` (one per scoring variant, before temperature, only with `include_raw_scores`). |
| `OptionProbability` | `key`, `probability`; choice options in prompt order, score levels keyed `"0".."n-1"`. |
| `DecisionBlocked` | `code` (`content_filter`), `message`, `step_id`: set when a guard halted the pipeline. This is a successful response, not an error. |

`usage.prompt_tokens` counts every prompt token evaluated; nothing is generated. Questions are
rendered and answered in request order. `DecideBatch` asks the same questions about each state;
results come back in input order and the first failure fails the call. At most 16 states run at
once, and none starts after a failure. A batch takes at most 256
states; too many states, a missing state or a malformed question refuses the whole batch with
`INVALID_ARGUMENT` before any state runs.

| Status | When |
| --- | --- |
| `NOT_FOUND` | Unknown model or pipeline id. |
| `INVALID_ARGUMENT` | Malformed question or prompt; the message names it (`questions.<id>: ...`). |
| `FAILED_PRECONDITION` | Not a decision target, an input mode the model's prompt source refuses, `raw` input to a pipeline, or media (images are not read yet). |
| `INTERNAL` | The model produced no usable scores (a joint head that could not use the decision order), or an engine failure, reported as `Internal error (ref 1a2b3c4d)`. |
| `UNAVAILABLE` | Workspace still loading. |

### GraviteeModelService

| RPC | Request | Response |
| --- | --- | --- |
| `GetModel` | `GetModelRequest { model_id }` | `GetModelResponse` |
| `ListModels` | `ListModelsRequest {}` | `ListModelsResponse { repeated GetModelResponse models }` |

`GetModelResponse`:

| Field | Purpose |
| --- | --- |
| `model_id`, `model_name` | Logical id and HuggingFace repo or path. |
| `model_type` | `ModelType` enum: `LLAMA_CPP`, `VLLM`, `ONNX_CLASSIFIER`, `ONNX_EMBEDDING`, `GLINER_CLASSIFIER`, `GLINER_NER`, `ONNX_RERANKER`, `LLAMA_CPP_EMBEDDING`, `LLAMA_CPP_RERANKER`, `LLAMA_CPP_DECISION`, `VLLM_DECISION`. |
| `status` | `MODEL_STATUS_ACTIVE` or `MODEL_STATUS_ERROR`. |
| `chat_template`, `bos_token`, `eos_token` | Template metadata, so a remote client can render prompts locally. |
| `task` | `text-generation`, `text-classification`, `token-classification`, `feature-extraction`, `reranking`, `decision`; empty when unknown. |
| `hidden` | `true` for `visible: false` entries. |
| `input_modalities` | `text` plus `image` and/or `audio`; empty reads as text-only. |
| `output_modalities` | `decisions` for a decision model, the value llama.cpp's server reports; empty reads as text. |
| `decision` | Decision models only: `DecisionInfo { family, max_options, joint, templated, raw }`, the family (`openjev`, `lev`, `nimble`, `kev`, `laya`, `clef`, `custom` for llama.cpp and `vllm_decision`; `decision1`, `decision2`, `decision3` for vLLM Semantic Router models, `gliner` for `gliner_decision`), the most options a question may have, whether questions are decided together, and which input modes the model accepts. |

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

# Pipeline with a structured output format
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"Is water wet? Answer yes or no.","structured_output":{"choice":{"values":["yes","no"]}}}' \
  localhost:9090 $PKG.GraviteeInferenceService/InferPipeline

# Pipeline
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"pipeline_id":"agent","prompt":"Summarize Dune"}' \
  localhost:9090 $PKG.GraviteeInferenceService/InferPipeline

# Classification
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"model_id":"pii","text":"mail me at a@b.com"}' \
  localhost:9090 $PKG.GraviteeInferenceService/Classify

# Decision: typed questions about one state
grpcurl -plaintext -import-path $PROTO -proto io/gravitee/singularitee/protocol/decision.proto \
  -d '{"model_id":"judge","templated":{"text":"The app crashes when I open settings.","questions":[
       {"id":"route","instructions":"Which team?","choice":{"options":[{"key":"billing"},{"key":"technical"}]}},
       {"id":"angry","instructions":"Is the user angry?","noul":{}}]}}' \
  localhost:9090 $PKG.GraviteeDecisionService/Decide

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
- [Structured output](../../guides/structured-output/README.md)
- [`decide` step](../../reference/steps/decide.md)
- [Remote models and multi-server](../../guides/remote-and-multi-server/README.md)
- [Observability](../../operations/observability/README.md)
