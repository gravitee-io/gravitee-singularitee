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

import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.decision.DecisionMessages;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.pipeline.PipelineExecutor;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.protocol.DecideBatchRequest;
import io.gravitee.singularitee.protocol.DecideBatchResponse;
import io.gravitee.singularitee.protocol.DecideBatchState;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.DecisionBlocked;
import io.gravitee.singularitee.protocol.GraviteeDecisionServiceGrpcService;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.TemplatedInput;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC {@code GraviteeDecisionService}: answers typed questions with a decision model, or with a
 * pipeline whose output step is a {@code decide} step.
 *
 * <p>{@link #execute(DecideRequest)} is the transport-neutral entry the HTTP routes share; it
 * fails with {@link DecisionException} or {@link DecisionTargetException}. The gRPC methods map
 * those to status codes.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeDecisionServiceImpl extends GraviteeDecisionServiceGrpcService {

  /** The error code a halting guard ends the pipeline stream with. */
  private static final String BLOCKED = "content_filter";

  private final Vertx vertx;
  private final ModelRegistry registry;
  private final PipelineRegistry pipelines;
  private final PipelineExecutor pipelineExecutor;
  private final ServiceInstrumentation instrumentation;

  /**
   * Wires the service to the registries, the pipeline executor (for pipeline targets), tracer and
   * metrics. A {@code null} executor serves models only.
   */
  public GraviteeDecisionServiceImpl(
    Vertx vertx,
    ModelRegistry registry,
    PipelineRegistry pipelines,
    PipelineExecutor pipelineExecutor,
    Tracer tracer,
    InferenceMetrics metrics
  ) {
    this.vertx = vertx;
    this.registry = registry;
    this.pipelines = pipelines;
    this.pipelineExecutor = pipelineExecutor;
    this.instrumentation = new ServiceInstrumentation(vertx, tracer, metrics);
  }

  @Override
  public Future<DecideResponse> decide(DecideRequest request) {
    return execute(request).recover(e -> Future.failedFuture(toStatus(e)));
  }

  @Override
  public Future<DecideBatchResponse> decideBatch(DecideBatchRequest request) {
    var states = new ArrayList<Future<DecideResponse>>(request.getStatesCount());
    for (DecideBatchState state : request.getStatesList()) {
      var input = TemplatedInput.newBuilder()
        .addAllQuestions(request.getQuestionsList())
        .addAllImages(state.getImagesList());
      switch (state.getStateCase()) {
        case TEXT -> input.setText(state.getText());
        case JSON -> input.setJson(state.getJson());
        case MESSAGES -> input.setMessages(state.getMessages());
        case STATE_NOT_SET -> {}
      }
      var single = DecideRequest.newBuilder()
        .setTemplated(input)
        .setIncludeRawScores(request.getIncludeRawScores());
      switch (request.getTargetCase()) {
        case MODEL_ID -> single.setModelId(request.getModelId());
        case PIPELINE_ID -> single.setPipelineId(request.getPipelineId());
        case TARGET_NOT_SET -> {}
      }
      states.add(execute(single.build()));
    }
    return Future.all(states)
      .map(all ->
        DecideBatchResponse.newBuilder().addAllResults(all.<DecideResponse>list()).build()
      )
      .recover(e -> Future.failedFuture(toStatus(e)));
  }

  /**
   * Answers a request, failing with {@link DecisionException} or
   * {@link DecisionTargetException} rather than a gRPC status.
   */
  public Future<DecideResponse> execute(DecideRequest request) {
    String target = switch (request.getTargetCase()) {
      case MODEL_ID -> request.getModelId();
      case PIPELINE_ID -> request.getPipelineId();
      case TARGET_NOT_SET -> "";
    };
    return instrumentation.traceUnary(SpanNames.key("decide"), "decide", target, () -> {
      try {
        return switch (request.getTargetCase()) {
          case MODEL_ID -> decideWithModel(request);
          case PIPELINE_ID -> decideWithPipeline(request);
          case TARGET_NOT_SET -> Future.failedFuture(
            new DecisionException(Reason.INVALID_REQUEST, "a model_id or a pipeline_id is required")
          );
        };
      } catch (RuntimeException e) {
        return Future.failedFuture(e);
      }
    });
  }

  /** Whether {@code id} names a published pipeline rather than a model. */
  public boolean isPipeline(String id) {
    return registry.get(id).isEmpty() && pipelines != null && pipelines.get(id).isPresent();
  }

  private Future<DecideResponse> decideWithModel(DecideRequest request) {
    String modelId = request.getModelId();
    var entry = registry.get(modelId).orElseThrow(() -> DecisionTargetException.notFound(modelId));
    if (!(entry.engine() instanceof DecisionEngine engine)) {
      throw DecisionTargetException.unsupported(modelId, "is not a decision model");
    }
    DecisionRequest decision = toEngineRequest(request);
    Promise<DecideResponse> promise = Promise.promise();
    engine
      .rxDecide(decision)
      .map(result -> DecisionMessages.response(modelId, result))
      .subscribe(promise::complete, promise::fail);
    return promise.future();
  }

  /**
   * Runs a pipeline whose output step is a {@code decide} step: the state becomes the pipeline's
   * prompt (or messages), the questions travel on the context, and the decide step's answers come
   * back the same way. A guard that halts the pipeline answers with {@code blocked}, not an error.
   */
  private Future<DecideResponse> decideWithPipeline(DecideRequest request) {
    String pipelineId = request.getPipelineId();
    var entry = pipelines == null ? null : pipelines.get(pipelineId).orElse(null);
    if (entry == null) throw DecisionTargetException.notFound(pipelineId);
    // Only a pipeline that answers with a decide step: running a generation pipeline whose
    // decide step routes it would generate text nobody reads.
    if (!ModelTasks.DECISION.equals(entry.pipeline().task())) {
      throw DecisionTargetException.unsupported(
        pipelineId,
        "is not a decision pipeline: its output step is not a decide step"
      );
    }
    if (pipelineExecutor == null) {
      throw DecisionTargetException.unsupported(pipelineId, "cannot run: no pipeline executor");
    }
    DecisionRequest decision = toEngineRequest(request);
    var pipelineRequest = InferPipelineRequest.newBuilder().setPipelineId(pipelineId);
    if (request.hasTemplated()) {
      TemplatedInput input = request.getTemplated();
      switch (input.getStateCase()) {
        case TEXT -> pipelineRequest.setPrompt(input.getText());
        case JSON -> pipelineRequest.setPrompt(DecisionProtoMapper.toJson(input.getJson()));
        case MESSAGES -> pipelineRequest.setMessages(input.getMessages());
        case STATE_NOT_SET -> {}
      }
    }

    var context = new AtomicReference<PipelineContext>();
    var sink = new CollectingResponseStream();
    Promise<DecideResponse> promise = Promise.promise();
    pipelineExecutor
      .executePipeline(pipelineRequest.build(), sink, vertx.getOrCreateContext(), ctx -> {
        ctx.setDecisionRequest(decision);
        context.set(ctx);
      })
      .subscribe(
        () -> {
          try {
            promise.complete(pipelineResponse(pipelineId, sink.last(), context.get()));
          } catch (RuntimeException e) {
            promise.fail(e);
          }
        },
        e -> promise.fail(unwrap(e))
      );
    return promise.future();
  }

  private static DecideResponse pipelineResponse(
    String pipelineId,
    InferResponse last,
    PipelineContext context
  ) {
    if (last != null && last.hasResponseFailed()) {
      var failed = last.getResponseFailed();
      if (BLOCKED.equals(failed.getErrorCode())) {
        return DecideResponse.newBuilder()
          .setModelId(pipelineId)
          .setBlocked(
            DecisionBlocked.newBuilder().setCode(BLOCKED).setMessage(failed.getErrorMessage())
          )
          .build();
      }
      throw new DecisionException(Reason.EVALUATION_FAILED, failed.getErrorMessage());
    }
    if (context != null && context.decisionFailure() != null) {
      throw context.decisionFailure();
    }
    DecisionResult result = context == null ? null : context.decisionResult();
    if (result == null) {
      throw DecisionTargetException.unsupported(
        pipelineId,
        "is not a decision pipeline: no decide step answered"
      );
    }
    return DecisionMessages.response(pipelineId, result);
  }

  /** The decision failure inside a pipeline error, when there is one. */
  private static Throwable unwrap(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof DecisionException || t instanceof DecisionTargetException) return t;
    }
    return e;
  }

  private static DecisionRequest toEngineRequest(DecideRequest request) {
    return DecisionMessages.request(request);
  }

  /** The gRPC status of a failure from {@link #execute}. */
  static Throwable toStatus(Throwable e) {
    return switch (e) {
      case DecisionException d -> new StatusException(
        DecisionProtoMapper.status(d.reason()),
        d.getMessage()
      );
      case DecisionTargetException t -> new StatusException(
        t.notFound() ? GrpcStatus.NOT_FOUND : GrpcStatus.FAILED_PRECONDITION,
        t.getMessage()
      );
      case StatusException s -> s;
      default -> new StatusException(GrpcStatus.INTERNAL, String.valueOf(e.getMessage()));
    };
  }
}
