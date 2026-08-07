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
import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.pipeline.PipelineExecutor;
import io.gravitee.singularitee.pipeline.executor.TokenStreamWriter;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.streams.WriteStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x gRPC service implementation for inference operations.
 *
 * <p>Handles both direct model inference ({@code Infer}) and pipeline DAG execution
 * ({@code InferPipeline}). The pipeline walk is fully reactive — no blocking or
 * {@code Schedulers.io()} wrapping needed.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeInferenceServiceImpl extends GraviteeInferenceServiceGrpcService {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeInferenceServiceImpl.class);

  private final Vertx vertx;
  private final ModelRegistry registry;
  private final io.gravitee.singularitee.pipeline.PipelineExecutor pipelineExecutor;
  private final InferenceMetrics metrics;
  private final ServiceInstrumentation instrumentation;

  public GraviteeInferenceServiceImpl(
    Vertx vertx,
    ModelRegistry registry,
    PipelineExecutor pipelineExecutor,
    Tracer tracer,
    InferenceMetrics metrics
  ) {
    this.vertx = vertx;
    this.registry = registry;
    this.pipelineExecutor = pipelineExecutor;
    this.metrics = metrics;
    this.instrumentation = new ServiceInstrumentation(vertx, tracer, metrics);
  }

  // ---------------------------------------------------------------------------
  // Infer (server-streaming — single model)
  // ---------------------------------------------------------------------------

  @Override
  public void infer(InferRequest request, WriteStream<InferResponse> response) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("Infer: model not found: {}", request.getModelId());
      metrics.recordRequest("infer", request.getModelId(), InferenceMetrics.STATUS_NOT_FOUND);
      response.end();
      return;
    }

    var entry = entryOpt.get();
    if (!(entry.engine() instanceof TextGenEngine tge)) {
      LOGGER.warn("Infer: model {} is not a text-gen engine", request.getModelId());
      metrics.recordRequest("infer", request.getModelId(), InferenceMetrics.STATUS_ERROR);
      response.end();
      return;
    }

    int seqId = entry.nextSequenceId();
    var context = vertx.getOrCreateContext();

    // Subscribe the response to the engine's per-sequence reactive token stream BEFORE
    // submitting, so the processor exists when the first token is produced. The writer
    // drains tokens to the client on the event loop with write-queue backpressure and a
    // bounded buffer (a slow client backs up only its own stream, never the decode loop).
    var stream = TokenStreamWriter.subscribe(
      tge,
      seqId,
      response,
      context,
      request.getRequestId(),
      request.getModelId()
    );

    // rxAddSequence completes when the final token has been delivered, so this measures
    // end-to-end generation latency (ai_infer_latency_seconds), not just enqueue time.
    final long startNanos = System.nanoTime();
    var disposable = tge
      .rxAddSequence(seqId, buildTextGenRequest(request))
      .subscribe(
        () -> {
          metrics.recordRequest("infer", request.getModelId(), InferenceMetrics.STATUS_SUCCESS);
          metrics.recordLatency("infer", request.getModelId(), System.nanoTime() - startNanos);
        },
        e -> {
          metrics.recordRequest("infer", request.getModelId(), InferenceMetrics.STATUS_ERROR);
          metrics.recordLatency("infer", request.getModelId(), System.nanoTime() - startNanos);
          LOGGER.error("Failed to complete inference for seq {}: {}", seqId, e.getMessage());
          // Writes a FAILED event, ends the response, and cancels the token stream.
          stream.fail(e);
        }
      );

    // Client disconnect (Ctrl+C, closed connection → RST_STREAM): stop the
    // generation instead of letting an orphaned sequence burn compute until
    // max_tokens. cancelSequence is idempotent, so a race with normal
    // completion is harmless.
    onClientTermination(response, reason -> {
      LOGGER.warn(
        "Infer: client stream terminated for model '{}' seq {} — cancelling generation: {}",
        request.getModelId(),
        seqId,
        reason
      );
      // Dispose FIRST: doOnDispose performs the engine cancel with the
      // subscriber already detached, so the subject's onComplete() cannot
      // fire the success callback (which would record a spurious "success"
      // metric for a cancelled request). stream.cancel() then detaches the
      // token writer; the explicit cancel is idempotent belt-and-braces.
      disposable.dispose();
      stream.cancel();
      tge.cancelSequence(seqId);
      metrics.recordRequest("infer", request.getModelId(), InferenceMetrics.STATUS_CANCELLED);
    });
  }

  /**
   * Registers a one-shot hook fired when the client side of a streaming
   * response terminates abnormally (cancelled call, dropped connection).
   *
   * <p>vert.x-grpc routes an HTTP/2 {@code RST_STREAM} (the wire form of a
   * gRPC client cancel) to the {@link io.vertx.grpc.common.GrpcWriteStream}
   * {@code errorHandler} as a {@link io.vertx.grpc.common.GrpcError} — NOT to
   * the plain {@link WriteStream#exceptionHandler}. Both are registered here
   * so the hook fires regardless of how the termination surfaces; an atomic
   * guard keeps it one-shot.
   */
  private static void onClientTermination(
    WriteStream<InferResponse> response,
    java.util.function.Consumer<String> action
  ) {
    var fired = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.function.Consumer<String> once = reason -> {
      if (fired.compareAndSet(false, true)) {
        action.accept(reason);
      }
    };
    // errorHandler is only exposed on the implementation base class — there
    // is no public-interface hook for GrpcError in vert.x-grpc 5.0.x.
    if (response instanceof io.vertx.grpc.common.impl.GrpcWriteStreamBase<?, ?> gws) {
      gws.errorHandler(err -> once.accept("grpc error " + err));
    }
    response.exceptionHandler(err -> once.accept(String.valueOf(err.getMessage())));
  }

  // ---------------------------------------------------------------------------
  // InferPipeline (server-streaming — pipeline DAG)
  // ---------------------------------------------------------------------------

  @Override
  public void inferPipeline(InferPipelineRequest request, WriteStream<InferResponse> response) {
    if (pipelineExecutor == null) {
      LOGGER.warn("InferPipeline called but no PipelineExecutor is configured");
      response.end();
      return;
    }
    LOGGER.info(
      "InferPipeline called for pipeline '{}' on thread '{}'",
      request.getPipelineId(),
      Thread.currentThread().getName()
    );
    var callerContext = vertx.getOrCreateContext();
    final long startNanos = System.nanoTime();

    // The pipeline walk is fully reactive — subscribe directly on the caller context.
    // No Schedulers.io() wrapping needed since no step blocks a thread.
    var disposable = pipelineExecutor
      .executePipeline(request, response, callerContext)
      .subscribe(
        () -> {
          metrics.recordPipelineRequest(request.getPipelineId(), InferenceMetrics.STATUS_SUCCESS);
          metrics.recordPipelineLatency(request.getPipelineId(), System.nanoTime() - startNanos);
        },
        e -> {
          metrics.recordPipelineRequest(request.getPipelineId(), InferenceMetrics.STATUS_ERROR);
          metrics.recordPipelineLatency(request.getPipelineId(), System.nanoTime() - startNanos);
          LOGGER.error(
            "Pipeline execution failed for '{}': {}",
            request.getPipelineId(),
            e.getMessage(),
            e
          );
          // Emit a FAILED event before ending the stream.
          var failed = InferResponse.newBuilder()
            .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
            .setResponseFailed(
              ResponseFailed.newBuilder()
                .setErrorCode("server_error")
                .setErrorMessage(e.getMessage() != null ? e.getMessage() : "Pipeline failed")
                .build()
            )
            .build();
          response.end(failed);
        }
      );

    // Client disconnect mid-pipeline: dispose the reactive chain. Disposal
    // propagates down to the running INFER step (its Completable cancellable
    // disposes the engine subscription, whose doOnDispose cancels native
    // generation — or, for remote engines, cancels the upstream gRPC call).
    onClientTermination(response, reason -> {
      LOGGER.warn(
        "InferPipeline: client stream terminated for pipeline '{}' — cancelling execution: {}",
        request.getPipelineId(),
        reason
      );
      disposable.dispose();
      metrics.recordPipelineRequest(request.getPipelineId(), InferenceMetrics.STATUS_CANCELLED);
      metrics.recordPipelineLatency(request.getPipelineId(), System.nanoTime() - startNanos);
    });
  }

  // ---------------------------------------------------------------------------
  // Classify (unary — single classifier model)
  // ---------------------------------------------------------------------------

  @Override
  public Future<io.gravitee.singularitee.protocol.ClassifyResponse> classify(
    io.gravitee.singularitee.protocol.ClassifyRequest request
  ) {
    return instrumentation.traceUnary("ai.classify", "classify", request.getModelId(), () ->
      classifyInternal(request)
    );
  }

  private Future<io.gravitee.singularitee.protocol.ClassifyResponse> classifyInternal(
    io.gravitee.singularitee.protocol.ClassifyRequest request
  ) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("Classify: model not found: {}", request.getModelId());
      return Future.failedFuture("Model not found: " + request.getModelId());
    }

    var entry = entryOpt.get();
    if (!(entry.engine() instanceof ClassifierEngine ce)) {
      LOGGER.warn(
        "Classify: model '{}' is not a ClassifierEngine (got {})",
        request.getModelId(),
        entry.engine().getClass().getSimpleName()
      );
      return Future.failedFuture("Model " + request.getModelId() + " is not a classifier");
    }

    var labels = request
      .getLabelsList()
      .stream()
      .map(l -> new ClassifierEngine.ClassifyLabel(l.getName(), l.getDescription()))
      .toList();

    Promise<io.gravitee.singularitee.protocol.ClassifyResponse> promise = Promise.promise();
    ce
      .rxClassify(new io.gravitee.singularitee.engine.ClassifyRequest(request.getText()), labels)
      .map(result -> {
        var responseBuilder = io.gravitee.singularitee.protocol.ClassifyResponse.newBuilder()
          .setTopLabel(result.topLabel() == null ? "" : result.topLabel())
          .setTopScore(result.topScore());

        if (result.allScores() != null) {
          responseBuilder.putAllAllScores(result.allScores());
        }

        if (result.results() != null) {
          for (var r : result.results()) {
            var rb = io.gravitee.singularitee.protocol.ClassifyResult.newBuilder()
              .setLabel(r.label())
              .setScore(r.score());
            if (r.token() != null) rb.setToken(r.token());
            if (r.start() != null) rb.setStart(r.start());
            if (r.end() != null) rb.setEnd(r.end());
            responseBuilder.addResults(rb.build());
          }
        }

        return responseBuilder.build();
      })
      .subscribe(promise::complete, promise::fail);
    return promise.future();
  }

  // ---------------------------------------------------------------------------
  // ClassifyBatch (unary — batch classification)
  // ---------------------------------------------------------------------------

  @Override
  public Future<io.gravitee.singularitee.protocol.ClassifyBatchResponse> classifyBatch(
    io.gravitee.singularitee.protocol.ClassifyBatchRequest request
  ) {
    return instrumentation.traceUnary("ai.classify.batch", "classify", request.getModelId(), () ->
      classifyBatchInternal(request)
    );
  }

  private Future<io.gravitee.singularitee.protocol.ClassifyBatchResponse> classifyBatchInternal(
    io.gravitee.singularitee.protocol.ClassifyBatchRequest request
  ) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("ClassifyBatch: model not found: {}", request.getModelId());
      return Future.failedFuture("Model not found: " + request.getModelId());
    }

    var entry = entryOpt.get();
    if (!(entry.engine() instanceof ClassifierEngine ce)) {
      LOGGER.warn(
        "ClassifyBatch: model '{}' is not a ClassifierEngine (got {})",
        request.getModelId(),
        entry.engine().getClass().getSimpleName()
      );
      return Future.failedFuture("Model " + request.getModelId() + " is not a classifier");
    }

    var requests = request
      .getTextsList()
      .stream()
      .map(text -> new io.gravitee.singularitee.engine.ClassifyRequest(text))
      .toList();

    var labels = request
      .getLabelsList()
      .stream()
      .map(l -> new ClassifierEngine.ClassifyLabel(l.getName(), l.getDescription()))
      .toList();

    Promise<io.gravitee.singularitee.protocol.ClassifyBatchResponse> promise = Promise.promise();
    ce
      .rxClassifyBatch(requests, labels)
      .map(results -> {
        var batchBuilder = io.gravitee.singularitee.protocol.ClassifyBatchResponse.newBuilder();
        for (var result : results) {
          var responseBuilder = io.gravitee.singularitee.protocol.ClassifyResponse.newBuilder()
            .setTopLabel(result.topLabel() == null ? "" : result.topLabel())
            .setTopScore(result.topScore());

          if (result.allScores() != null) {
            responseBuilder.putAllAllScores(result.allScores());
          }

          if (result.results() != null) {
            for (var r : result.results()) {
              var rb = io.gravitee.singularitee.protocol.ClassifyResult.newBuilder()
                .setLabel(r.label())
                .setScore(r.score());
              if (r.token() != null) rb.setToken(r.token());
              if (r.start() != null) rb.setStart(r.start());
              if (r.end() != null) rb.setEnd(r.end());
              responseBuilder.addResults(rb.build());
            }
          }
          batchBuilder.addResults(responseBuilder.build());
        }
        return batchBuilder.build();
      })
      .subscribe(promise::complete, promise::fail);
    return promise.future();
  }

  // ---------------------------------------------------------------------------
  // Request building
  // ---------------------------------------------------------------------------

  private static TextGenRequest buildTextGenRequest(InferRequest req) {
    String prompt = req.hasPrompt() ? req.getPrompt() : null;
    List<ChatTurn> messages = req.hasMessages() ? toChatTurns(req.getMessages()) : null;

    var sp = req.hasSamplingParams()
      ? req.getSamplingParams()
      : SamplingParams.getDefaultInstance();

    return new TextGenRequest(
      prompt,
      messages,
      sp.getMaxTokens() > 0 ? sp.getMaxTokens() : null,
      sp.getTemperature() >= 0 ? sp.getTemperature() : null,
      sp.getTopP() > 0 ? sp.getTopP() : null,
      sp.getPresencePenalty() != 0 ? sp.getPresencePenalty() : null,
      sp.getFrequencyPenalty() != 0 ? sp.getFrequencyPenalty() : null,
      req.getStopList().isEmpty() ? null : req.getStopList(),
      sp.getSeed() > 0 ? sp.getSeed() : null,
      toTagConfig(req.getReasoningTags()),
      toTagConfig(req.getToolCallTags()),
      req.hasLora() ? req.getLora().getLoraName() : null,
      req.hasLora() ? req.getLora().getLoraPath() : null,
      req.hasTemplateContext()
        ? io.gravitee.singularitee.pipeline.executor.JinjaContextHelper.structToMap(
          req.getTemplateContext()
        )
        : null,
      req.getCacheKey().isEmpty() ? null : req.getCacheKey(),
      sp.getTopLogprobs() > 0 ? sp.getTopLogprobs() : null
    );
  }

  private static List<ChatTurn> toChatTurns(ChatMessageList list) {
    return list
      .getMessagesList()
      .stream()
      .map(m -> {
        var role = switch (m.getRole()) {
          case ROLE_SYSTEM -> ChatRole.SYSTEM;
          case ROLE_ASSISTANT -> ChatRole.ASSISTANT;
          case ROLE_TOOL -> ChatRole.TOOL;
          default -> ChatRole.USER;
        };
        var media = m
          .getMediaList()
          .stream()
          .map(mc -> {
            var mt = toAttachmentType(mc.getMediaType());
            return new MediaAttachment(mt, mc.getData().toStringUtf8());
          })
          .toList();
        var toolCalls = m
          .getToolCallsList()
          .stream()
          .map(tc -> new ChatTurn.ToolCallTurn(tc.getId(), tc.getName(), tc.getArgumentsJson()))
          .toList();
        return new ChatTurn(
          role,
          m.getContent(),
          media,
          toolCalls,
          m.getToolCallId().isEmpty() ? null : m.getToolCallId(),
          m.getName().isEmpty() ? null : m.getName()
        );
      })
      .toList();
  }

  private static MediaAttachmentType toAttachmentType(MediaType proto) {
    return switch (proto) {
      case MEDIA_TYPE_IMAGE_JPEG -> MediaAttachmentType.IMAGE_JPEG;
      case MEDIA_TYPE_IMAGE_PNG -> MediaAttachmentType.IMAGE_PNG;
      case MEDIA_TYPE_IMAGE_GIF -> MediaAttachmentType.IMAGE_GIF;
      case MEDIA_TYPE_IMAGE_BMP -> MediaAttachmentType.IMAGE_BMP;
      case MEDIA_TYPE_AUDIO_WAV -> MediaAttachmentType.AUDIO_WAV;
      default -> MediaAttachmentType.APPLICATION_OCTET_STREAM;
    };
  }

  private static io.gravitee.singularitee.inference.api.textgen.TagConfig toTagConfig(
    TagConfig proto
  ) {
    if (
      proto == null ||
      proto.equals(TagConfig.getDefaultInstance()) ||
      (proto.getOpenTag().isBlank() && proto.getCloseTag().isBlank())
    ) {
      return null;
    }
    return new io.gravitee.singularitee.inference.api.textgen.TagConfig(
      proto.getOpenTag(),
      proto.getCloseTag(),
      proto.getOpenTagAlternativesList()
    );
  }
}
