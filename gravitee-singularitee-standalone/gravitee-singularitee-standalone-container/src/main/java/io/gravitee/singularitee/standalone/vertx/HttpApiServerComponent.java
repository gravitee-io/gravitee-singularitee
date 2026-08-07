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
package io.gravitee.singularitee.standalone.vertx;

import io.gravitee.common.service.AbstractService;
import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpServerRequest;
import io.gravitee.node.vertx.server.VertxServer;
import io.gravitee.node.vertx.server.VertxServerFactory;
import io.gravitee.node.vertx.server.VertxServerOptions;
import io.gravitee.node.vertx.server.http.VertxHttpServer;
import io.gravitee.node.vertx.server.http.VertxHttpServerOptions;
import io.gravitee.singularitee.http.auth.BearerTokenAuthHandler;
import io.gravitee.singularitee.http.json.OpenAiError;
import io.gravitee.singularitee.http.router.OpenAiRoutes;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Lifecycle component that starts the native OpenAI-compatible HTTP/JSON API server.
 *
 * <p>Built on the same gravitee-node {@link VertxServerFactory} as {@link GrpcServerComponent} but
 * on a separate port (config prefix {@code http.*}, default 8080), so it has independent TLS and
 * auth. Opt-in via {@code http.enabled} (default {@code false}); when disabled this component is a
 * no-op. Bearer API-key auth is enabled via {@code http.auth.enabled} + {@code http.auth.tokens}.
 *
 * <p>The OpenTelemetry tracer lifecycle is owned by {@link GrpcServerComponent}; this component is
 * registered after it (and gRPC is always enabled), so the tracer is already started here and this
 * component only reads it.
 *
 * <h3>Configuration ({@code gravitee.yml})</h3>
 * <pre>{@code
 * http:
 *   enabled: false
 *   port: 8080
 *   host: 0.0.0.0
 *   secured: false
 *   ssl: { ... }              # same structure as grpc.ssl
 *   expose-pipelines: true    # list pipelines on /v1/models, accept pipeline ids
 *   auth:
 *     enabled: false
 *     type: bearer
 *     tokens:
 *       - sk-local-xxxx
 * }</pre>
 */
