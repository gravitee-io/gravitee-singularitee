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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.node.opentelemetry.tracer.OpenTelemetryTracer;
import io.gravitee.node.opentelemetry.tracer.instrumentation.vertx.VertxHttpInstrumenterTracerFactory;
import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.protocol.ClassifyRequest;
import io.opentelemetry.api.trace.SpanKind;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A gRPC call carrying a W3C {@code traceparent} in its metadata opens its server span inside the
 * caller's trace: the client injects, the traced server handler extracts.
 */
class GrpcServerTracingTest {

  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String PARENT_ID = "b7ad6b7169203331";

  private final List<SpanData> spans = new CopyOnWriteArrayList<>();
  private Vertx vertx;
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
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
    var tracer = new OpenTelemetryTracer(sdk, List.of(new VertxHttpInstrumenterTracerFactory()));
    tracer.start();

    vertx = Vertx.vertx();
    server = vertx
      .createHttpServer()
      .requestHandler(
        GrpcServerComponent.tracingHandler(tracer, vertx::getOrCreateContext, request ->
          request
            .response()
            .putHeader("content-type", "application/grpc")
            .putHeader("grpc-status", "12")
            .end()
        )
      )
      .listen(0, "127.0.0.1")
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stop() {
    vertx.close();
  }

  private void classify(SingulariteeClient client) {
    assertThatThrownBy(() ->
      client
        .classify(ClassifyRequest.newBuilder().setModelId("pii").setText("hi").build())
        .blockingGet()
    ).isNotNull();
  }

  private SpanData serverSpan() throws InterruptedException {
    for (int i = 0; i < 100 && spans.isEmpty(); i++) {
      Thread.sleep(20);
    }
    assertThat(spans).hasSize(1);
    return spans.getFirst();
  }

  @Test
  void a_call_with_a_traceparent_joins_the_callers_trace() throws Exception {
    var client = new SingulariteeClient(vertx, "127.0.0.1", server.actualPort());

    classify(
      client.withMetadata(Map.of("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-01"))
    );

    var span = serverSpan();
    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
    assertThat(span.getParentSpanId()).isEqualTo(PARENT_ID);
  }

  @Test
  void a_call_without_one_starts_its_own_trace() throws Exception {
    classify(new SingulariteeClient(vertx, "127.0.0.1", server.actualPort()));

    var span = serverSpan();
    assertThat(span.getTraceId()).isNotEqualTo(TRACE_ID);
    assertThat(span.getParentSpanContext().isValid()).isFalse();
  }
}
