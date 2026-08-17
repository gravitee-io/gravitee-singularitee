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
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.Pipeline;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything a step executor needs from the outside world, packed into one object.
 *
 * <p>Avoids passing 5+ parameters through every {@link StepExecutor#execute} call.
 * Each executor picks what it needs — simple steps ignore the response stream,
 * streaming steps use it.
 *
 * <p>The {@code tracer}/{@code metrics}/{@code pipelineSpan}/{@code activeStepSpan}
 * fields carry the (server-side) OpenTelemetry + Micrometer instrumentation seam.
 * They are all {@code null}/no-op when the pipeline runs without a tracer (client-side
 * {@code ClientPipelineExecutor}, CLI, unit tests) — see the compact constructor.
 *
 * @param pipelineContext the shared scratchpad for inter-step communication
 * @param pipeline        the pipeline definition (for edge lookup)
 * @param response        the gRPC response stream for writing tokens (may be unused)
 * @param callerContext   the Vert.x context of the caller (may be unused)
 * @param tracer          the OpenTelemetry tracer, or {@code null} to disable tracing
 * @param metrics         the inference metrics recorder, or {@code null} to disable metrics
 * @param pipelineSpan    the parent {@code ai.pipeline} span for per-step spans, or {@code null}
 * @param activeStepSpan  holder for the in-flight {@code ai.step} span (parent of model-call spans);
 *                        set by {@link StepDispatcher}, read by {@link ModelBoundStepExecutor}
 * @param currentStep     the step being dispatched, set per step by {@link StepDispatcher} via
 *                        {@link #withStep(PipelineStep)}; {@code null} outside a dispatch
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepContext(
  PipelineContext pipelineContext,
  Pipeline pipeline,
  WriteStream<InferResponse> response,
  Context callerContext,
  Tracer tracer,
  InferenceMetrics metrics,
  Span pipelineSpan,
  AtomicReference<Span> activeStepSpan,
  PipelineStep currentStep
) {
  public StepContext(
    PipelineContext pipelineContext,
    Pipeline pipeline,
    WriteStream<InferResponse> response,
    Context callerContext,
    Tracer tracer,
    InferenceMetrics metrics,
    Span pipelineSpan,
    AtomicReference<Span> activeStepSpan
  ) {
    this(
      pipelineContext,
      pipeline,
      response,
      callerContext,
      tracer,
      metrics,
      pipelineSpan,
      activeStepSpan,
      null
    );
  }

  /**
   * Untraced constructor for pipelines that run without server-side instrumentation
   * (client-side executor, CLI, tests). All tracer/metrics calls become no-ops.
   */
  public StepContext(
    PipelineContext pipelineContext,
    Pipeline pipeline,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    this(
      pipelineContext,
      pipeline,
      response,
      callerContext,
      null,
      null,
      null,
      new AtomicReference<>()
    );
  }

  /** Returns a copy of this context scoped to the given step. */
  public StepContext withStep(PipelineStep step) {
    return new StepContext(
      pipelineContext,
      pipeline,
      response,
      callerContext,
      tracer,
      metrics,
      pipelineSpan,
      activeStepSpan,
      step
    );
  }

  /**
   * Resolves the next step ID from the pipeline edges.
   *
   * @return the next step ID, or {@code null} if this is a terminal step
   */
  public String nextStep(String currentStepId) {
    String next = pipeline.getEdgesMap().get(currentStepId);
    return (next == null || next.isBlank()) ? null : next;
  }

  /**
   * Reactive variant: resolves the next step ID as a {@link Maybe}.
   *
   * <p>Emits the next step ID if an outgoing edge exists, or completes empty
   * for terminal steps. This is the idiomatic RxJava3 way to express
   * "zero or one value" without sentinel strings or nulls.
   *
   * @return a {@link Maybe} emitting the next step ID, or empty for terminal steps
   */
  public Maybe<String> rxNextStep(String currentStepId) {
    String next = pipeline.getEdgesMap().get(currentStepId);
    return (next == null || next.isBlank()) ? Maybe.empty() : Maybe.just(next);
  }
}
