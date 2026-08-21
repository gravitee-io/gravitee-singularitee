# Java client

> `SingulariteeClient` (`gravitee-singularitee-client`): an RxJava 3 wrapper over the Vert.x gRPC client, one method per RPC, with TLS, Basic auth and connection retries.

## Overview
`gravitee-singularitee-client` is the only dependency a gateway connector or another Singularitee
server needs. It builds on the Vert.x-native gRPC client (no grpc-java, no Netty shading), exposes
unary RPCs as `Single<...>` and the two streaming RPCs as `Flowable<InferResponse>`, and
implements `AutoCloseable`. Connection failures are retried with a Fibonacci backoff; once a
stream has delivered its first event, failures propagate unchanged.

## Key types
- `SingulariteeClient`: the client. Constructors take host and port plus optional Vert.x instance, keep-alive interval, Basic credentials, TLS flag and `ClientTlsOptions`.
- `ClientTlsOptions`: record `(TrustOptions trust, KeyCertOptions keyCert, boolean trustAll, boolean verifyHostname)` with factories `trusting(trust)` and `mutual(trust, keyCert)`.
- `BasicAuthGrpcClient`: package-private decorator that adds `authorization: Basic ...` to every request.
- `ClientPipelineExecutor` (`gravitee-singularitee-engine-remote`): runs a pipeline DAG locally over `remote_*` model proxies that call this client. See [Remote models and multi-server](../../guides/remote-and-multi-server/README.md).

## Usage

### Dependency

```xml
<dependency>
  <groupId>io.gravitee.singularitee</groupId>
  <artifactId>gravitee-singularitee-client</artifactId>
  <version>${singularitee.version}</version>
</dependency>
```

### Construction

| Constructor | Vert.x | Notes |
| --- | --- | --- |
| `(host, port)` | owned | Plaintext, no auth, 30 s keep-alive. |
| `(host, port, username, password)` | owned | Basic auth. |
| `(host, port, http2KeepAliveTimeout)` | owned | Keep-alive in seconds; `-1` disables. |
| `(host, port, http2KeepAliveTimeout, username, password, ssl)` | owned | Full arity. |
| `(host, port, http2KeepAliveTimeout, username, password, ssl, tls)` | owned | Full arity with `ClientTlsOptions`. |
| `(vertx, host, port)` | shared | The Vert.x instance is not closed by `close()`. |
| `(vertx, host, port, ssl)` | shared | TLS against the JVM default trust store. |
| `(vertx, host, port, username, password)` | shared | Basic auth. |
| `(vertx, host, port, username, password, ssl)` | shared | Basic auth over TLS. |
| `(vertx, host, port, http2KeepAliveTimeout)` | shared | Custom keep-alive. |
| `(vertx, host, port, http2KeepAliveTimeout, username, password, ssl)` | shared | Full arity. |
| `(vertx, host, port, http2KeepAliveTimeout, username, password, ssl, tls)` | shared | Full arity with `ClientTlsOptions`. |

"Owned" constructors create a Vert.x instance and close it in `close()`; "shared" ones reuse yours.

```java
import io.gravitee.singularitee.client.ClientTlsOptions;
import io.gravitee.singularitee.client.SingulariteeClient;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;

// Plaintext
var plain = new SingulariteeClient("localhost", 9090);

// Basic auth (server has grpc.auth.enabled: true)
var authed = new SingulariteeClient("localhost", 9090, "admin", "adminadmin");

// TLS against a publicly trusted certificate, keep-alive disabled
var tls = new SingulariteeClient("inference.example.internal", 443, -1, null, null, true);

// Private CA and mutual TLS (server has grpc.ssl.clientAuth: REQUIRED)
var mtls = new SingulariteeClient(
    "localhost", 9090, 30, null, null, true,
    ClientTlsOptions.mutual(
        new PemTrustOptions().addCertPath("certs/ca.pem"),
        new PemKeyCertOptions().setCertPath("certs/client.pem").setKeyPath("certs/client-key.pem")));
```

`ClientTlsOptions.trusting(trust)` verifies the server against a private CA without presenting a
client certificate. Passing `null` for `tls` uses the JVM default trust store.

### Methods

| Method | Returns | RPC |
| --- | --- | --- |
| `getModel(String modelId)` | `Single<GetModelResponse>` | `GraviteeModelService/GetModel` |
| `listModels()` | `Single<ListModelsResponse>` | `GraviteeModelService/ListModels` |
| `getPipeline(String pipelineId)` | `Single<GetPipelineResponse>` | `GraviteePipelineService/GetPipeline` |
| `listPipelines()` | `Single<ListPipelinesResponse>` | `GraviteePipelineService/ListPipelines` |
| `infer(InferRequest)` | `Flowable<InferResponse>` | `GraviteeInferenceService/Infer` |
| `inferPipeline(InferPipelineRequest)` | `Flowable<InferResponse>` | `GraviteeInferenceService/InferPipeline` |
| `classify(ClassifyRequest)` | `Single<ClassifyResponse>` | `GraviteeInferenceService/Classify` |
| `classifyBatch(ClassifyBatchRequest)` | `Single<ClassifyBatchResponse>` | `GraviteeInferenceService/ClassifyBatch` |
| `embed(EmbedRequest)` | `Single<EmbedResponse>` | `GraviteeVectorService/Embed` |
| `embedBatch(EmbedBatchRequest)` | `Single<EmbedBatchResponse>` | `GraviteeVectorService/EmbedBatch` |
| `cosineSimilarity(CosineSimilarityRequest)` | `Single<CosineSimilarityResponse>` | `GraviteeVectorService/CosineSimilarity` |
| `rank(RankRequest)` | `Single<RankResponse>` | `GraviteeVectorService/Rank` |
| `textSimilarity(TextSimilarityRequest)` | `Single<TextSimilarityResponse>` | `GraviteeVectorService/TextSimilarity` |
| `textRerank(TextRerankRequest)` | `Single<TextRerankResponse>` | `GraviteeVectorService/TextRerank` |
| `shutdown()` / `close()` | void | Closes the gRPC client and, for owned instances, Vert.x. |

