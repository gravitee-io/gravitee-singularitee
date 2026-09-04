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
package io.gravitee.singularitee.engine.pipeline.executor;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.OpenInference;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepInvocation;
import io.gravitee.singularitee.engine.api.pipeline.executor.TracingOptions;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import java.util.Map;

/**
 * Platform decorator: opens one {@code singularitee.step} span per step (child of the pipeline
 * span), publishes it as the active step span so {@link ModelBoundStepExecutor} parents
 * its model-call span to it, and ends it error-aware on every terminal. No-op when
 * tracing is disabled.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TracingStepDecorator implements StepExecutorDecorator {

  @Override
  public Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next) {
    // Defer so the span opens at subscribe time (when the step actually runs in the
    // recursive walk), bracketing the whole inner chain.
    return Maybe.defer(() -> {
      final Span span = startStepSpan(ctx, step);
      final Throwable[] error = { null };
      return next
        .proceed(step, ctx)
        .doOnError(e -> error[0] = e)
        .doFinally(() -> endStepSpan(ctx, span, error[0]));
    });
  }

  private static Span startStepSpan(StepContext ctx, StepModel step) {
    Tracer tracer = ctx.tracer();
    Context vctx = ctx.callerContext();
    if (tracer == null || vctx == null) {
      return null;
    }
    InternalRequest request = InternalRequest.builder()
      .name(SpanNames.key("step"))
      .attributes(Map.of("step.id", step.id(), "step.type", step.type()))
      .spanKind(SpanKind.INTERNAL)
      .build();
    Span span = ctx.pipelineSpan() != null
      ? tracer.startSpanWithParentFrom(vctx, ctx.pipelineSpan(), request)
      : tracer.startSpanFrom(vctx, request);
    // OpenInference span kind, so the step renders as an LLM/GUARDRAIL/CHAIN/... in trace tools.
    if (TracingOptions.openInference()) {
      span.withAttribute(OpenInference.SPAN_KIND, OpenInference.spanKind(step.type()));
    }
    ctx.activeStepSpan().set(span);
    return span;
  }

  private static void endStepSpan(StepContext ctx, Span span, Throwable error) {
    ctx.activeStepSpan().set(null);
    Tracer tracer = ctx.tracer();
    if (span == null || tracer == null) {
      return;
    }
    if (error != null) {
      tracer.endOnError(ctx.callerContext(), span, error);
    } else {
      tracer.end(ctx.callerContext(), span);
    }
  }
}
