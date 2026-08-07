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
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import java.util.Locale;
import java.util.Map;

/**
 * Reactive template base for step executors that need a model engine.
 *
 * <p>Handles the repetitive lookup → type-check → skip-on-error pattern
 * that was previously copy-pasted across Classify, Embed, Guard, Route and Infer executors.
 * Subclasses only implement the domain logic via {@link #rxExecuteWithEngine}.
 *
 * @param <C> the protobuf config type (e.g. ClassifyStepConfig)
 * @param <E> the engine type required (e.g. ClassifierEngine)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class ModelBoundStepExecutor<C, E extends ModelEngine> implements StepExecutor<C> {

  protected final StepExecutionContext execContext;

  protected ModelBoundStepExecutor(StepExecutionContext execContext) {
    this.execContext = execContext;
  }

  /** Returns the model ID from the step config. */
  protected abstract String getModelId(C config);

  /** Returns the engine class expected by this executor. */
  protected abstract Class<E> engineType();

  /**
   * The actual domain logic, called only when the model is found and the
   * engine type matches.
   *
   * @param stepId the step identifier
   * @param config the typed step configuration
   * @param engine the validated engine instance (already cast)
   * @param ctx    the step context
   * @return a {@link Maybe} emitting the next step ID, or empty for terminal steps
   */
  protected abstract Maybe<String> rxExecuteWithEngine(
    String stepId,
    C config,
    E engine,
    StepContext ctx
  );

  @Override
  public final Maybe<String> execute(String stepId, C config, StepContext ctx) {
    var entryOpt = execContext.lookupModel(getModelId(config));
    if (entryOpt.isEmpty()) {
      execContext.logModelNotFound(stepId, getModelId(config));
      return ctx.rxNextStep(stepId);
    }

    ModelEngine engine = entryOpt.get().engine();
    if (!engineType().isInstance(engine)) {
      execContext.logTypeError(
        stepId,
        getModelId(config),
        engineType().getSimpleName(),
        engine.getClass().getSimpleName()
      );
      return ctx.rxNextStep(stepId);
    }

    final E typed = engineType().cast(engine);
    final String modelId = getModelId(config);
    final String op = modelOp();

    // Open an ai.model.<op> span (child of the active step span) and time the engine
    // call (ai_model_call_seconds). Deferred so it opens at subscribe time and — for
    // streaming infer — stays open until the token stream completes (rxExecuteWithEngine
    // only terminates once the capture stream ends). No-op when tracing/metrics are off.
    return Maybe.defer(() -> {
      final Tracer tracer = ctx.tracer();
      final Context vctx = ctx.callerContext();
      final Span stepSpan = ctx.activeStepSpan().get();
      if (stepSpan != null && modelId != null && !modelId.isBlank()) {
        stepSpan.withAttribute("model.id", modelId);
      }
      final Span span = startModelSpan(tracer, vctx, stepSpan, modelId, op);
      final Throwable[] error = { null };
      final long startNanos = System.nanoTime();
      return rxExecuteWithEngine(stepId, config, typed, ctx)
        .doOnError(e -> error[0] = e)
        .doFinally(() -> {
          endModelSpan(tracer, vctx, span, error[0]);
          if (ctx.metrics() != null) {
            ctx.metrics().recordModelCall(op, modelId, System.nanoTime() - startNanos);
          }
        });
    });
  }

  /**
   * The {@code op} tag/suffix for {@code ai.model.*} spans and metrics, derived from the
   * executor class name (e.g. {@code InferStepExecutor} → {@code infer},
   * {@code ClassifyStepExecutor} → {@code classify}). Override for a custom value.
   */
  protected String modelOp() {
    String name = getClass().getSimpleName();
    int idx = name.indexOf("StepExecutor");
    String op = (idx > 0 ? name.substring(0, idx) : name).toLowerCase(Locale.ROOT);
    return op.isBlank() ? "model" : op;
  }

  private static Span startModelSpan(
    Tracer tracer,
    Context vctx,
    Span parent,
    String modelId,
    String op
  ) {
    if (tracer == null || vctx == null) {
      return null;
    }
    InternalRequest request = InternalRequest.builder()
      .name("ai.model." + op)
      .attributes(Map.of("model.id", modelId == null ? "" : modelId, "op", op))
      .spanKind(SpanKind.CLIENT)
      .build();
    return parent != null
      ? tracer.startSpanWithParentFrom(vctx, parent, request)
      : tracer.startSpanFrom(vctx, request);
  }

  private static void endModelSpan(Tracer tracer, Context vctx, Span span, Throwable error) {
    if (span == null || tracer == null) {
      return;
    }
    if (error != null) {
      tracer.endOnError(vctx, span, error);
    } else {
      tracer.end(vctx, span);
    }
  }

  // -----------------------------------------------------------------------
  // Convenience helpers available to all model-bound executors
  // -----------------------------------------------------------------------

  /**
   * Reads the input text from context, falling back to the prompt key.
   * Returns {@code null} (and logs) when the field is empty.
   */
  protected String resolveInputText(String stepId, String inputField, StepContext ctx) {
    String field = (inputField == null || inputField.isBlank())
      ? PipelineContext.KEY_PROMPT
      : inputField;
    String value = ctx.pipelineContext().get(field);
    if (value == null || value.isBlank()) {
      execContext.logEmptyField(stepId, field);
      return null;
    }
    return value;
  }

  /** Resolves the output field name, defaulting to {@code <stepId><suffix>}. */
  protected String resolveOutputField(String providedField, String stepId, String suffix) {
    return execContext.getOutputField(providedField, stepId, suffix);
  }
}
