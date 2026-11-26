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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.opentelemetry.tracer.OpenTelemetryTracer;
import io.gravitee.node.opentelemetry.tracer.instrumentation.vertx.VertxHttpInstrumenterTracerFactory;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The HTTP API's request span: probe paths open none, and a traced request ends {@code OK} for a
 * 2xx or 3xx answer, {@code ERROR} for a 5xx and unset for a 4xx.
 */
class HttpApiTracingTest {

  private final List<SpanData> spans = new CopyOnWriteArrayList<>();
  private final HttpClient client = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
  private Vertx vertx;
  private OpenTelemetryTracer tracer;

  @BeforeEach
  void setUp() throws Exception {
    SpanExporter exporter = new SpanExporter() {
      @Override
      public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
      }

      @Override
      public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
      }

      @Override
      public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
      }
    };
    var sdk = OpenTelemetrySdk.builder()
      .setTracerProvider(
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
      )
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .build();
    tracer = new OpenTelemetryTracer(sdk, List.of(new VertxHttpInstrumenterTracerFactory()));
    tracer.start();
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  @Test
  void probe_paths_are_not_traced() throws Exception {
    int port = serve(TracingExclusions.defaults());

    for (String path : List.of("/health", "/healthz", "/ready", "/readiness", "/liveness")) {
      assertThat(get(port, path)).isEqualTo(200);
    }
    assertThat(get(port, "/_node/metrics/prometheus")).isEqualTo(200);
    assertThat(get(port, "/metrics")).isEqualTo(200);
    assertThat(get(port, "/v1/chat/completions")).isEqualTo(200);

    assertThat(awaitSpans(1)).hasSize(1);
    assertThat(spans.getFirst().getAttributes().asMap().toString()).contains(
      "/v1/chat/completions"
    );
  }

  @Test
  void api_calls_are_traced() throws Exception {
    int port = serve(TracingExclusions.defaults());

    for (String path : List.of("/v1/chat/completions", "/v1/responses", "/v1/classify")) {
      assertThat(get(port, path)).isEqualTo(200);
    }

    assertThat(awaitSpans(3)).hasSize(3);
  }

  @Test
  void a_successful_request_ends_ok() throws Exception {
    int port = serve(TracingExclusions.defaults());

    assertThat(get(port, "/status/200")).isEqualTo(200);

    var span = awaitSpans(1).getFirst();
    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void a_redirect_ends_ok() throws Exception {
    int port = serve(TracingExclusions.defaults());

    assertThat(get(port, "/status/302")).isEqualTo(302);

    assertThat(awaitSpans(1).getFirst().getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void a_server_error_ends_error() throws Exception {
    int port = serve(TracingExclusions.defaults());

    assertThat(get(port, "/status/503")).isEqualTo(503);

    var status = awaitSpans(1).getFirst().getStatus();
    assertThat(status.getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(status.getDescription()).contains("503");
  }

  @Test
  void a_client_error_stays_unset() throws Exception {
    int port = serve(TracingExclusions.defaults());

    assertThat(get(port, "/status/404")).isEqualTo(404);

    assertThat(awaitSpans(1).getFirst().getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
  }

  @Test
  void configured_exclusions_replace_the_defaults() throws Exception {
    var environment = environment(Map.of(TracingExclusions.PROPERTY, "/ping, /internal/*"));
    int port = serve(TracingExclusions.from(environment));

    assertThat(get(port, "/ping")).isEqualTo(200);
    assertThat(get(port, "/internal/stats")).isEqualTo(200);
    assertThat(get(port, "/health")).isEqualTo(200);

    assertThat(awaitSpans(1)).hasSize(1);
  }

  @Test
  void exclusions_can_be_a_yaml_list_and_a_blank_value_traces_everything() {
    var listed = environment(
      Map.of(
        TracingExclusions.PROPERTY + "[0]",
        "/ping",
        TracingExclusions.PROPERTY + "[1]",
        "/internal/*"
      )
    );
    var blank = environment(Map.of(TracingExclusions.PROPERTY, ""));

    assertThat(TracingExclusions.from(listed).excludes("/ping")).isTrue();
    assertThat(TracingExclusions.from(listed).excludes("/internal/x")).isTrue();
    assertThat(TracingExclusions.from(listed).excludes("/health")).isFalse();
    assertThat(TracingExclusions.from(blank).excludes("/health")).isFalse();
    assertThat(TracingExclusions.from(environment(Map.of())).excludes("/health")).isTrue();
  }

  private static StandardEnvironment environment(Map<String, Object> properties) {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
    return environment;
  }

  private int serve(TracingExclusions exclusions) throws Exception {
    HttpServer server = vertx
      .createHttpServer()
      .requestHandler(
        HttpApiServerComponent.tracingHandler(
          tracer,
          vertx::getOrCreateContext,
          exclusions,
          request -> {
            String path = request.path();
            int status = path.startsWith("/status/")
              ? Integer.parseInt(path.substring("/status/".length()))
              : 200;
            request.response().setStatusCode(status).end();
          }
        )
      )
      .listen(0, "127.0.0.1")
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);
    return server.actualPort();
  }

  private int get(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private List<SpanData> awaitSpans(int expected) throws InterruptedException {
    for (int i = 0; i < 100 && spans.size() < expected; i++) {
      Thread.sleep(20);
    }
    Thread.sleep(100);
    return spans;
  }
}
