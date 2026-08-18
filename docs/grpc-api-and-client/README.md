# gRPC API & Java Client

> The four gRPC services (model, pipeline, inference, vector), their request/response shapes, the `SingulariteeClient` Java client, and the server's port/TLS/basic-auth configuration.

## Overview
Singularitee's primary surface is gRPC over HTTP/2, served by a Vert.x gRPC server built through
gravitee-node's `VertxServerFactory` (so TLS with hot-reloadable certificates, SNI, mTLS, and
HAProxy PROXY protocol all come from the `grpc.*` block in `gravitee.yml`). Four services are bound
on one port (default `9090`): `GraviteeModelService` and `GraviteePipelineService` are read-only
queries over the workspace-loaded registries; `GraviteeInferenceService` streams text generation
(direct model or pipeline DAG) and runs classification; `GraviteeVectorService` covers embeddings
and SIMD vector math. Models and pipelines are loaded once at startup from the workspace YAML —
there is no runtime publish/retire lifecycle. The `gravitee-singularitee-client` module ships
`SingulariteeClient`, an RxJava 3 wrapper over the Vert.x-native gRPC client (no grpc-java/Netty) with
fail-fast connect, Fibonacci-backoff retries, TLS, and HTTP Basic auth.

## Key types
- `GraviteeModelService` — `GetModel`, `ListModels` (proto: `model.proto`).
- `GraviteePipelineService` — `GetPipeline`, `ListPipelines` (proto: `pipeline.proto`).
- `GraviteeInferenceService` — `Infer`, `InferPipeline` (server-streaming), `Classify`, `ClassifyBatch` (proto: `inference.proto`).
- `GraviteeVectorService` — `Embed`, `EmbedBatch`, `CosineSimilarity`, `Rank`, `TextSimilarity`, `TextRerank` (proto: `vector.proto`).
- `SingulariteeClient` — the Java client; one method per RPC, returning `Single<...>` for unary calls and `Flowable<InferResponse>` for streams. `AutoCloseable`.
- `BasicAuthGrpcClient` — package-private `GrpcClient` decorator that sets `authorization: Basic base64(user:pass)` on every request.
- `GrpcServerComponent` — standalone container component that binds the four services, wraps them in tracing, readiness gating, and optional `GrpcBasicAuthHandler` auth.
- `InferResponse` — the streamed event envelope: `event_type` + oneof `response_created | response_output_text_delta | response_completed | response_failed`, plus `step_role`.

## Usage

### The services and RPCs

**GraviteeModelService** (read-only — models are fixed at startup):

| RPC | Request | Response |
| --- | --- | --- |
| `GetModel` | `GetModelRequest { model_id }` | `GetModelResponse { model_id, model_name, model_type, status, chat_template, bos_token, eos_token, task, hidden }` |
| `ListModels` | `ListModelsRequest {}` | `ListModelsResponse { repeated GetModelResponse models }` |

`model_type` distinguishes engines (`MODEL_TYPE_LLAMA_CPP`, `MODEL_TYPE_VLLM`,
`MODEL_TYPE_ONNX_CLASSIFIER`, `MODEL_TYPE_ONNX_EMBEDDING`, `MODEL_TYPE_GLINER_CLASSIFIER`,
`MODEL_TYPE_GLINER_NER`, `MODEL_TYPE_ONNX_RERANKER`, `MODEL_TYPE_LLAMA_CPP_EMBEDDING`,
`MODEL_TYPE_LLAMA_CPP_RERANKER`). `task` is a pipeline-task slug for routing
(`text-generation`, `text-classification`, `token-classification`,
`feature-extraction`, `reranking`) — the workspace's `task:` when it declared one,
the engine's own answer otherwise.

`hidden` marks a model the workspace published with `visible: false`. `ListModels`
omits those entirely; `GetModel` still answers for them, which is what keeps a
hidden model usable as another server's `remote_*` backing — the OpenAI HTTP
surface is where hiding turns into a refusal.

**GraviteePipelineService**:

