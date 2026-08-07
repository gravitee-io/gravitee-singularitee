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

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.SubPipelineStepConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a SUB_PIPELINE step: invokes another pipeline and captures its output.
 *
 * <p>Supports both local and remote sub-pipelines. Fully reactive — no
 * {@code CountDownLatch} or blocking.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SubPipelineStepExecutor implements StepExecutor<SubPipelineStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SubPipelineStepExecutor.class);

  private final StepExecutionContext execContext;
  private PipelineExecutorCallback localCallback;
  private Map<String, PipelineExecutorCallback> remoteCallbacks;

  /**
   * Callback to execute a pipeline (local or remote) reactively.
   * Returns a {@link Completable} that completes when the sub-pipeline finishes.
   */
  public interface PipelineExecutorCallback {
    Completable executePipeline(
      InferPipelineRequest request,
      WriteStream<InferResponse> response,
      Context callerContext
    );
  }

  public SubPipelineStepExecutor(
    StepExecutionContext execContext,
    PipelineExecutorCallback localCallback,
    Map<String, PipelineExecutorCallback> remoteCallbacks
  ) {
    this.execContext = execContext;
    this.localCallback = localCallback;
    this.remoteCallbacks = remoteCallbacks != null ? remoteCallbacks : Map.of();
  }

  /**
   * Sets the callbacks after construction (breaks circular dependency).
   */
  public void setCallbacks(
    PipelineExecutorCallback localCallback,
    Map<String, PipelineExecutorCallback> remoteCallbacks
  ) {
    this.localCallback = localCallback;
    this.remoteCallbacks = remoteCallbacks != null ? remoteCallbacks : Map.of();
  }

  @Override
  public SubPipelineStepConfig extractConfig(PipelineStep step) {
    return step.getSubPipeline();
  }

  @Override
  public Maybe<String> execute(String stepId, SubPipelineStepConfig cfg, StepContext ctx) {
    var pipelineCtx = ctx.pipelineContext();

    LOGGER.info(
      "SubPipelineStep '{}': pipeline_id='{}', remote_id='{}', available_remotes={}",
      stepId,
      cfg.getPipelineId(),
      cfg.getRemoteId(),
      remoteCallbacks.keySet()
    );

    String remoteId = cfg.getRemoteId();
    PipelineExecutorCallback callback = resolveCallback(stepId, cfg.getPipelineId(), remoteId);
    if (callback == null) {
      return ctx.rxNextStep(stepId);
    }

    // Build the sub-pipeline request
    String inputField = cfg.getInputField().isBlank()
      ? PipelineContext.KEY_PROMPT
      : cfg.getInputField();
    String subPrompt = pipelineCtx.get(inputField);

    var reqBuilder = InferPipelineRequest.newBuilder()
      .setPipelineId(cfg.getPipelineId())
      .putAllContext(pipelineCtx.snapshot());

    if (!pipelineCtx.tools().isEmpty()) {
      reqBuilder.addAllTools(pipelineCtx.tools());
    }

    boolean hasSystemPrompt = !cfg.getSystemPrompt().isBlank();

    if (cfg.getForwardMessages() && pipelineCtx.messages() != null) {
      var messageList = pipelineCtx.toChatMessageList();
      if (hasSystemPrompt) {
        messageList = prependOrReplaceSystem(messageList, cfg.getSystemPrompt());
      }
      reqBuilder.setMessages(messageList);
    } else if (hasSystemPrompt) {
      var listBuilder = ChatMessageList.newBuilder()
        .addMessages(
          ChatMessage.newBuilder()
            .setRole(io.gravitee.singularitee.protocol.Role.ROLE_SYSTEM)
            .setContent(cfg.getSystemPrompt())
            .build()
        )
        .addMessages(
          ChatMessage.newBuilder()
            .setRole(io.gravitee.singularitee.protocol.Role.ROLE_USER)
            .setContent(subPrompt != null ? subPrompt : "")
            .build()
        );
      reqBuilder.setMessages(listBuilder.build());
    } else {
      reqBuilder.setPrompt(subPrompt != null ? subPrompt : "");
    }

    var subRequest = reqBuilder.build();
    var accumulator = new StringBuilder();
    var captureStreamHolder = new TokenCaptureStream[1];

    return Completable.create(emitter -> {
      var captureStream = TokenCaptureStream.forwardAll(accumulator, emitter, ctx.response());
      captureStreamHolder[0] = captureStream;
      Disposable d = callback
        .executePipeline(subRequest, captureStream, ctx.callerContext())
        .subscribe(() -> {}, emitter::tryOnError);
      emitter.setCancellable(d::dispose);
    }).andThen(
      Maybe.defer(() -> {
        var lastResp = captureStreamHolder[0] != null
          ? captureStreamHolder[0].lastResponse()
          : null;
        if (lastResp != null) {
          if (
            lastResp.getEventType() ==
            io.gravitee.singularitee.protocol.ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED
          ) {
            var completed = lastResp.getResponseCompleted();
            pipelineCtx.accumulateUsage(
              completed.hasUsage() ? completed.getUsage() : null,
              completed.hasPerformance() ? completed.getPerformance() : null
            );

            var fr = completed.getFinishReason();
            if (
              fr != null &&
              fr != FinishReason.FINISH_REASON_STOP &&
              fr != FinishReason.FINISH_REASON_UNSPECIFIED
            ) {
              LOGGER.info(
                "SubPipelineStep '{}': sub-pipeline '{}' halted with reason {}, propagating to parent",
                stepId,
                cfg.getPipelineId(),
                fr
              );
              pipelineCtx.signalHalt(
                execContext.getOutputField(cfg.getOutputField(), stepId, ".output"),
                fr
              );
            }
          } else if (
            lastResp.getEventType() ==
            io.gravitee.singularitee.protocol.ResponseEventType.RESPONSE_EVENT_TYPE_FAILED
          ) {
            var failed = lastResp.getResponseFailed();
            LOGGER.info(
              "SubPipelineStep '{}': sub-pipeline '{}' failed: {} - {}",
              stepId,
              cfg.getPipelineId(),
              failed.getErrorCode(),
              failed.getErrorMessage()
            );
            pipelineCtx.signalHalt(
              execContext.getOutputField(cfg.getOutputField(), stepId, ".output"),
              FinishReason.FINISH_REASON_GUARD_BLOCKED
            );
            pipelineCtx.setHaltMessage(failed.getErrorMessage());
          }
        }

        String outputField = execContext.getOutputField(cfg.getOutputField(), stepId, ".output");
        pipelineCtx.set(outputField, accumulator.toString());

        LOGGER.debug(
          "SubPipelineStep '{}': sub-pipeline '{}' completed (remote={}), output in '{}'",
          stepId,
          cfg.getPipelineId(),
          !remoteId.isBlank() ? remoteId : "local",
          outputField
        );
        return ctx.rxNextStep(stepId);
      })
    );
  }

  private PipelineExecutorCallback resolveCallback(
    String stepId,
    String pipelineId,
    String remoteId
  ) {
    if (remoteId != null && !remoteId.isBlank()) {
      var cb = remoteCallbacks.get(remoteId);
      if (cb == null) {
        LOGGER.warn(
          "SubPipelineStep '{}': remote '{}' not configured — skipping",
          stepId,
          remoteId
        );
        return null;
      }
      return cb;
    }

    if (execContext.pipelineRegistry().get(pipelineId).isPresent()) {
      if (localCallback == null) {
        LOGGER.warn(
          "SubPipelineStep '{}': local pipeline '{}' found but no local callback — skipping",
          stepId,
          pipelineId
        );
        return null;
      }
      return localCallback;
    }

    LOGGER.warn(
      "SubPipelineStep '{}': pipeline '{}' not found locally and no remote_id set — skipping",
      stepId,
      pipelineId
    );
    return null;
  }

  private static ChatMessageList prependOrReplaceSystem(
    ChatMessageList original,
    String systemPrompt
  ) {
    var builder = ChatMessageList.newBuilder();
    builder.addMessages(
      ChatMessage.newBuilder()
        .setRole(io.gravitee.singularitee.protocol.Role.ROLE_SYSTEM)
        .setContent(systemPrompt)
        .build()
    );
    for (var msg : original.getMessagesList()) {
      if (msg.getRole() != io.gravitee.singularitee.protocol.Role.ROLE_SYSTEM) {
        builder.addMessages(msg);
      }
    }
    return builder.build();
  }
}
