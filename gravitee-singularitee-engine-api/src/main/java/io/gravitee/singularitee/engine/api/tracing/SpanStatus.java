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
package io.gravitee.singularitee.engine.api.tracing;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.opentelemetry.tracer.span.OpenTelemetrySpan;
import io.opentelemetry.api.trace.StatusCode;
import io.vertx.core.Context;

/**
 * Span status beyond what the gravitee-node {@link Span} sets: {@code OK} on success, and on failure
 * {@code ERROR} with the exception as attributes. The exception is not a span event: some exporters
 * (Elasticsearch) store span events as separate documents that trace views draw as unnamed spans.
 */
public final class SpanStatus {

  private static final boolean SDK_PRESENT = sdkPresent();

  private SpanStatus() {}

  /**
   * Records {@code error} on {@code span} as {@code exception.type} and {@code exception.message},
   * and ends it {@code ERROR} with the message as the status description.
   */
  public static void endWithError(Tracer tracer, Context vertxContext, Span span, Throwable error) {
    if (tracer == null || span == null) {
      return;
    }
    String message = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    span
      .withAttribute("exception.type", error.getClass().getName())
      .withAttribute("exception.message", message);
    tracer.endOnError(vertxContext, span, message);
  }

  /** Marks {@code span} {@code OK}; call it just before ending the span. */
  public static void ok(Span span) {
    if (span != null && SDK_PRESENT) {
      markOk(span);
    }
  }

  private static void markOk(Span span) {
    if (span instanceof OpenTelemetrySpan<?> otel) {
      otel.span().setStatus(StatusCode.OK);
    }
  }

  private static boolean sdkPresent() {
    try {
      Class.forName(
        "io.gravitee.node.opentelemetry.tracer.span.OpenTelemetrySpan",
        false,
        SpanStatus.class.getClassLoader()
      );
      return true;
    } catch (ClassNotFoundException | LinkageError absent) {
      return false;
    }
  }
}