| RPC | Request | Response |
| --- | --- | --- |
| `GetPipeline` | `GetPipelineRequest { pipeline_id }` | `GetPipelineResponse { Pipeline pipeline, PipelineStatus status }` — `Pipeline` carries `task` and `hidden` alongside its steps |
| `ListPipelines` | `ListPipelinesRequest {}` | `ListPipelinesResponse { repeated GetPipelineResponse pipelines }` |

`Pipeline` carries the full DAG: `entry_step_id`, `repeated PipelineStep steps` (each a oneof of
infer / classify / embed / route / sub_pipeline / guard / break / loop / llm_guard / regex_guard
config), and `map<string,string> edges`.

**GraviteeInferenceService**:

| RPC | Request | Response |
| --- | --- | --- |
| `Infer` | `InferRequest` | `stream InferResponse` |
| `InferPipeline` | `InferPipelineRequest` | `stream InferResponse` |
| `Classify` | `ClassifyRequest { model_id, text, repeated ClassifyLabel labels }` | `ClassifyResponse { top_label, top_score, map<string,float> all_scores, repeated ClassifyResult results }` |
| `ClassifyBatch` | `ClassifyBatchRequest { model_id, repeated texts, labels }` | `ClassifyBatchResponse { repeated ClassifyResponse results }` |

`InferRequest` fields: `model_id`, oneof input (`prompt` string or `ChatMessageList messages` —
each `ChatMessage { Role role, string content, repeated MediaContent media }`), `SamplingParams
sampling_params { max_tokens, temperature, top_p, presence_penalty, frequency_penalty, seed }`,
`TagConfig reasoning_tags / tool_call_tags { open_tag, close_tag }`, `repeated string stop`,
`LoraConfig lora`, `repeated string tools_json` (vLLM native function-calling), a
`google.protobuf.Struct template_context` for extra chat-template variables (e.g.
`enable_thinking`; ignored when `prompt` is set), and `request_id` (echoed back in
`ResponseCreated`).

`InferPipelineRequest` fields: `pipeline_id`, the same oneof input, `sampling_params` (applied to
the first infer step), `map<string,string> context` (seed values for `{{key}}` template
expressions), `repeated ToolDefinition tools` (prompt-based `{{tools}}` injection), `request_id`.

The response stream follows the OpenAI Responses event model:
`CREATED → OUTPUT_TEXT_DELTA* → COMPLETED | FAILED`. `ResponseCompleted` carries
`TokenUsage { prompt_tokens, completion_tokens, reasoning_tokens, tool_tokens }`,
`InferencePerformance` (load/prompt-eval/eval/sampling timings, token counts), and a
`FinishReason` (`STOP`, `LENGTH`, `TOOL_CALLS`, `GUARD_BLOCKED`, `BREAK_CONDITION`,
`MAX_ITERATIONS`). Pipeline deltas are tagged with `step_role` (`THINKING`, `OUTPUT`;
`INTERNAL` steps are never streamed).

**GraviteeVectorService**:

| RPC | Request | Response |
| --- | --- | --- |
| `Embed` | `EmbedRequest { model_id, text }` | `EmbedResponse { FloatVector embedding, token_count }` |
| `EmbedBatch` | `EmbedBatchRequest { model_id, repeated texts }` | `EmbedBatchResponse { repeated EmbedBatchItem items }` |
| `CosineSimilarity` | `CosineSimilarityRequest { FloatVector a, b }` | `CosineSimilarityResponse { float score }` |
| `Rank` | `RankRequest { query, repeated candidates, top_k }` | `RankResponse { repeated RankedResult { index, score } }` |
| `TextSimilarity` | `TextSimilarityRequest { model_id, repeated input, repeated candidates, SimilarityMode mode }` | `TextSimilarityResponse { repeated scores, input_count, candidate_count, total_tokens }` |
| `TextRerank` | `TextRerankRequest { model_id, query, repeated documents, top_k }` | `TextRerankResponse { repeated TextRerankResult { index, score }, total_tokens }` |

`SimilarityMode` is `SIMILARITY_MODE_CROSS` (all pairs, flat row-major matrix) or
`SIMILARITY_MODE_ZIPPED` (positional 1-to-1; arrays must be the same length).

