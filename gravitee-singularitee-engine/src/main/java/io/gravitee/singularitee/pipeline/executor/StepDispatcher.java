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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.StepType;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches step execution to the appropriate {@link StepExecutor} handler.
 *
 * <p>Returns a {@link Maybe} emitting the next step ID, allowing the
 * {@link io.gravitee.singularitee.pipeline.PipelineExecutor} to build a fully
 * reactive DAG walk via {@code flatMapCompletable}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class StepDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(StepDispatcher.class);

  private final Map<StepType, StepExecutor<?>> handlers;

  public StepDispatcher(Map<StepType, StepExecutor<?>> handlers) {
    this.handlers = new EnumMap<>(handlers);
  }

  /**
   * Dispatches a pipeline step: extracts its typed config then executes reactively.
   *
   * @param step the pipeline step
   * @param ctx  the step context
   * @return a {@link Maybe} emitting the next step ID, or empty for terminal steps
   */
  public Maybe<String> dispatch(PipelineStep step, StepContext ctx) {
    StepExecutor<?> handler = handlers.get(step.getType());
    if (handler == null) {
      LOGGER.warn(
        "No handler registered for step type {} — skipping '{}'",
        step.getType(),
        step.getStepId()
      );
      // Return the next step from edges so the walk can continue
      return ctx.rxNextStep(step.getStepId());
    }
    // Defer so the per-step span opens at subscribe time (when the step actually
    // runs in the recursive walk), bracketing extractConfig + execute, and closes
    // on every terminal (success, empty, error). No-op when tracing is disabled.
    return Maybe.defer(() -> {
      final Span span = startStepSpan(ctx, step);
      final Throwable[] error = { null };
      return doDispatch(handler, step, ctx)
        .doOnError(e -> error[0] = e)
        .doFinally(() -> endStepSpan(ctx, span, error[0]));
    })
      .doOnError(e ->
        LOGGER.error(
          "Step '{}' (type={}) failed: {}",
          step.getStepId(),
          step.getType(),
          e.getMessage(),
          e
        )
      )
      .onErrorComplete(); // terminal on unhandled error
  }

  /**
   * Opens an {@code ai.step} span (child of the pipeline span) and publishes it as the
   * active step span so {@link ModelBoundStepExecutor} can parent its model-call span to it.
   * Returns {@code null} when tracing is disabled.
   */
  private static Span startStepSpan(StepContext ctx, PipelineStep step) {
    Tracer tracer = ctx.tracer();
    Context vctx = ctx.callerContext();
    if (tracer == null || vctx == null) {
      return null;
    }
    InternalRequest request = InternalRequest.builder()
      .name("ai.step")
      .attributes(Map.of("step.id", step.getStepId(), "step.type", step.getType().name()))
      .spanKind(SpanKind.INTERNAL)
      .build();
    Span span = ctx.pipelineSpan() != null
      ? tracer.startSpanWithParentFrom(vctx, ctx.pipelineSpan(), request)
      : tracer.startSpanFrom(vctx, request);
    ctx.activeStepSpan().set(span);
    return span;
  }

  /** Clears the active step span and ends the step span (error-aware). */
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

  /**
   * Type-safe bridge: extracts config with the concrete type, then calls execute.
   */
  @SuppressWarnings("unchecked")
  private static <C> Maybe<String> doDispatch(
    StepExecutor<C> handler,
    PipelineStep step,
    StepContext outerCtx
  ) {
    StepContext ctx = outerCtx.withStep(step);
    C config = handler.extractConfig(step);
    String stepId = step.getStepId();
    PipelineContext pctx = ctx.pipelineContext();

    LOGGER.info(
      "Dispatching step '{}' to {} with config type {}",
      stepId,
      handler.getClass().getSimpleName(),
      config != null ? config.getClass().getSimpleName() : "null"
    );

    // One-line compact config summary — safe at DEBUG even for big raw_templates
    // (reports size hint rather than dumping the body).
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Step '{}': config {}", stepId, StepConfigDescriber.describe(config));
    }
    // Full protobuf dump — only at TRACE.
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Step '{}': full config {}", stepId, StepConfigDescriber.describeFull(config));
    }

    // Pre-execution context snapshot — see what the step has access to.
    Set<String> fieldsBefore = null;
    int generatedBefore = 0;
    int verdictsBefore = 0;
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Step '{}': pre-execution context\n{}", stepId, pctx.debugSnapshot());
      fieldsBefore = new LinkedHashSet<>(pctx.snapshot().keySet());
      generatedBefore = pctx.generatedMessages().size();
      verdictsBefore = pctx.verdicts().size();
    }
    final Set<String> preFields = fieldsBefore;
    final int preGenerated = generatedBefore;
    final int preVerdicts = verdictsBefore;

    return handler
      .execute(stepId, config, ctx)
      .doOnSuccess(next -> {
        LOGGER.info("Step '{}' completed, nextStep='{}'", stepId, next);
        logPostStep(stepId, pctx, preFields, preGenerated, preVerdicts);
      })
      .doOnComplete(() -> {
        LOGGER.info("Step '{}' completed, terminal (no next step)", stepId);
        logPostStep(stepId, pctx, preFields, preGenerated, preVerdicts);
      });
  }

  /**
   * Emits the post-step context snapshot and a compact delta showing what
   * the step produced (new fields, generated messages, verdicts).
   */
  private static void logPostStep(
    String stepId,
    PipelineContext pctx,
    Set<String> preFields,
    int preGenerated,
    int preVerdicts
  ) {
    if (!LOGGER.isDebugEnabled()) return;

    LOGGER.debug("Step '{}': post-execution context\n{}", stepId, pctx.debugSnapshot());

    // Delta: which context keys appeared, how many generated / verdicts were added.
    if (preFields == null) return;
    Set<String> added = new LinkedHashSet<>(pctx.snapshot().keySet());
    added.removeAll(preFields);
    int addedGenerated = pctx.generatedMessages().size() - preGenerated;
    int addedVerdicts = pctx.verdicts().size() - preVerdicts;

    if (added.isEmpty() && addedGenerated == 0 && addedVerdicts == 0) {
      LOGGER.debug("Step '{}': no context changes", stepId);
      return;
    }
    StringBuilder delta = new StringBuilder();
    if (!added.isEmpty()) delta.append("fields=").append(added);
    if (addedGenerated > 0) {
      if (!delta.isEmpty()) delta.append(", ");
      delta.append("+").append(addedGenerated).append(" generatedMessage(s)");
    }
    if (addedVerdicts > 0) {
      if (!delta.isEmpty()) delta.append(", ");
      delta.append("+").append(addedVerdicts).append(" verdict(s)");
    }
    LOGGER.debug("Step '{}': produced {}", stepId, delta);
  }
}
