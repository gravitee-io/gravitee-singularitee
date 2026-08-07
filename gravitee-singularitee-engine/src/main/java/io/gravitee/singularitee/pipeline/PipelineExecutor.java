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
package io.gravitee.singularitee.pipeline;

import static java.util.function.Function.identity;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.pipeline.executor.StepContext;
import io.gravitee.singularitee.pipeline.executor.StepDispatcher;
import io.gravitee.singularitee.pipeline.executor.SubPipelineStepExecutor;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a pipeline DAG step-by-step reactively, delegating execution to
 * {@link StepDispatcher}.
 *
 * <p>The DAG walk is implemented as a recursive {@code flatMapCompletable} chain — no
 * {@code CountDownLatch}, no blocking. Each step returns a {@link io.reactivex.rxjava3.core.Single}
 * emitting the next step ID; the walk recurses until the chain terminates.
 *
 * <p>Implements {@link SubPipelineStepExecutor.PipelineExecutorCallback} so it can be
 * used as a local sub-pipeline callback.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class PipelineExecutor implements SubPipelineStepExecutor.PipelineExecutorCallback {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineExecutor.class);

  private final PipelineRegistry pipelineRegistry;
  private final StepDispatcher dispatcher;
  private final Tracer tracer;
  private final InferenceMetrics metrics;

  /** Untraced constructor (client-side executor, CLI, tests). */
  public PipelineExecutor(PipelineRegistry pipelineRegistry, StepDispatcher dispatcher) {
    this(pipelineRegistry, dispatcher, null, null);
  }

  /**
   * @param tracer  the OpenTelemetry tracer, or {@code null} to disable tracing
   * @param metrics the inference metrics recorder, or {@code null} to disable metrics
   */
  public PipelineExecutor(
    PipelineRegistry pipelineRegistry,
    StepDispatcher dispatcher,
    Tracer tracer,
    InferenceMetrics metrics
  ) {
    this.pipelineRegistry = pipelineRegistry;
    this.dispatcher = dispatcher;
    this.tracer = tracer;
    this.metrics = metrics;
  }

  /**
   * Executes the pipeline identified by the request and writes tokens to the response stream.
   * Returns a {@link Completable} that completes when the pipeline finishes.
   */
  @Override
  public Completable executePipeline(
    InferPipelineRequest request,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    var entryOpt = pipelineRegistry.get(request.getPipelineId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("InferPipeline: pipeline not found: {}", request.getPipelineId());
      return Completable.fromAction(() ->
        endWith(null, response, FinishReason.FINISH_REASON_UNSPECIFIED)
      );
    }

    var entry = entryOpt.get();
    Pipeline pipeline = entry.pipeline();
    entry.inFlightCount().incrementAndGet();

    return walk(pipeline, request, response, callerContext).doFinally(
      entry.inFlightCount()::decrementAndGet
    );
  }

  // ---------------------------------------------------------------------------
  // DAG walk — recursive reactive chain
  // ---------------------------------------------------------------------------

  private Completable walk(
    Pipeline pipeline,
    InferPipelineRequest request,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    var context = PipelineContext.fromRequest(request);

    var stepMap = pipeline
      .getStepsList()
      .stream()
      .collect(Collectors.toMap(PipelineStep::getStepId, identity()));

    // Open the ai.pipeline span (child of the gRPC server span on the caller context).
    // Step/model spans nest under it via explicit parenting. No-op when tracing is off.
    final Span pipelineSpan = startPipelineSpan(callerContext, pipeline);
    var stepCtx = new StepContext(
      context,
      pipeline,
      response,
      callerContext,
      tracer,
      metrics,
      pipelineSpan,
      new AtomicReference<>()
    );

    // Emit CREATED event at the start of the pipeline.
    var created = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_CREATED)
      .setResponseCreated(
        ResponseCreated.newBuilder()
          .setResponseId(request.getRequestId() != null ? request.getRequestId() : "")
          .setModel(request.getPipelineId())
          .build()
      )
      .build();
    response.write(created);

    final Throwable[] error = { null };
    return walkStep(pipeline.getEntryStepId(), stepMap, stepCtx, context)
      .andThen(
        Completable.defer(() -> {
          if (context.isHalted()) {
            return Completable.fromAction(() -> emitHaltResponse(context, response));
          }
          FinishReason reason = context.lastEngineFinishReason() != null
            ? context.lastEngineFinishReason()
            : FinishReason.FINISH_REASON_STOP;
          return Completable.fromAction(() -> endWith(context, response, reason));
        })
      )
      .doOnError(e -> error[0] = e)
      .doFinally(() -> endPipelineSpan(callerContext, pipelineSpan, error[0]));
  }

  /** Opens the {@code ai.pipeline} span on the caller context, or returns {@code null}. */
  private Span startPipelineSpan(Context callerContext, Pipeline pipeline) {
    if (tracer == null || callerContext == null) {
      return null;
    }
    InternalRequest request = InternalRequest.builder()
      .name("ai.pipeline")
      .attributes(Map.of("pipeline.id", pipeline.getPipelineId()))
      .spanKind(SpanKind.INTERNAL)
      .build();
    return tracer.startSpanFrom(callerContext, request);
  }

  private void endPipelineSpan(Context callerContext, Span span, Throwable error) {
    if (span == null || tracer == null) {
      return;
    }
    if (error != null) {
      tracer.endOnError(callerContext, span, error);
    } else {
      tracer.end(callerContext, span);
    }
  }

  private Completable walkStep(
    String stepId,
    Map<String, PipelineStep> stepMap,
    StepContext stepCtx,
    PipelineContext context
  ) {
    if (stepId == null || stepId.isBlank() || context.isHalted()) {
      return Completable.complete();
    }

    PipelineStep step = stepMap.get(stepId);
    if (step == null) {
      LOGGER.warn(
        "Pipeline '{}': step '{}' not found — halting",
        stepCtx.pipeline().getPipelineId(),
        stepId
      );
      return Completable.complete();
    }

    LOGGER.info(
      "Pipeline '{}': executing step '{}' (type={}{})",
      stepCtx.pipeline().getPipelineId(),
      step.getStepId(),
      step.getType(),
      step.getType() == StepType.STEP_TYPE_INFER ? ", role=" + step.getRole() : ""
    );

    return dispatcher
      .dispatch(step, stepCtx)
      .flatMapCompletable(nextStepId -> {
        if (context.isHalted()) return Completable.complete();
        return walkStep(nextStepId, stepMap, stepCtx, context);
      });
  }

  // ---------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------

  private static void emitHaltResponse(
    PipelineContext context,
    WriteStream<InferResponse> response
  ) {
    FinishReason reason = context.haltReason() != null
      ? context.haltReason()
      : FinishReason.FINISH_REASON_STOP;

    if (reason == FinishReason.FINISH_REASON_GUARD_BLOCKED) {
      LOGGER.info(
        "Pipeline halted due to guard block — reason={}, output_field={}",
        reason,
        context.breakOutputField()
      );
      // Emit FAILED event for guard blocks.
      var failed = InferResponse.newBuilder()
        .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
        .setResponseFailed(
          ResponseFailed.newBuilder()
            .setErrorCode("content_filter")
            .setErrorMessage(
              context.haltMessage() != null ? context.haltMessage() : "Guard blocked"
            )
            .build()
        )
        .build();
      response.end(failed);
    } else {
      LOGGER.debug(
        "Pipeline halted — reason={}, output_field={}",
        reason,
        context.breakOutputField()
      );
      endWith(context, response, reason);
    }
  }

  private static void endWith(
    PipelineContext context,
    WriteStream<InferResponse> response,
    FinishReason reason
  ) {
    var completedBuilder = ResponseCompleted.newBuilder().setFinishReason(reason);
    if (context != null) {
      completedBuilder
        .setUsage(context.buildTotalUsage())
        .setPerformance(context.buildTotalPerformance())
        .addAllToolCalls(context.extractedToolCalls());
    }
    var completed = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)
      .setResponseCompleted(completedBuilder.build())
      .build();
    response.end(completed);
  }
}