public class HttpApiServerComponent extends AbstractService<HttpApiServerComponent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpApiServerComponent.class);

  public static final String HTTP_PREFIX = "http";
  public static final String AUTH_PREFIX = HTTP_PREFIX + ".auth";
  public static final int DEFAULT_HTTP_PORT = 8080;

  private final Environment environment;
  private final Vertx vertx;
  private final VertxServerFactory<
    VertxServer<?, VertxServerOptions>,
    VertxServerOptions
  > serverFactory;
  private final GraviteeInferenceServiceImpl inferenceService;
  private final GraviteeVectorServiceImpl vectorService;
  private final GraviteeModelServiceImpl modelService;
  private final GraviteePipelineServiceImpl pipelineService;
  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;
  private final Tracer tracer;
  private final ReadinessState readinessState;

  private VertxHttpServer vertxHttpServer;
  private HttpServer httpServer;

  public HttpApiServerComponent(
    Environment environment,
    Vertx vertx,
    VertxServerFactory<VertxServer<?, VertxServerOptions>, VertxServerOptions> serverFactory,
    GraviteeInferenceServiceImpl inferenceService,
    GraviteeVectorServiceImpl vectorService,
    GraviteeModelServiceImpl modelService,
    GraviteePipelineServiceImpl pipelineService,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    Tracer tracer,
    ReadinessState readinessState
  ) {
    this.environment = environment;
    this.vertx = vertx;
    this.serverFactory = serverFactory;
    this.inferenceService = inferenceService;
    this.vectorService = vectorService;
    this.modelService = modelService;
    this.pipelineService = pipelineService;
    this.modelRegistry = modelRegistry;
    this.pipelineRegistry = pipelineRegistry;
    this.tracer = tracer;
    this.readinessState = readinessState;
  }

  @Override
  protected void doStart() throws Exception {
    boolean enabled = Boolean.TRUE.equals(
      environment.getProperty(HTTP_PREFIX + ".enabled", Boolean.class, false)
    );
    if (!enabled) {
      LOGGER.info("Native HTTP API disabled (set http.enabled=true to enable)");
      return;
    }

    var options = VertxHttpServerOptions.builder()
      .defaultPort(DEFAULT_HTTP_PORT)
      .prefix(HTTP_PREFIX)
      .environment(environment)
      .id("http")
      .build();

    Object rawServer = serverFactory.create(options);
    if (!(rawServer instanceof VertxHttpServer)) {
      throw new IllegalStateException(
        "Expected VertxHttpServer from factory but got: " + rawServer.getClass().getName()
      );
    }
    this.vertxHttpServer = (VertxHttpServer) rawServer;
    this.httpServer = vertxHttpServer.newInstance();

    boolean exposePipelines = Boolean.TRUE.equals(
      environment.getProperty(HTTP_PREFIX + ".expose-pipelines", Boolean.class, true)
    );

    Router router = Router.router(vertx.getDelegate());
    router.route().handler(BodyHandler.create());

    // Unauthenticated liveness — always 200, before auth and the readiness gate.
    router
      .get("/health")
      .handler(rc ->
        rc.response().setStatusCode(200).putHeader("content-type", "text/plain").end("OK")
      );

    // Readiness gate — until the workspace has loaded its models, service calls get 503.
    router
      .route()
      .handler(rc -> {
        if (readinessState.isReady()) {
          rc.next();
        } else {
          writeError(rc, 503, "Model server is still loading", "server_error", "model_not_ready");
        }
      });

    final boolean authEnabled = Boolean.TRUE.equals(
      environment.getProperty(AUTH_PREFIX + ".enabled", Boolean.class, false)
    );
    if (authEnabled) {
      router.route().handler(buildAuthHandler());
    }

    OpenAiRoutes.mount(
      router,
      inferenceService,
      vectorService,
      modelService,
      pipelineService,
      modelRegistry,
      pipelineRegistry,
      exposePipelines
    );

    router.errorHandler(404, ctx ->
      writeError(ctx, 404, "Unknown endpoint", "invalid_request_error", "not_found")
    );
    router.errorHandler(405, ctx ->
      writeError(ctx, 405, "Method not allowed", "invalid_request_error", "method_not_allowed")
    );
    router
      .route()
      .failureHandler(ctx -> {
        Throwable failure = ctx.failure();
        if (ctx.statusCode() == 404 || ctx.statusCode() == 405) {
          writeError(
            ctx,
            ctx.statusCode(),
            "Request error",
            "invalid_request_error",
            "invalid_request_error"
          );
          return;
        }
        LOGGER.error("Unhandled HTTP API failure", failure);
        writeError(ctx, 500, "Internal error", "internal_error", "internal_error");
      });

    if (!authEnabled && !isLoopback(options.getHost())) {
      LOGGER.warn(
        "Native HTTP API is bound to non-loopback host '{}' without authentication — " +
          "set http.auth.enabled=true and configure http.auth.tokens",
        options.getHost()
      );
    }

    final Handler<HttpServerRequest> requestHandler = tracingHandler(router);

    var latch = new CountDownLatch(1);
    final boolean[] started = { false };
    httpServer
      .getDelegate()
      .requestHandler(requestHandler)
      .listen()
      .onSuccess(s -> {
        started[0] = true;
        LOGGER.info(
          "Native OpenAI-compatible HTTP API listening on {}:{} (tls={}, auth={})",
          options.getHost(),
          options.getPort(),
          options.isSecured() ? "enabled" : "disabled",
          authEnabled ? "bearer" : "disabled"
        );
        latch.countDown();
      })
      .onFailure(err -> {
        LOGGER.error(
          "Failed to start HTTP API server on {}:{}: {}",
          options.getHost(),
          options.getPort(),
          err.getMessage()
        );
        latch.countDown();
      });

    latch.await();
    if (!started[0]) {
      throw new IllegalStateException("HTTP API server failed to start");
    }
  }

  @Override
  protected void doStop() throws Exception {
    if (httpServer != null) {
      LOGGER.info("Stopping HTTP API server...");
      var latch = new CountDownLatch(1);
      httpServer
        .getDelegate()
        .close()
        .onComplete(ar -> {
          if (ar.succeeded()) {
            LOGGER.info("HTTP API server stopped");
          } else {
            LOGGER.warn("HTTP API server stop failed: {}", ar.cause().getMessage());
          }
          latch.countDown();
        });
      latch.await();
    }
    if (vertxHttpServer != null) {
      vertxHttpServer.stop();
    }
    // Tracer lifecycle is owned by GrpcServerComponent — do not stop it here.
  }

  /**
   * Opens a {@code SERVER} span per request (continuing any inbound {@code traceparent}) attached to
   * the request's Vert.x context, so the service layer's child spans nest under it.
   */
  private Handler<HttpServerRequest> tracingHandler(Handler<HttpServerRequest> delegate) {
    return request -> {
      final Context ctx = request instanceof
          io.vertx.core.internal.http.HttpServerRequestInternal internal
        ? internal.context()
        : vertx.getDelegate().getOrCreateContext();

      final Span span = tracer.startRootSpanFrom(ctx, new ObservableHttpServerRequest(request));
      span.withAttribute("rpc.system", "http").withAttribute("http.route", request.path());

      final AtomicBoolean ended = new AtomicBoolean();
      request
        .response()
        .endHandler(v -> {
          if (ended.compareAndSet(false, true)) {
            tracer.end(ctx, span);
          }
        });
      request
        .response()
        .exceptionHandler(err -> {
          if (ended.compareAndSet(false, true)) {
            tracer.endOnError(ctx, span, err);
          }
        });
      request.exceptionHandler(err -> {
        if (ended.compareAndSet(false, true)) {
          tracer.endOnError(ctx, span, err);
        }
      });

      delegate.handle(request);
    };
  }

  private BearerTokenAuthHandler buildAuthHandler() {
    String type = environment.getProperty(AUTH_PREFIX + ".type", "bearer");
    if (!"bearer".equalsIgnoreCase(type)) {
      throw new IllegalStateException(
        "Unsupported http.auth.type '" + type + "' (only 'bearer' is supported)"
      );
    }
    List<String> tokens = readTokens(AUTH_PREFIX + ".tokens");
    if (tokens.isEmpty()) {
      throw new IllegalStateException(
        "http.auth.enabled is true but no tokens are configured under " + AUTH_PREFIX + ".tokens"
      );
    }
    LOGGER.info("HTTP API Bearer authentication enabled for {} key(s)", tokens.size());
    return new BearerTokenAuthHandler(tokens);
  }

  /** Reads a YAML list property ({@code prefix[0]}, {@code prefix[1]}, ...) into a list. */
  private List<String> readTokens(String prefix) {
    List<String> tokens = new ArrayList<>();
    for (int i = 0; ; i++) {
      String token = environment.getProperty(prefix + "[" + i + "]");
      if (token == null) {
        break;
      }
      if (!token.isBlank()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static boolean isLoopback(String host) {
    return host == null || host.isBlank() || "localhost".equals(host) || host.startsWith("127.");
  }

  private static void writeError(
    RoutingContext ctx,
    int status,
    String message,
    String type,
    String code
  ) {
    if (ctx.response().ended()) {
      return;
    }
    ctx
      .response()
      .setStatusCode(status)
      .putHeader("content-type", "application/json")
      .end(OpenAiError.json(message, type, null, code));
  }
}
