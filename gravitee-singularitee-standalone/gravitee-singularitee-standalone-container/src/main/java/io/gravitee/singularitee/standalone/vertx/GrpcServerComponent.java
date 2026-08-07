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
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpServer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Lifecycle component that starts and stops the Vert.x gRPC server.
 *
 * <p>Uses gravitee-node's {@link VertxServerFactory} to create an HTTP/2 server
 * with full support for TLS certificates (hot-reloadable), SNI, client auth (mTLS),
 * HAProxy PROXY protocol, compression, idle timeout, and all other options
 * configurable under the {@code grpc.*} prefix in {@code gravitee.yml}.
 *
 * <p>This is the last component started by {@code SingulariteeNode}, ensuring all
 * models, pipelines, and services are ready before accepting connections.
 *
 * <h3>Configuration reference ({@code gravitee.yml})</h3>
 * <pre>{@code
 * grpc:
 *   port: 9090                        # Listen port (default: 9090)
 *   host: 0.0.0.0                     # Bind address (default: 0.0.0.0)
 *   alpn: true                        # HTTP/2 ALPN negotiation (default: true for gRPC)
 *   compressionSupported: false       # gRPC compression (default: false)
 *   idleTimeout: 0                    # Idle connection timeout in seconds (default: 0 = no timeout)
 *   tcpKeepAlive: true                # TCP keep-alive (default: true)
 *   secured: false                    # Enable TLS (default: false)
 *   ssl:
 *     sni: false                      # Server Name Indication (default: false)
 *     openssl: false                  # Use OpenSSL engine (default: false)
 *     tlsProtocols: TLSv1.2,TLSv1.3  # Allowed TLS versions
 *     clientAuth: NONE                # Client auth: NONE, REQUEST, REQUIRED (for mTLS)
 *     keystore:
 *       type: JKS                     # JKS, PEM, PKCS12, SELF-SIGNED
 *       path: /path/to/keystore.jks
 *       password: changeit
 *       watch: true                   # Hot-reload on file change
 *     truststore:
 *       type: JKS                     # JKS, PEM, PKCS12, PEM-FOLDER
 *       path: /path/to/truststore.jks
 *       password: changeit
 *   haproxy:
 *     proxyProtocol: false            # HAProxy PROXY protocol support
 *     proxyProtocolTimeout: 10000     # PROXY protocol timeout in ms
 *   auth:
 *     enabled: false                  # Enable HTTP Basic auth on gRPC calls (default: false)
 *     type: basic                     # Authentication scheme (only "basic" is supported)
 *     users:                          # username -> password map
 *       admin: adminadmin
 * }</pre>
 *
 * <p>When {@code grpc.auth.enabled} is {@code true}, callers must send an
 * {@code authorization: Basic base64(username:password)} entry in their gRPC call
 * metadata. Requests without valid credentials are rejected with gRPC
 * {@code UNAUTHENTICATED}. See {@link GrpcBasicAuthHandler}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GrpcServerComponent extends AbstractService<GrpcServerComponent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerComponent.class);

  /** Configuration prefix — all properties are read from {@code grpc.*} in gravitee.yml. */
  public static final String GRPC_PREFIX = "grpc";

  /** Configuration prefix for the optional gRPC authentication block. */
  public static final String AUTH_PREFIX = GRPC_PREFIX + ".auth";

  /** Default gRPC port (different from gravitee-node's HTTP default of 8082). */
  public static final int DEFAULT_GRPC_PORT = 9090;

  private static final String GIO_DARK = "\033[38;5;166m";
  private static final String GIO_ORANGE = "\033[38;5;208m";
  private static final String GIO_LIGHT = "\033[38;5;214m";
  private static final String GIO_GREY = "\033[38;5;239m";
  private static final String GIO_WHITE = "\033[38;5;255m";
  private static final String BOLD = "\033[1m";
  private static final String RESET = "\033[0m";

  private final Environment environment;
  private final Vertx vertx;
  private final VertxServerFactory<
    VertxServer<?, VertxServerOptions>,
    VertxServerOptions
  > serverFactory;
  private final GraviteeModelServiceImpl modelService;
  private final GraviteePipelineServiceImpl pipelineService;
  private final GraviteeInferenceServiceImpl inferenceService;
  private final GraviteeVectorServiceImpl vectorService;
  private final Tracer tracer;
  private final ReadinessState readinessState;

  private VertxHttpServer vertxHttpServer;
  private HttpServer httpServer;

  public GrpcServerComponent(
    Environment environment,
    Vertx vertx,
    VertxServerFactory<VertxServer<?, VertxServerOptions>, VertxServerOptions> serverFactory,
    GraviteeModelServiceImpl modelService,
    GraviteePipelineServiceImpl pipelineService,
    GraviteeInferenceServiceImpl inferenceService,
    GraviteeVectorServiceImpl vectorService,
    Tracer tracer,
    ReadinessState readinessState
  ) {
    this.environment = environment;
    this.vertx = vertx;
    this.serverFactory = serverFactory;
    this.modelService = modelService;
    this.pipelineService = pipelineService;
    this.inferenceService = inferenceService;
    this.vectorService = vectorService;
    this.tracer = tracer;
    this.readinessState = readinessState;
  }

  @Override
  protected void doStart() throws Exception {
    // Start the OpenTelemetry tracer (a no-op tracer when tracing is disabled). This MUST run
    // before the request handler is wired: an unstarted tracer silently emits no-op spans even
    // when tracing is enabled.
    tracer.start();

    // Determine if ALPN should be forced on (gRPC over TLS requires ALPN for h2 negotiation).
    boolean securedInConfig = Boolean.TRUE.equals(
      environment.getProperty(GRPC_PREFIX + ".secured", Boolean.class, false)
    );
    boolean alpnInConfig = Boolean.TRUE.equals(
      environment.getProperty(GRPC_PREFIX + ".alpn", Boolean.class, false)
    );
    boolean forceAlpn = securedInConfig && !alpnInConfig;
    if (forceAlpn) {
      LOGGER.info("gRPC over TLS requires ALPN — enabling automatically");
    }

    // Build server options from gravitee.yml under the "grpc" prefix.
    // This reads port, host, TLS, SNI, mTLS, HAProxy, compression, idle timeout, etc.
    var optionsBuilder = VertxHttpServerOptions.builder()
      .defaultPort(DEFAULT_GRPC_PORT)
      .prefix(GRPC_PREFIX)
      .environment(environment)
      .id("grpc");
    if (forceAlpn) {
      optionsBuilder.alpn(true);
    }
    final var options = optionsBuilder.build();

    // Create the Vert.x HTTP server via gravitee-node's factory.
    // This gives us: TLS with hot-reloadable certs, SNI, mTLS, HAProxy PROXY protocol, etc.
    Object rawServer = serverFactory.create(options);
    if (!(rawServer instanceof VertxHttpServer)) {
      throw new IllegalStateException(
        "Expected VertxHttpServer from factory but got: " + rawServer.getClass().getName()
      );
    }
    this.vertxHttpServer = (VertxHttpServer) rawServer;

    // Create an HTTP/2 server instance with all TLS/cert infrastructure wired
    this.httpServer = vertxHttpServer.newInstance();

    // Create gRPC server and bind all AI services
    GrpcServer grpcServer = GrpcServer.server(vertx.getDelegate());
    modelService.bind(grpcServer);
    pipelineService.bind(grpcServer);
    inferenceService.bind(grpcServer);
    vectorService.bind(grpcServer);

    // Optionally wrap the gRPC server with HTTP Basic authentication.
    final boolean authEnabled = Boolean.TRUE.equals(
      environment.getProperty(AUTH_PREFIX + ".enabled", Boolean.class, false)
    );
    // The tracing decorator sits OUTSIDE the auth handler so rejected/unauthenticated calls
    // are still traced (their gRPC UNAUTHENTICATED response ends the span cleanly).
    final Handler<HttpServerRequest> service = tracingHandler(
      authEnabled ? buildAuthHandler(grpcServer) : grpcServer
    );
    // GET /health → 200 (unauthenticated); service calls → 503 until the workspace has loaded.
    final Handler<HttpServerRequest> requestHandler = healthAndReadinessGate(service);

    // gRPC is the primary API and defaults to 0.0.0.0 with auth and TLS off, so an
    // unguarded bind exposes inference — and every model — to the network. Mirrors the
    // equivalent warning on the HTTP API.
    if (!authEnabled && !isLoopback(options.getHost())) {
      LOGGER.warn(
        "gRPC API is bound to non-loopback host '{}' without authentication — " +
          "set grpc.auth.enabled=true (and grpc.secured=true) before exposing it",
        options.getHost()
      );
    }

    // Attach gRPC handler and start listening
    var latch = new CountDownLatch(1);
    final boolean[] started = { false };
    httpServer
      .getDelegate()
      .requestHandler(requestHandler)
      .listen()
      .onSuccess(s -> {
        started[0] = true;
        printBanner(options, authEnabled);
        latch.countDown();
      })
      .onFailure(err -> {
        LOGGER.error(
          "Failed to start gRPC server on {}:{}: {}",
          options.getHost(),
          options.getPort(),
          err.getMessage()
        );
        latch.countDown();
      });

    latch.await();

    if (!started[0]) {
      throw new IllegalStateException("gRPC server failed to start");
    }
  }

  @Override
  protected void doStop() throws Exception {
    if (httpServer != null) {
      LOGGER.info("Stopping gRPC server...");
      var latch = new CountDownLatch(1);
      httpServer
        .getDelegate()
        .close()
        .onComplete(ar -> {
          if (ar.succeeded()) {
            LOGGER.info("gRPC server stopped");
          } else {
            LOGGER.warn("gRPC server stop failed: {}", ar.cause().getMessage());
          }
          latch.countDown();
        });
      latch.await();
    }
    if (vertxHttpServer != null) {
      vertxHttpServer.stop();
    }
    // Stop the tracer last so it flushes/exports any in-flight spans before the SDK closes.
    if (tracer != null) {
      tracer.stop();
    }
  }

  /**
   * Wraps the gRPC request handler so each HTTP/2 stream (one gRPC call) opens a
   * {@code SERVER}-kind span — continuing any inbound W3C {@code traceparent} — and closes it
   * when the response ends or fails. The span is attached to the request's (duplicated) Vert.x
   * context, which the gRPC service methods run on, so their child spans nest under it.
   *
   * <p>No-op overhead only when tracing is disabled (the tracer is a no-op implementation).
   */
  /**
   * Unauthenticated {@code GET /health} liveness (always {@code 200}) plus a readiness gate: until
   * the workspace finishes loading, service calls get {@code 503} (gRPC clients map this to
   * {@code UNAVAILABLE}). Sits outside auth and tracing so probes are never rejected or traced.
   */
  private Handler<HttpServerRequest> healthAndReadinessGate(Handler<HttpServerRequest> delegate) {
    return request -> {
      if ("/health".equals(request.path())) {
        request.response().setStatusCode(200).putHeader("content-type", "text/plain").end("OK");
        return;
      }
      if (!readinessState.isReady()) {
        request
          .response()
          .setStatusCode(503)
          .putHeader("content-type", "application/grpc")
          .putHeader("grpc-status", "14") // UNAVAILABLE
          .putHeader("grpc-message", "Model server is still loading")
          .end();
        return;
      }
      delegate.handle(request);
    };
  }

  private Handler<HttpServerRequest> tracingHandler(Handler<HttpServerRequest> delegate) {
    return request -> {
      final Context ctx = request instanceof
          io.vertx.core.internal.http.HttpServerRequestInternal internal
        ? internal.context()
        : vertx.getDelegate().getOrCreateContext();

      final Span span = tracer.startRootSpanFrom(ctx, new ObservableHttpServerRequest(request));
      span.withAttribute("rpc.system", "grpc").withAttribute("rpc.method", request.path());

      // Close the span exactly once, on the first terminal event of the stream.
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

  /**
   * Builds the {@link GrpcBasicAuthHandler} from the {@code grpc.auth.*} config.
   * Only the {@code basic} auth type is supported.
   */
  private Handler<HttpServerRequest> buildAuthHandler(GrpcServer grpcServer) {
    String type = environment.getProperty(AUTH_PREFIX + ".type", "basic");
    if (!"basic".equalsIgnoreCase(type)) {
      throw new IllegalStateException(
        "Unsupported grpc.auth.type '" + type + "' (only 'basic' is supported)"
      );
    }
    Map<String, String> users = readUsersMap(AUTH_PREFIX + ".users");
    if (users.isEmpty()) {
      throw new IllegalStateException(
        "grpc.auth.enabled is true but no users are configured under " + AUTH_PREFIX + ".users"
      );
    }
    LOGGER.info("gRPC Basic authentication enabled for {} user(s)", users.size());
    return new GrpcBasicAuthHandler(grpcServer, users);
  }

  /**
   * Reads a {@code username -> password} map from the environment by enumerating
   * all property names under {@code prefix} (e.g. {@code grpc.auth.users.admin}).
   *
   * <p>Three sources are scanned so the map works however it is configured:
   * the node's {@link ConfigurableEnvironment} property sources ({@code gravitee.yml}),
   * JVM system properties ({@code -Dgrpc.auth.users.*}), and {@code GRAVITEE_}-prefixed
   * environment variables ({@code GRAVITEE_GRPC_AUTH_USERS_*}). The node resolves
   * {@code -D}/{@code GRAVITEE_} overrides for scalar lookups but does not expose them
   * as an enumerable source, so they are read directly here.
   */
  private Map<String, String> readUsersMap(String prefix) {
    Map<String, String> users = new LinkedHashMap<>();
    String dotted = prefix + ".";
    if (environment instanceof ConfigurableEnvironment configurable) {
      for (PropertySource<?> source : configurable.getPropertySources()) {
        if (source instanceof EnumerablePropertySource<?> enumerable) {
          for (String name : enumerable.getPropertyNames()) {
            collectUser(users, name, dotted);
          }
        }
      }
    }
    for (String name : System.getProperties().stringPropertyNames()) {
      collectUser(users, name, dotted);
    }
    // GRAVITEE_GRPC_AUTH_USERS_<USER> -> grpc.auth.users.<user>
    String envPrefix = ("gravitee." + dotted).toUpperCase(Locale.ROOT).replace('.', '_');
    for (var entry : System.getenv().entrySet()) {
      if (entry.getKey().startsWith(envPrefix)) {
        String username = entry.getKey().substring(envPrefix.length()).toLowerCase(Locale.ROOT);
        if (!username.isEmpty()) {
          users.putIfAbsent(username, entry.getValue());
        }
      }
    }
    return users;
  }

  /** Adds {@code <prefix>.<username>} as a flat single-level map entry, skipping nested keys. */
  private void collectUser(Map<String, String> users, String propertyName, String dotted) {
    if (!propertyName.startsWith(dotted)) return;
    String username = propertyName.substring(dotted.length());
    if (!username.isEmpty() && username.indexOf('.') < 0) {
      users.computeIfAbsent(username, u -> environment.getProperty(dotted + u));
    }
  }

  /** Loopback binds are development defaults; anything else is reachable off-box. */
  private static boolean isLoopback(String host) {
    return host == null || host.isBlank() || "localhost".equals(host) || host.startsWith("127.");
  }

  private void printBanner(VertxHttpServerOptions options, boolean authEnabled) {
    System.out.println();
    System.out.println(GIO_DARK + BOLD + "       ____                 _ _              " + RESET);
    System.out.println(GIO_DARK + BOLD + "      / ___|_ __ __ ___   _(_) |_ ___  ___   " + RESET);
    System.out.println(
      GIO_ORANGE + BOLD + "     | |  _| '__/ _` \\ \\ / / | __/ _ \\/ _ \\  " + RESET
    );
    System.out.println(
      GIO_ORANGE + BOLD + "     | |_| | | | (_| |\\ V /| | ||  __/  __/  " + RESET
    );
    System.out.println(
      GIO_LIGHT + BOLD + "      \\____|_|  \\__,_| \\_/ |_|\\__\\___|\\___|  " + RESET
    );
    System.out.println(
      GIO_LIGHT +
        BOLD +
        "                                  " +
        RESET +
        GIO_GREY +
        "Singularitee" +
        RESET
    );
    System.out.println();
    System.out.printf(
      "  %sport%s      %s%d%s%n",
      GIO_ORANGE,
      RESET,
      GIO_WHITE,
      options.getPort(),
      RESET
    );
    System.out.printf(
      "  %shost%s      %s%s%s%n",
      GIO_ORANGE,
      RESET,
      GIO_WHITE,
      options.getHost(),
      RESET
    );
    System.out.printf(
      "  %stls%s       %s%s%s%n",
      GIO_ORANGE,
      RESET,
      GIO_WHITE,
      options.isSecured() ? "enabled" : "disabled",
      RESET
    );
    System.out.printf(
      "  %sauth%s      %s%s%s%n",
      GIO_ORANGE,
      RESET,
      GIO_WHITE,
      authEnabled ? "basic" : "disabled",
      RESET
    );
    System.out.printf(
      "  %sruntime%s   %sVert.x gRPC (gravitee-node)%s%n",
      GIO_ORANGE,
      RESET,
      GIO_WHITE,
      RESET
    );
    System.out.printf(
      "  %sstatus%s    %s%s\u2713 ready%s%n",
      GIO_ORANGE,
      RESET,
      GIO_LIGHT,
      BOLD,
      RESET
    );
    System.out.println();
  }
}