### Java client

```java
import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.protocol.*;

// Plaintext, no auth. Other constructors: (host, port, username, password),
// (vertx, host, port), (vertx, host, port, ssl), (vertx, host, port, username, password, ssl).
try (var client = new SingulariteeClient("localhost", 9090)) {

    // List models (unary → Single)
    ListModelsResponse models = client.listModels().blockingGet();
    models.getModelsList().forEach(m ->
        System.out.println(m.getModelId() + " " + m.getModelType() + " " + m.getTask()));

    // Streaming inference (server-streaming → Flowable, one event per token)
    var request = InferRequest.newBuilder()
        .setModelId("llm")
        .setMessages(ChatMessageList.newBuilder()
            .addMessages(ChatMessage.newBuilder()
                .setRole(Role.ROLE_USER)
                .setContent("What is the capital of France?")))
        .setSamplingParams(SamplingParams.newBuilder().setMaxTokens(256).setTemperature(0.7f))
        .build();

    client.infer(request)
        .blockingForEach(event -> {
            switch (event.getEventType()) {
                case RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA ->
                    System.out.print(event.getResponseOutputTextDelta().getDelta());
                case RESPONSE_EVENT_TYPE_COMPLETED ->
                    System.out.println("\nusage: " + event.getResponseCompleted().getUsage());
                default -> {}
            }
        });

    // Pipeline execution — deltas carry getStepRole() (THINKING vs OUTPUT)
    client.inferPipeline(InferPipelineRequest.newBuilder()
            .setPipelineId("reasoning-pipeline")
            .setPrompt("Summarize the plot of Dune")
            .build())
        .filter(e -> e.getStepRole() != StepRole.STEP_ROLE_THINKING)
        .blockingForEach(e -> { /* ... */ });

    // Classification and embeddings (unary)
    ClassifyResponse c = client.classify(
        ClassifyRequest.newBuilder().setModelId("pii").setText("mail me at a@b.com").build()
    ).blockingGet();

    EmbedResponse e = client.embed(
        EmbedRequest.newBuilder().setModelId("embedding").setText("hello world").build()
    ).blockingGet();
}
```

### grpcurl

```bash
# List models (server reflection is not registered — pass the proto files)
grpcurl -plaintext \
  -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeModelService/ListModels

# Streaming inference (prints one JSON message per event)
grpcurl -plaintext \
  -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/inference.proto \
  -d '{"model_id":"llm","prompt":"Say hi","sampling_params":{"max_tokens":32}}' \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeInferenceService/Infer

# With basic auth enabled on the server
grpcurl -plaintext \
  -H "authorization: Basic $(printf 'admin:adminadmin' | base64)" \
  -import-path gravitee-singularitee-protocol/src/main/proto \
  -proto io/gravitee/singularitee/protocol/model.proto \
  localhost:9090 io.gravitee.singularitee.protocol.GraviteeModelService/ListModels
```

## Options

### Server (`grpc.*` in `gravitee.yml`)
| Key | Default | Purpose |
| --- | --- | --- |
| `grpc.port` | `9090` | Listen port. |
| `grpc.host` | `0.0.0.0` | Bind address. |
| `grpc.alpn` | `true` | HTTP/2 ALPN negotiation (forced on automatically when `secured: true`). |
| `grpc.secured` | `false` | Enable TLS. |
| `grpc.ssl.sni` | `false` | Server Name Indication. |
| `grpc.ssl.clientAuth` | `NONE` | `NONE`, `REQUEST`, `REQUIRED` (mTLS). |
| `grpc.ssl.keystore.type/path/password/watch` | — | `JKS`, `PEM`, `PKCS12`, `SELF-SIGNED`; `watch: true` hot-reloads certificates on file change. |
| `grpc.ssl.truststore.type/path/password` | — | `JKS`, `PEM`, `PKCS12`, `PEM-FOLDER`. |
| `grpc.ssl.tlsProtocols` | — | Allowed TLS versions, e.g. `TLSv1.2,TLSv1.3`. |
| `grpc.compressionSupported` | `false` | gRPC compression. |
| `grpc.idleTimeout` | `0` | Idle connection timeout (seconds, 0 = none). |
| `grpc.haproxy.proxyProtocol` | `false` | HAProxy PROXY protocol support. |
| `grpc.auth.enabled` | `false` | Enable HTTP Basic auth on gRPC calls. |
| `grpc.auth.type` | `basic` | Only `basic` is supported (anything else fails startup). |
| `grpc.auth.users.<name>` | — | `username: password` map; also readable from `-Dgrpc.auth.users.*` and `GRAVITEE_GRPC_AUTH_USERS_<USER>` env vars. |

