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
package io.gravitee.singularitee.service;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.node.opentelemetry.tracer.OpenTelemetryTracer;
import io.gravitee.node.opentelemetry.tracer.instrumentation.internal.InternalInstrumenterTracerFactory;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The gravitee-node tracer over the OpenTelemetry SDK, keeping every ended span in memory. */
final class RecordingTracer implements AutoCloseable {

  private final List<SpanData> spans = new CopyOnWriteArrayList<>();
  private final OpenTelemetryTracer tracer;
  private final Vertx vertx = Vertx.vertx();
  private final Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

  RecordingTracer() throws Exception {
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
    tracer = new OpenTelemetryTracer(sdk, List.of(new InternalInstrumenterTracerFactory()));
    tracer.start();
  }

  Tracer tracer() {
    return tracer;
  }

  Vertx vertx() {
    return vertx;
  }

  /** A duplicated Vert.x context, the kind a request runs on. */
  Context context() {
    return context;
  }

  Span start(String name) {
    return tracer.startSpanFrom(context, new InternalRequest(name, Map.of()));
  }

  void end(Span span) {
    tracer.end(context, span);
  }

  List<SpanData> spans() {
    return List.copyOf(spans);
  }

  SpanData span(String name) {
    return spans
      .stream()
      .filter(s -> s.getName().equals(name))
      .findFirst()
      .orElseThrow(() ->
        new AssertionError(
          "no span named '" + name + "' in " + spans.stream().map(SpanData::getName).toList()
        )
      );
  }

  @Override
  public void close() {
    vertx.close();
  }
}
