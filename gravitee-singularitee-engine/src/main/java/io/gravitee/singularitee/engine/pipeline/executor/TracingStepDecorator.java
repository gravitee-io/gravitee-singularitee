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
import io.gravitee.singularitee.engine.api.tracing.SpanStatus;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform decorator: opens one {@code step <id> (<type>)} span per step (child of the pipeline
 * span), publishes it as the active step span so {@link ModelBoundStepExecutor} parents
 * its model-call span to it, and ends it before the next step starts: {@code OK} on success,
 * {@code ERROR} with the exception on failure. A step that halts the run (a guard block) is a
 * deliberate outcome: {@code OK} with {@code <prefix>.step.halted=true}. No-op when tracing is
 * disabled.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TracingStepDecorator implements StepExecutorDecorator {

  @Override
  public Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next) {
    // Defer so the span opens at subscribe time (when the step actually runs in the
    // recursive walk), bracketing the whole inner chain. It ends before the terminal signal
    // travels on, since the walk subscribes the next step from that signal.
    return Maybe.defer(() -> {
      final boolean haltedBefore = isHalted(ctx);
      final Span span = startStepSpan(ctx, step);
      final AtomicBoolean ended = new AtomicBoolean();
      return next
        .proceed(step, ctx)
        .doOnEvent((nextStep, error) -> {
          if (ended.compareAndSet(false, true)) {
            boolean halted = !haltedBefore && isHalted(ctx);
            endStepSpan(ctx, span, error, halted, true);
          }
        })
        .doOnDispose(() -> {
          if (ended.compareAndSet(false, true)) {
            endStepSpan(ctx, span, null, false, false);
          }
        });
    });
  }

  private static boolean isHalted(StepContext ctx) {
    return ctx.pipelineContext() != null && ctx.pipelineContext().isHalted();
  }

  private static Span startStepSpan(StepContext ctx, StepModel step) {
    Tracer tracer = ctx.tracer();
    Context vctx = ctx.callerContext();
    if (tracer == null || vctx == null) {
      return null;
    }
    var attributes = new HashMap<String, String>();
    attributes.put("step.id", step.id());
    attributes.put("step.type", step.type());
    attributes.put(SpanNames.key("step.id"), step.id());
    attributes.put(SpanNames.key("step.type"), step.type());
    if (ctx.pipeline() != null) {
      attributes.put(SpanNames.key("pipeline.id"), ctx.pipeline().id());
    }
    InternalRequest request = InternalRequest.builder()
      .name("step " + step.id() + " (" + step.type() + ")")
      .attributes(attributes)
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

  /** {@code completed} is false when the caller disposed of the step: no status is set. */
  private static void endStepSpan(
    StepContext ctx,
    Span span,
    Throwable error,
    boolean halted,
    boolean completed
  ) {
    Tracer tracer = ctx.tracer();
    if (span == null || tracer == null) {
      return;
    }
    ctx.activeStepSpan().compareAndSet(span, null);
    if (error != null) {
      SpanStatus.endWithError(tracer, ctx.callerContext(), span, error);
      return;
    }
    if (halted) {
      span.withAttribute(SpanNames.key("step.halted"), true);
    }
    if (completed) {
      SpanStatus.ok(span);
    }
    tracer.end(ctx.callerContext(), span);
  }
}