### Client (`SingulariteeClient` behaviour)
| Setting | Default | Purpose |
| --- | --- | --- |
| Connect timeout | 5 000 ms | Fail fast against unreachable hosts; the HTTP/2 pool reconnects transparently on the next RPC. |
| Idle timeout | `0` (disabled) | Streams may legitimately sit quiet between tokens (think phases, cold loads). |
| HTTP/2 keep-alive | 30 s (constructor arg; `-1` disables) | Periodic pings keep NATs/LBs from silently dropping idle connections. |
| Retry backoff | Fibonacci × 200 ms, capped 5 s | Only on `ConnectException`, `StreamResetException`, and gRPC `UNAVAILABLE`. |
| Overall retry timeout | 10 s | Hard cap across all attempts; surfaces as a `TimeoutException`. |
| `ssl` (constructor flag) | `false` | TLS with ALPN h2 negotiation; certificates validated against the JVM default trust store. |
| `username`/`password` | none | Wraps the client in `BasicAuthGrpcClient` for servers with `grpc.auth.enabled: true`. |

## Notes
- **Readiness gate**: until the workspace finishes loading, all service calls return HTTP 503 with `grpc-status: 14` (`UNAVAILABLE`) and message "Model server is still loading". `GET /health` on the gRPC port always returns 200, unauthenticated — use it for liveness probes.
- **Read-only lifecycle**: despite `PublishModelRequest` still existing in `model.proto` (used internally by the workspace loader), the services expose only Get/List — there is no Publish/Update/Retire RPC.
- **Streaming retries stop at first token**: `SingulariteeClient` retries only the connection-establishment phase of `infer`/`inferPipeline`; once the first `InferResponse` arrives the stream is live and a failure propagates (re-issuing mid-stream is not idempotent).
- **The 10 s overall timeout applies to streams too** — `streamingCall` wraps the whole `Flowable` in `.timeout(10_000 ms)`, so a generation that goes longer than 10 s between events will be cut. Keep this in mind for slow models.
- **TLS requires ALPN**: `grpc.secured: true` auto-forces `alpn: true` server-side; client-side the `ssl` flag sets `setUseAlpn(true)` — without ALPN, TLS edges downgrade to HTTP/1.1 and gRPC framing breaks.
- **Basic auth is HTTP/2 header metadata**: `authorization: Basic base64(user:pass)` on every call; invalid credentials are rejected with gRPC `UNAUTHENTICATED` by `GrpcBasicAuthHandler`.
- **Empty user map fails startup**: `grpc.auth.enabled: true` with no users under `grpc.auth.users` throws `IllegalStateException` at boot.
- **Sampling zero-values mean "engine default"** (`max_tokens: 0`, `top_p: 0`, `seed: 0` = random); `temperature` uses *negative* for engine default since `0` is a valid greedy setting.

## See also
- [Getting Started](../getting-started/README.md) — install, run the standalone server, first workspace.
- [Pipelines](../pipelines/README.md) — the DAG step vocabulary behind `InferPipeline`.
- [OpenAI HTTP API](../openai-http-api/README.md) — the same engines over OpenAI-compatible HTTP/JSON.
- [Remote & Multi-Server](../remote-and-multi-server/README.md) — the client is also how servers call each other for remote sub-pipelines.
- [Embeddings & Reranking](../embeddings-and-reranking/README.md) — the models behind the vector service.
- [Observability](../observability/README.md) — tracing spans opened per gRPC stream.
