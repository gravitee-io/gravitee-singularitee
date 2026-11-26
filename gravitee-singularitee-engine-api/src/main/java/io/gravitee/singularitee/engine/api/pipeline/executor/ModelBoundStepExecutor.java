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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.engine.api.ModelEngineType;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.tracing.SpanStatus;
import io.gravitee.singularitee.engine.api.tracing.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive template base for step executors that need a model engine.
 *
 * <p>Handles the shared lookup, type-check and skip-on-error pattern so
 * Classify, Embed, Guard, Route and Infer executors do not repeat it.
 * Subclasses only implement the domain logic via {@link #rxExecuteWithEngine}.
 *
 * @param <C> the step's config type
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
    // A step that cannot reach its model fails the request rather than being skipped: a skipped
    // guard would let the request through unchecked.
    var entryOpt = execContext.lookupModel(getModelId(config));
    if (entryOpt.isEmpty()) {
      execContext.logModelNotFound(stepId, getModelId(config));
      return Maybe.error(
        new IllegalStateException(
          "Step '" + stepId + "': model '" + getModelId(config) + "' is not loaded"
        )
      );
    }

    ModelEngine engine = entryOpt.get().engine();
    if (!engineType().isInstance(engine)) {
      execContext.logTypeError(
        stepId,
        getModelId(config),
        engineType().getSimpleName(),
        engine.getClass().getSimpleName()
      );
      return Maybe.error(
        new IllegalStateException(
          "Step '" +
            stepId +
            "': model '" +
            getModelId(config) +
            "' is not a " +
            engineType().getSimpleName()
        )
      );
    }

    final E typed = engineType().cast(engine);
    final String modelId = getModelId(config);
    final String op = modelOp();

    // Deferred so the model span opens at subscribe time and, for streaming infer, stays open
    // until the token stream completes (rxExecuteWithEngine terminates only once the capture
    // stream ends). It ends before the terminal signal reaches the step, so it closes inside
    // its step span. No-op when tracing/metrics are off.
    return Maybe.defer(() -> {
      final Tracer tracer = ctx.tracer();
      final Context vctx = ctx.callerContext();
      final Span stepSpan = ctx.activeStepSpan().get();
      if (stepSpan != null && modelId != null && !modelId.isBlank()) {
        stepSpan.withAttribute("model.id", modelId);
      }
      final Span span = startModelSpan(tracer, vctx, stepSpan, modelId, op, engine.type());
      if (span != null) {
        if (TracingOptions.openInference()) {
          span.withAttribute(OpenInference.SPAN_KIND, engineKind(engine.type()));
        }
        span
          .withAttribute(SpanNames.key("model.engine_type"), engine.type().name())
          .withAttribute(SpanNames.key("model.engine"), engine.getClass().getSimpleName());
      }
      final AtomicReference<Span> modelSpanHolder = ctx.activeModelSpan();
      if (modelSpanHolder != null) {
        modelSpanHolder.set(span);
      }
      final E called = TraceContext.bind(
        engineType(),
        typed,
        TraceContext.headersOf(tracer, vctx, span)
      );
      final var call = new ModelCall(
        tracer,
        vctx,
        span,
        modelSpanHolder,
        ctx.metrics(),
        op,
        modelId
      );
      return rxExecuteWithEngine(stepId, config, called, ctx)
        .doOnEvent((next, error) -> call.end(error, true))
        .doOnDispose(() -> call.end(null, false));
    });
  }

  /** One model call in flight: ends its span and records its duration exactly once. */
  private static final class ModelCall {

    private final Tracer tracer;
    private final Context vctx;
    private final Span span;
    private final AtomicReference<Span> holder;
    private final InferenceMetrics metrics;
    private final String op;
    private final String modelId;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean ended = new AtomicBoolean();

    ModelCall(
      Tracer tracer,
      Context vctx,
      Span span,
      AtomicReference<Span> holder,
      InferenceMetrics metrics,
      String op,
      String modelId
    ) {
      this.tracer = tracer;
      this.vctx = vctx;
      this.span = span;
      this.holder = holder;
      this.metrics = metrics;
      this.op = op;
      this.modelId = modelId;
    }

    /** {@code completed} is false when the caller disposed of the call: no status is set. */
    void end(Throwable error, boolean completed) {
      if (!ended.compareAndSet(false, true)) {
        return;
      }
      if (holder != null) {
        holder.compareAndSet(span, null);
      }
      if (span != null && tracer != null) {
        if (error != null) {
          SpanStatus.endWithError(tracer, vctx, span, error);
        } else {
          if (completed) {
            SpanStatus.ok(span);
          }
          tracer.end(vctx, span);
        }
      }
      if (metrics != null) {
        metrics.recordModelCall(op, modelId, System.nanoTime() - startNanos);
      }
    }
  }

  /** Records a generation's token usage on the in-flight model span; a no-op outside one. */
  protected static void recordTokenUsage(StepContext ctx, long inputTokens, long outputTokens) {
    SpanScribe scribe = ctx.modelScribe();
    if (scribe != null) {
      scribe
        .set(GenAi.USAGE_INPUT_TOKENS, inputTokens)
        .set(GenAi.USAGE_OUTPUT_TOKENS, outputTokens);
    }
  }

  /** The OpenInference span kind for an engine type; a text-gen backend (llama.cpp, vLLM) is LLM. */
  private static String engineKind(ModelEngineType type) {
    return switch (type) {
      case TEXT_GEN -> OpenInference.KIND_LLM;
      case EMBEDDING -> OpenInference.KIND_EMBEDDING;
      case RERANKER -> OpenInference.KIND_RERANKER;
      default -> OpenInference.KIND_CHAIN;
    };
  }

  /** What a model of this type is asked to do: the first word of its span name. */
  static String operation(ModelEngineType type) {
    return switch (type) {
      case TEXT_GEN -> "infer";
      case CLASSIFIER -> "classify";
      case EMBEDDING -> "embed";
      case RERANKER -> "rerank";
      case DECISION -> "decide";
    };
  }

  /**
   * The {@code op} attribute of the model span and the {@code op} tag of {@code ai.model.*}
   * metrics, derived from the executor class name (e.g. {@code InferStepExecutor} gives
   * {@code infer}, {@code GuardStepExecutor} gives {@code guard}). Override for a custom value.
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
    String op,
    ModelEngineType type
  ) {
    if (tracer == null || vctx == null) {
      return null;
    }
    String model = modelId == null ? "" : modelId;
    var attributes = new HashMap<String, String>();
    attributes.put("model.id", model);
    attributes.put("op", op);
    attributes.put(SpanNames.key("model.id"), model);
    attributes.put(SpanNames.key("model.type"), type.name().toLowerCase(Locale.ROOT));
    switch (type) {
      case TEXT_GEN -> {
        attributes.put(GenAi.OPERATION_NAME, GenAi.OPERATION_CHAT);
        attributes.put(GenAi.REQUEST_MODEL, model);
      }
      case EMBEDDING -> {
        attributes.put(GenAi.OPERATION_NAME, GenAi.OPERATION_EMBEDDINGS);
        attributes.put(GenAi.REQUEST_MODEL, model);
      }
      default -> {}
    }
    InternalRequest request = InternalRequest.builder()
      .name((operation(type) + " " + model).strip())
      .attributes(attributes)
      .spanKind(SpanKind.CLIENT)
      .build();
    return parent != null
      ? tracer.startSpanWithParentFrom(vctx, parent, request)
      : tracer.startSpanFrom(vctx, request);
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
