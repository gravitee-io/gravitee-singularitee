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
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.opentelemetry.api.trace.SpanKind;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared OpenTelemetry span + Micrometer metric wrapper for the unary gRPC service methods.
 *
 * <p>The {@link Tracer} is always non-{@code null} (a no-op tracer when tracing is disabled,
 * see {@code OpenTelemetryFactory.createTracer}); the {@link InferenceMetrics} is always
 * non-{@code null} (no-op when the registry is unbound). Child spans created here nest under
 * the gRPC server span attached to the request context by {@code GrpcServerComponent}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class ServiceInstrumentation {

  private final Vertx vertx;
  private final Tracer tracer;
  private final InferenceMetrics metrics;

  ServiceInstrumentation(Vertx vertx, Tracer tracer, InferenceMetrics metrics) {
    this.vertx = vertx;
    this.tracer = tracer;
    this.metrics = metrics;
  }

  InferenceMetrics metrics() {
    return metrics;
  }

  /**
   * Wraps a {@link Future}-returning unary RPC: opens a child span on the request context,
   * records {@code ai_<op>_requests_total} + {@code ai_<op>_latency_seconds}, and ends the
   * span when the future settles (or when {@code work} throws synchronously).
   *
   * @param spanName the span name (e.g. {@code ai.embed})
   * @param op       the metric op tag (e.g. {@code embed})
   * @param model    the model id tag (may be {@code null})
   * @param work     the actual RPC work
   */
  <T> Future<T> traceUnary(String spanName, String op, String model, Supplier<Future<T>> work) {
    Context ctx = vertx.getOrCreateContext();
    Span span = startSpan(ctx, spanName, model);
    long startNanos = System.nanoTime();
    Future<T> future;
    try {
      future = work.get();
    } catch (RuntimeException e) {
      finish(ctx, span, op, model, startNanos, e);
      throw e;
    }
    return future.andThen(ar -> finish(ctx, span, op, model, startNanos, ar.cause()));
  }

  private Span startSpan(Context ctx, String spanName, String model) {
    return tracer.startSpanFrom(
      ctx,
      InternalRequest.builder()
        .name(spanName)
        .attributes(Map.of("model.id", model == null ? "" : model))
        .spanKind(SpanKind.INTERNAL)
        .build()
    );
  }

  private void finish(
    Context ctx,
    Span span,
    String op,
    String model,
    long startNanos,
    Throwable error
  ) {
    if (error != null) {
      tracer.endOnError(ctx, span, error);
    } else {
      tracer.end(ctx, span);
    }
    metrics.recordRequest(
      op,
      model,
      error != null ? InferenceMetrics.STATUS_ERROR : InferenceMetrics.STATUS_SUCCESS
    );
    metrics.recordLatency(op, model, System.nanoTime() - startNanos);
  }
}
