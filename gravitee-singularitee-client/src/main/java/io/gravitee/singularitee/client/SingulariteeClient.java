/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.singularitee.client;

import io.gravitee.singularitee.protocol.*;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Function;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.ReadStream;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.common.GrpcErrorException;
import io.vertx.grpc.common.GrpcStatus;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC client for Singularitee.
 *
 * <p>Uses the Vert.x-native gRPC client and the generated Vert.x stubs — no
 * grpc-java/Netty runtime involved.
 *
 * <p>Exposes three groups of operations:
 * <ul>
 *   <li><b>Model queries</b> — get, list models</li>
 *   <li><b>Pipeline queries</b> — get, list pipelines</li>
 *   <li><b>Inference</b> — direct model inference and pipeline DAG execution</li>
 *   <li><b>Vector</b> — embed, batch embed, cosine similarity, rank</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SingulariteeClient implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SingulariteeClient.class);

  /**
   * Fail-fast TCP connect timeout (ms) used when the remote host is down or
   * unreachable. The HTTP/2 pool will retry transparently on the next RPC once
   * the remote comes back — we just want each attempt to return quickly
   * instead of hanging on half-open connections.
   */
  private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

  /**
   * Idle timeout is disabled (0) because streaming inference RPCs can legitimately
   * sit quiet between tokens during long think phases or cold model loads.
   * A non-zero value would kill valid long-running streams.
   */
  private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 0;

  /**
   * Default HTTP/2 keep-alive ping interval (seconds). Sending periodic pings prevents
   * NATs, load-balancers, and cloud firewalls from silently dropping idle
   * HTTP/2 connections. Without this, an established connection that has been
   * quiet for more than the NAT timeout (typically 30-60 s) will be torn down
   * mid-side — the client only discovers this on the next RPC attempt, which
   * then surfaces as a confusing GOAWAY or RST error rather than a clean reconnect.
   *
   * <p>Pass {@code -1} to the constructor to disable the timeout entirely and
   * keep connections alive indefinitely.
   */
  private static final int DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS = 30;

  /**
   * Base unit (ms) for the Fibonacci backoff sequence. The nth retry waits
   * {@code fib(n) * RETRY_BASE_DELAY_MS} ms, so the sequence is:
   * 200 ms, 200 ms, 400 ms, 600 ms, 1 000 ms, 1 600 ms, …
   */
  private static final long RETRY_BASE_DELAY_MS = 200;

  /**
   * Maximum delay (ms) that any single Fibonacci backoff step may reach.
   * Once the Fibonacci value exceeds this cap the delay is clamped here so
   * the inter-retry pause never grows unbounded.
   */
  private static final long RETRY_MAX_DELAY_MS = 5_000;

  /**
   * Hard cap (ms) on the total time spent across all retry attempts.
   * Retries are otherwise indefinite — this timeout is what terminates the
   * retry loop, giving upstream callers a {@link java.util.concurrent.TimeoutException}
   * instead of an indefinite hang.
   *
   * <p>The timeout is applied around the full retry chain (not per attempt), so
   * it bounds the worst-case wall-clock time regardless of backoff schedule.
   */
  private static final long OVERALL_RETRY_TIMEOUT_MS = 10_000;

  private final Vertx vertx;
  private final GrpcClient grpcClient;
  private final GraviteeModelServiceGrpcClient modelStub;
  private final GraviteePipelineServiceGrpcClient pipelineStub;
  private final GraviteeInferenceServiceGrpcClient inferenceStub;
  private final GraviteeVectorServiceGrpcClient vectorStub;
  private final boolean ownsVertx;

  /**
   * Creates a new client connected to Singularitee at the given host and port,
   * using the default HTTP/2 keep-alive timeout.
   *
   * @param host the server hostname or IP
   * @param port the server gRPC port
   */
  public SingulariteeClient(String host, int port) {
    this(
      Vertx.vertx(),
      host,
      port,
      DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS,
      true,
      null,
      null,
      false,
      null
    );
  }

  /**
   * Creates a new client that authenticates with HTTP Basic credentials, sent as
   * {@code authorization: Basic base64(username:password)} metadata on every call.
   * Use this when the target server has {@code grpc.auth.enabled: true}.
   *
   * @param host     the server hostname or IP
   * @param port     the server gRPC port
   * @param username the basic-auth username
   * @param password the basic-auth password
   */
  public SingulariteeClient(String host, int port, String username, String password) {
    this(
      Vertx.vertx(),
      host,
      port,
      DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS,
      true,
      username,
      password,
      false,
      null
    );
  }

  /**
   * Creates a new client connected to Singularitee at the given host and port,
   * with a configurable HTTP/2 keep-alive timeout.
   *
   * <p>Pass {@code -1} to disable the timeout entirely — connections are kept
   * alive indefinitely (equivalent to Vert.x's default behaviour of never
   * closing idle HTTP/2 connections due to inactivity).
   *
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   */
  public SingulariteeClient(String host, int port, int http2KeepAliveTimeout) {
    this(Vertx.vertx(), host, port, http2KeepAliveTimeout, true, null, null, false, null);
  }

  /**
   * Creates a new client using an existing Vert.x instance.
   * The provided Vert.x instance is <em>not</em> closed when {@link #close()} is called.
   *
   * @param vertx the Vert.x instance
   * @param host  the server hostname or IP
   * @param port  the server gRPC port
   */
  public SingulariteeClient(Vertx vertx, String host, int port) {
    this(
      vertx,
      host,
      port,
      DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS,
      false,
      null,
      null,
      false,
      null
    );
  }

  /**
   * Creates a new client using an existing Vert.x instance, optionally connecting
   * over TLS. Use {@code ssl = true} when the server sits behind a TLS-terminating
   * edge (e.g. a cloud provider exposing gRPC on port 443). Server certificates are
   * validated against the JVM's default trust store, and ALPN negotiates HTTP/2.
   * The provided Vert.x instance is <em>not</em> closed when {@link #close()} is called.
   *
   * @param vertx the Vert.x instance
   * @param host  the server hostname or IP
   * @param port  the server gRPC port
   * @param ssl   whether to connect over TLS (with ALPN HTTP/2 negotiation)
   */
  public SingulariteeClient(Vertx vertx, String host, int port, boolean ssl) {
    this(vertx, host, port, DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS, false, null, null, ssl, null);
  }

  /**
   * Creates a new client using an existing Vert.x instance that authenticates with
   * HTTP Basic credentials, sent as {@code authorization: Basic base64(username:password)}
   * metadata on every call. The provided Vert.x instance is <em>not</em> closed when
   * {@link #close()} is called.
   *
   * @param vertx    the Vert.x instance
   * @param host     the server hostname or IP
   * @param port     the server gRPC port
   * @param username the basic-auth username
   * @param password the basic-auth password
   */
  public SingulariteeClient(Vertx vertx, String host, int port, String username, String password) {
    this(
      vertx,
      host,
      port,
      DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS,
      false,
      username,
      password,
      false,
      null
    );
  }

  /**
   * Creates a new client using an existing Vert.x instance that authenticates with
   * HTTP Basic credentials, optionally connecting over TLS. Use {@code ssl = true}
   * when the server sits behind a TLS-terminating edge (e.g. a cloud provider
   * exposing gRPC on port 443). Server certificates are validated against the JVM's
   * default trust store, and ALPN negotiates HTTP/2. The provided Vert.x instance is
   * <em>not</em> closed when {@link #close()} is called.
   *
   * @param vertx    the Vert.x instance
   * @param host     the server hostname or IP
   * @param port     the server gRPC port
   * @param username the basic-auth username
   * @param password the basic-auth password
   * @param ssl      whether to connect over TLS (with ALPN HTTP/2 negotiation)
   */
  public SingulariteeClient(
    Vertx vertx,
    String host,
    int port,
    String username,
    String password,
    boolean ssl
  ) {
    this(
      vertx,
      host,
      port,
      DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT_SECONDS,
      false,
      username,
      password,
      ssl,
      null
    );
  }

  /**
   * Creates a new client using an existing Vert.x instance, with a configurable
   * HTTP/2 keep-alive timeout.
   *
   * <p>Pass {@code -1} for {@code http2KeepAliveTimeout} to disable the timeout
   * entirely — connections are kept alive indefinitely.
   * The provided Vert.x instance is <em>not</em> closed when {@link #close()} is called.
   *
   * @param vertx                  the Vert.x instance
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   */
  public SingulariteeClient(Vertx vertx, String host, int port, int http2KeepAliveTimeout) {
    this(vertx, host, port, http2KeepAliveTimeout, false, null, null, false, null);
  }

  /**
   * Full-arity constructor: keep-alive, optional Basic credentials and optional TLS,
   * using an existing Vert.x instance (not closed by {@link #close()}).
   *
   * @param vertx                  the Vert.x instance
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   * @param username               Basic auth username, or {@code null} for no authentication
   * @param password               Basic auth password, paired with {@code username}
   * @param ssl                    whether to connect over TLS (with ALPN HTTP/2 negotiation)
   */
  public SingulariteeClient(
    Vertx vertx,
    String host,
    int port,
    int http2KeepAliveTimeout,
    String username,
    String password,
    boolean ssl
  ) {
    this(vertx, host, port, http2KeepAliveTimeout, false, username, password, ssl, null);
  }

  /**
   * Full-arity constructor with a self-managed Vert.x instance, closed by {@link #close()}.
   *
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   * @param username               Basic auth username, or {@code null} for no authentication
   * @param password               Basic auth password, paired with {@code username}
   * @param ssl                    whether to connect over TLS (with ALPN HTTP/2 negotiation)
   */
  public SingulariteeClient(
    String host,
    int port,
    int http2KeepAliveTimeout,
    String username,
    String password,
    boolean ssl
  ) {
    this(Vertx.vertx(), host, port, http2KeepAliveTimeout, true, username, password, ssl, null);
  }

  /**
   * Full-arity constructor with outbound TLS material, using an existing Vert.x instance
   * (not closed by {@link #close()}). Pass a {@link ClientTlsOptions} carrying a client
   * certificate to answer a server running {@code grpc.ssl.clientAuth: REQUIRED}.
   *
   * @param vertx                  the Vert.x instance
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   * @param username               Basic auth username, or {@code null} for no authentication
   * @param password               Basic auth password, paired with {@code username}
   * @param ssl                    whether to connect over TLS (with ALPN HTTP/2 negotiation)
   * @param tls                    trust/key material, or {@code null} for the JVM default trust store
   */
  public SingulariteeClient(
    Vertx vertx,
    String host,
    int port,
    int http2KeepAliveTimeout,
    String username,
    String password,
    boolean ssl,
    ClientTlsOptions tls
  ) {
    this(vertx, host, port, http2KeepAliveTimeout, false, username, password, ssl, tls);
  }

  /**
   * Full-arity constructor with outbound TLS material and a self-managed Vert.x instance,
   * closed by {@link #close()}.
   *
   * @param host                   the server hostname or IP
   * @param port                   the server gRPC port
   * @param http2KeepAliveTimeout  keep-alive interval in seconds, or {@code -1} to always keep alive
   * @param username               Basic auth username, or {@code null} for no authentication
   * @param password               Basic auth password, paired with {@code username}
   * @param ssl                    whether to connect over TLS (with ALPN HTTP/2 negotiation)
   * @param tls                    trust/key material, or {@code null} for the JVM default trust store
   */
  public SingulariteeClient(
    String host,
    int port,
    int http2KeepAliveTimeout,
    String username,
    String password,
    boolean ssl,
    ClientTlsOptions tls
  ) {
    this(Vertx.vertx(), host, port, http2KeepAliveTimeout, true, username, password, ssl, tls);
  }

  private SingulariteeClient(
    Vertx vertx,
    String host,
    int port,
    int http2KeepAliveTimeout,
    boolean ownsVertx,
    String username,
    String password,
    boolean ssl,
    ClientTlsOptions tls
  ) {
    this.vertx = vertx;
    this.ownsVertx = ownsVertx;
    // Configure HttpClientOptions with a short connect timeout so that
    // RPCs against an unreachable remote fail fast instead of waiting for
    // the default 60s TCP connect timeout. Vert.x's HttpClient transparently
    // reconnects on the next attempt once the remote is reachable again,
    // which is what gives us the "lazy self-healing" behavior expected by
    // the remote model engines (see WorkspaceLoaderComponent#registerRemoteModel).
    var httpOptions = new HttpClientOptions()
      .setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS)
      .setIdleTimeout(DEFAULT_IDLE_TIMEOUT_SECONDS)
      .setHttp2ClearTextUpgrade(false);
    // TLS mode: ALPN is required so the TLS handshake negotiates HTTP/2 —
    // without it, TLS-terminating edges downgrade to
    // HTTP/1.1 and gRPC framing breaks.
    if (ssl) {
      httpOptions.setSsl(true).setUseAlpn(true);
      if (tls != null) {
        // No trust material means the JVM default trust store, which is correct for a
        // publicly-trusted certificate and wrong for a private CA.
        if (tls.trustAll()) {
          httpOptions.setTrustAll(true);
        } else if (tls.trust() != null) {
          httpOptions.setTrustOptions(tls.trust());
        }
        // The client certificate: without it a server demanding clientAuth REQUIRED
        // aborts the handshake.
        if (tls.keyCert() != null) {
          httpOptions.setKeyCertOptions(tls.keyCert());
        }
        httpOptions.setVerifyHost(tls.verifyHostname());
      }
    }
    // A value of -1 means "always keep alive" — no timeout is set, and Vert.x's
    // default behaviour (never closing idle HTTP/2 connections) is preserved.
    // Any other value is applied directly as the keep-alive ping interval (seconds).
    if (http2KeepAliveTimeout != -1) {
      httpOptions.setHttp2KeepAliveTimeout(http2KeepAliveTimeout);
    }
    GrpcClient client = GrpcClient.client(vertx, httpOptions);
    // When credentials are supplied, decorate the client so every request carries
    // an HTTP Basic authorization header (gRPC metadata) for servers with grpc.auth enabled.
    this.grpcClient = (username != null)
      ? new BasicAuthGrpcClient(client, username, password)
      : client;
    var address = SocketAddress.inetSocketAddress(port, host);
    this.modelStub = GraviteeModelServiceGrpcClient.create(grpcClient, address);
    this.pipelineStub = GraviteePipelineServiceGrpcClient.create(grpcClient, address);
    this.inferenceStub = GraviteeInferenceServiceGrpcClient.create(grpcClient, address);
    this.vectorStub = GraviteeVectorServiceGrpcClient.create(grpcClient, address);
  }

  // =========================================================================
  // Model queries
  // =========================================================================

  /**
   * Retrieves the status and metadata of a published model.
   *
   * @param modelId the model identifier returned by {@link #publishModel}
   * @return a {@link Single} that emits the model metadata
   */
  public Single<GetModelResponse> getModel(String modelId) {
    return Single.fromCompletionStage(
      modelStub
        .getModel(GetModelRequest.newBuilder().setModelId(modelId).build())
        .toCompletionStage()
    )
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Lists all currently published models on the remote Singularitee.
   *
   * @return a {@link Single} that emits the list of model metadata
   */
  public Single<ListModelsResponse> listModels() {
    return Single.fromCompletionStage(
      modelStub.listModels(ListModelsRequest.getDefaultInstance()).toCompletionStage()
    )
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  // =========================================================================
  // Pipeline queries
  // =========================================================================

  /**
   * Retrieves the definition and status of a published pipeline.
   *
   * @param pipelineId the pipeline identifier returned by {@link #publishPipeline}
   * @return a {@link Single} that emits the pipeline definition and status
   */
  public Single<GetPipelineResponse> getPipeline(String pipelineId) {
    return Single.fromCompletionStage(
      pipelineStub
        .getPipeline(GetPipelineRequest.newBuilder().setPipelineId(pipelineId).build())
        .toCompletionStage()
    )
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Lists all currently published pipelines on the remote Singularitee.
   *
   * @return a {@link Single} that emits the list of pipeline metadata
   */
  public Single<ListPipelinesResponse> listPipelines() {
    return Single.fromCompletionStage(
      pipelineStub.listPipelines(ListPipelinesRequest.getDefaultInstance()).toCompletionStage()
    )
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  // =========================================================================
  // Inference (streaming)
  // =========================================================================

  /**
   * Runs streaming inference against a single published model.
   *
   * <p>Returns a {@link Flowable} that emits one {@link InferResponse} per generated token.
   * The final response has {@code is_final = true} and carries usage statistics and
   * performance metrics.
   *
   * @param request the inference request (model ID, prompt/messages, sampling params, etc.)
   * @return a backpressure-aware stream of token responses
   */
  public Flowable<InferResponse> infer(InferRequest request) {
    return streamingCall(() -> inferenceStub.infer(request));
  }

  /**
   * Executes a published pipeline DAG and streams token output.
   *
   * <p>Each {@link InferResponse} carries a {@code step_id} field identifying which
   * pipeline step produced the token, allowing callers to distinguish intermediate
   * reasoning steps from the terminal output.
   *
   * @param request the pipeline inference request (pipeline ID, input, context seed)
   * @return a backpressure-aware stream of token responses tagged with step_id
   */
  public Flowable<InferResponse> inferPipeline(InferPipelineRequest request) {
    return streamingCall(() -> inferenceStub.inferPipeline(request));
  }

  /**
   * Runs classification against a single published ONNX classifier model.
   *
   * @param request the classify request (model ID + text)
   * @return a {@link Single} that emits the classification results
   */
  public Single<ClassifyResponse> classify(ClassifyRequest request) {
    return Single.fromCompletionStage(inferenceStub.classify(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Runs batch classification against a single published classifier model.
   * Each text is classified independently; results are returned in order.
   *
   * @param request the batch classify request (model ID + texts)
   * @return a {@link Single} that emits the batch classification results
   */
  public Single<ClassifyBatchResponse> classifyBatch(ClassifyBatchRequest request) {
    return Single.fromCompletionStage(inferenceStub.classifyBatch(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  // =========================================================================
  // Vector operations
  // =========================================================================

  /**
   * Encodes a single text using a published ONNX embedding model.
   *
   * @param request the embed request (model ID + text)
   * @return a {@link Single} that emits the embedding vector
   */
  public Single<EmbedResponse> embed(EmbedRequest request) {
    return Single.fromCompletionStage(vectorStub.embed(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Encodes a batch of texts in a single call.
   *
   * @param request the batch embed request (model ID + list of texts)
   * @return a {@link Single} that emits the embedding vectors
   */
  public Single<EmbedBatchResponse> embedBatch(EmbedBatchRequest request) {
    return Single.fromCompletionStage(vectorStub.embedBatch(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Computes the cosine similarity between two float vectors.
   *
   * @param request the two vectors
   * @return a {@link Single} that emits the similarity score
   */
  public Single<CosineSimilarityResponse> cosineSimilarity(CosineSimilarityRequest request) {
    return Single.fromCompletionStage(vectorStub.cosineSimilarity(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Ranks a list of candidate vectors against a query vector and returns the top-k results.
   *
   * @param request the query vector, candidates, and top-k count
   * @return a {@link Single} that emits the ranked results
   */
  public Single<RankResponse> rank(RankRequest request) {
    return Single.fromCompletionStage(vectorStub.rank(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Computes text-to-text similarity by embedding both input and candidate arrays
   * and returning cosine similarity scores.
   *
   * @param request the input texts, candidate texts, model ID, and comparison mode
   * @return a {@link Single} that emits the similarity scores and token usage
   */
  public Single<TextSimilarityResponse> textSimilarity(TextSimilarityRequest request) {
    return Single.fromCompletionStage(vectorStub.textSimilarity(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Reranks documents against a query by embedding everything and returning
   * cosine similarity scores sorted in descending order.
   *
   * @param request the query, documents, model ID, and top-k count
   * @return a {@link Single} that emits the ranked results and token usage
   */
  public Single<TextRerankResponse> textRerank(TextRerankRequest request) {
    return Single.fromCompletionStage(vectorStub.textRerank(request).toCompletionStage())
      .retryWhen(retryPolicy())
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  // =========================================================================
  // Lifecycle
  // =========================================================================

  /**
   * Gracefully shuts down the gRPC client and, if owned, the Vert.x instance.
   */
  public void shutdown() {
    grpcClient.close();
    if (ownsVertx) {
      vertx.close();
    }
  }

  @Override
  public void close() {
    shutdown();
  }

  // =========================================================================
  // Internal helpers
  // =========================================================================

  /**
   * Returns {@code true} for errors that are safe to retry:
   * <ul>
   *   <li>TCP connection refused / network unreachable ({@link ConnectException})</li>
   *   <li>HTTP/2 stream reset ({@link StreamResetException}) — indicates a GOAWAY or RST_STREAM
   *       sent by the server, which happens on graceful server restart</li>
   *   <li>{@link GrpcErrorException} with status {@code UNAVAILABLE} — the only gRPC-level
   *       status that is unambiguously transient (connection-level, not request-level)</li>
   * </ul>
   *
   * Non-retryable statuses (INVALID_ARGUMENT, NOT_FOUND, UNIMPLEMENTED, PERMISSION_DENIED, …)
   * represent permanent request errors and must propagate immediately to the caller.
   */
  static boolean isRetryable(Throwable t) {
    if (t instanceof ConnectException) {
      return true;
    }
    if (t instanceof StreamResetException) {
      return true;
    }
    if (t instanceof GrpcErrorException gee) {
      return gee.status() == GrpcStatus.UNAVAILABLE;
    }
    // Unwrap one level — Vert.x sometimes wraps the real cause in a VertxException
    Throwable cause = t.getCause();
    if (cause != null && cause != t) {
      return isRetryable(cause);
    }
    return false;
  }

  /**
   * Fibonacci-backoff retry function for use with {@link Single#retryWhen}.
   *
   * <p>Retries indefinitely on transient connection errors (the overall
   * {@link #OVERALL_RETRY_TIMEOUT_MS} timeout is what terminates the loop).
   * The delay between attempts follows the Fibonacci sequence scaled by
   * {@link #RETRY_BASE_DELAY_MS}, capped at {@link #RETRY_MAX_DELAY_MS}:
   * 200 ms, 200 ms, 400 ms, 600 ms, 1 000 ms, … up to 30 s.
   * Any non-retryable error propagates immediately without waiting.
   */
  private static Function<Flowable<Throwable>, Publisher<?>> retryPolicy() {
    return errors -> {
      // Fibonacci state: prev = fib(n-1), curr = fib(n). Seed: fib(0)=1, fib(1)=1.
      AtomicLong prev = new AtomicLong(0);
      AtomicLong curr = new AtomicLong(1);
      return errors.flatMap(error -> {
        if (!isRetryable(error)) {
          return Flowable.error(
            (error instanceof RuntimeException re) ? re : new RuntimeException(error)
          );
        }
        long next = prev.get() + curr.get();
        prev.set(curr.get());
        curr.set(next);
        long delayMs = Math.min(next * RETRY_BASE_DELAY_MS, RETRY_MAX_DELAY_MS);
        LOGGER.warn("Transient gRPC error, retrying in {} ms: {}", delayMs, error.getMessage());
        return Flowable.timer(delayMs, TimeUnit.MILLISECONDS);
      });
    };
  }

  /**
   * Wraps a single gRPC server-streaming call as a {@link Flowable}, using the
   * supplied factory so the RPC can be re-issued on each retry attempt.
   *
   * <p>Retry semantics:
   * <ul>
   *   <li>Only the connection-establishment phase (i.e. the {@link io.vertx.core.Future}
   *       itself) is retried. Once the first {@link InferResponse} item has been
   *       emitted the stream is considered live and will not be retried — re-issuing
   *       the full request mid-stream is not idempotent.</li>
   *   <li>The same Fibonacci-backoff policy used for unary calls governs the retry
   *       schedule (indefinite retries on retryable errors only).</li>
   *   <li>An overall {@link #OVERALL_RETRY_TIMEOUT_MS} cap bounds the total wall-clock
   *       time across all attempts combined.</li>
   * </ul>
   *
   * @param futureSupplier factory that starts a fresh gRPC streaming call each time it is invoked
   * @return a backpressure-aware stream of {@link InferResponse} items
   */
  private Flowable<InferResponse> streamingCall(
    Supplier<io.vertx.core.Future<ReadStream<InferResponse>>> futureSupplier
  ) {
    // hasReceivedItem gates retry: once the server has started sending tokens
    // we must not re-issue the request.
    AtomicBoolean hasReceivedItem = new AtomicBoolean(false);

    Flowable<InferResponse> attempt = Flowable.create(
      emitter -> {
        io.vertx.core.Future<ReadStream<InferResponse>> future = futureSupplier.get();
        LOGGER.info(
          "streamingCall: setting up future.onComplete on thread '{}'",
          Thread.currentThread().getName()
        );
        future.onComplete(ar -> {
          LOGGER.info(
            "streamingCall: future completed, success={}, thread='{}'",
            ar.succeeded(),
            Thread.currentThread().getName()
          );
          if (ar.failed()) {
            LOGGER.error("streamingCall: future failed", ar.cause());
            if (!emitter.isCancelled()) emitter.onError(ar.cause());
            return;
          }
          ReadStream<InferResponse> stream = ar.result();
          stream.handler(response -> {
            hasReceivedItem.set(true);
            if (!emitter.isCancelled()) emitter.onNext(response);
          });
          stream.exceptionHandler(err -> {
            LOGGER.error("streamingCall: stream exception", err);
            if (!emitter.isCancelled()) emitter.onError(err);
          });
          stream.endHandler(v -> {
            LOGGER.info("streamingCall: stream ended");
            if (!emitter.isCancelled()) emitter.onComplete();
          });
        });
      },
      io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER
    );

    // Fibonacci state for streaming retries (independent from any concurrent unary retry).
    AtomicLong prev = new AtomicLong(0);
    AtomicLong curr = new AtomicLong(1);

    return attempt
      .retryWhen(errors ->
        errors.flatMap(error -> {
          // Never retry after the stream has started delivering items — the
          // request is no longer idempotent at that point.
          if (hasReceivedItem.get() || !isRetryable(error)) {
            return Flowable.error(
              (error instanceof RuntimeException re) ? re : new RuntimeException(error)
            );
          }
          long next = prev.get() + curr.get();
          prev.set(curr.get());
          curr.set(next);
          long delayMs = Math.min(next * RETRY_BASE_DELAY_MS, RETRY_MAX_DELAY_MS);
          LOGGER.warn(
            "Transient gRPC streaming error, retrying in {} ms: {}",
            delayMs,
            error.getMessage()
          );
          return Flowable.timer(delayMs, TimeUnit.MILLISECONDS);
        })
      )
      .timeout(OVERALL_RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }
}