Request and response types are the generated protobuf classes in
`io.gravitee.singularitee.protocol`; their fields are listed in the [gRPC API](../grpc/README.md).

### Unary calls

```java
import io.gravitee.singularitee.protocol.ClassifyRequest;
import io.gravitee.singularitee.protocol.EmbedRequest;

try (var client = new SingulariteeClient("localhost", 9090)) {
    client.listModels().blockingGet().getModelsList()
        .forEach(m -> System.out.println(m.getModelId() + " " + m.getTask()));

    var pii = client.classify(ClassifyRequest.newBuilder()
        .setModelId("pii").setText("mail me at a@b.com").build()).blockingGet();
    System.out.println(pii.getTopLabel() + " " + pii.getTopScore());

    var vec = client.embed(EmbedRequest.newBuilder()
        .setModelId("text-embedding").setText("hello world").build()).blockingGet();
    System.out.println(vec.getEmbedding().getValuesCount() + " dims");
}
```

### Streaming

```java
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.StepRole;

var request = InferRequest.newBuilder()
    .setModelId("llm")
    .setMessages(ChatMessageList.newBuilder().addMessages(
        ChatMessage.newBuilder().setRole(Role.ROLE_USER).setContent("What is the capital of France?")))
    .setSamplingParams(SamplingParams.newBuilder().setMaxTokens(256).setTemperature(0.7f))
    .build();

client.infer(request).blockingForEach(event -> {
    switch (event.getEventType()) {
        case RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA ->
            System.out.print(event.getResponseOutputTextDelta().getDelta());
        case RESPONSE_EVENT_TYPE_COMPLETED -> {
            var done = event.getResponseCompleted();
            System.out.println("\n" + done.getFinishReason() + " " + done.getUsage());
        }
        case RESPONSE_EVENT_TYPE_FAILED ->
            System.err.println(event.getResponseFailed().getErrorMessage());
        default -> { }
    }
});

// Pipelines: keep only the answer, drop THINKING and TOOL deltas
client.inferPipeline(InferPipelineRequest.newBuilder()
        .setPipelineId("agent").setPrompt("Summarize the plot of Dune").build())
    .filter(e -> e.getStepRole() != StepRole.STEP_ROLE_THINKING
              && e.getStepRole() != StepRole.STEP_ROLE_TOOL)
    .blockingForEach(e -> { /* ... */ });
```

Disposing the `Flowable` subscription cancels the RPC, which cancels generation on the server.

## Options

| Setting | Default | Purpose |
| --- | --- | --- |
| Connect timeout | 5000 ms | Fail fast against unreachable hosts; the HTTP/2 pool reconnects on the next RPC. |
| Idle timeout | `0` | Disabled: streams may sit quiet between tokens. |
| HTTP/2 keep-alive | 30 s (`-1` disables) | Periodic pings keep NATs and load balancers from dropping idle connections. |
| Retry backoff | Fibonacci x 200 ms, capped at 5 s | Only on `ConnectException`, `StreamResetException` and gRPC `UNAVAILABLE`. |
| Overall retry timeout | 10 s | Hard cap across all attempts; surfaces as `TimeoutException`. |
| `ssl` | `false` | TLS with ALPN HTTP/2 negotiation. |
| `tls` (`ClientTlsOptions`) | `null` | Trust and key material; `null` means the JVM default trust store. `trustAll` disables verification; `verifyHostname` defaults to true through the factories. |
| `username` / `password` | `null` | Basic auth metadata on every call. |

## Notes
- Retries cover only connection establishment. A stream that has started is never re-issued.
- The 10 s retry timeout wraps the whole retry chain; it does not cut a stream that has already
  delivered events.
- `ssl: true` without `tls` is right for a publicly trusted certificate and wrong for a private CA;
  pass `ClientTlsOptions.trusting(...)` for the latter.
- Workspaces declare the same connection settings under `remote:` (`host`, `port`, `ssl`,
  `username`, `password`); `WorkspaceLoaderComponent` builds `SingulariteeClient` instances from
  them for `remote_*` models.

## See also
- [API overview](../README.md)
- [gRPC API](../grpc/README.md)
- [Remote models and multi-server](../../guides/remote-and-multi-server/README.md)
- [Workspaces](../../workspaces/README.md)
