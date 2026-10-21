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
package io.gravitee.singularitee.plugin.subpipeline;

import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.PipelineExecutorCallback;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.SubPipelineCallbacks;
import io.gravitee.singularitee.engine.api.pipeline.executor.TokenCaptureStream;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.Role;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a SUB_PIPELINE step: invokes another pipeline and captures its output.
 *
 * <p>Supports both local and remote sub-pipelines. Fully reactive, no
 * {@code CountDownLatch} or blocking.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SubPipelineStepExecutor implements StepExecutor<SubPipelineStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SubPipelineStepExecutor.class);

  private final StepExecutionContext execContext;
  private final SubPipelineCallbacks callbacks;

  /**
   * Creates an executor reading its local and remote executors from {@code callbacks},
   * which the server registers once the pipeline executor exists.
   */
  public SubPipelineStepExecutor(StepExecutionContext execContext, SubPipelineCallbacks callbacks) {
    this.execContext = execContext;
    this.callbacks = callbacks;
  }

  /**
   * Accepts the plugin record, and the wire config that the loader still builds directly
   * for a remote pipeline proxy; the latter is converted on the way in.
   */

  @Override
  public Maybe<String> execute(String stepId, SubPipelineStepConfig cfg, StepContext ctx) {
    var pipelineCtx = ctx.pipelineContext();

    LOGGER.info(
      "SubPipelineStep '{}': pipeline_id='{}', remote_id='{}', available_remotes={}",
      stepId,
      cfg.pipelineId(),
      cfg.remoteId(),
      callbacks.remotes().keySet()
    );

    String remoteId = cfg.remoteId();
    // A sub-pipeline that cannot run fails the request rather than being skipped: it may be the
    // guard pipeline, and skipping it would let the request through unchecked.
    PipelineExecutorCallback callback;
    try {
      callback = resolveCallback(stepId, cfg.pipelineId(), remoteId);
    } catch (IllegalStateException unavailable) {
      LOGGER.warn(unavailable.getMessage());
      return Maybe.error(unavailable);
    }

    // Build the sub-pipeline request
    String inputField = cfg.inputField().isBlank() ? PipelineContext.KEY_PROMPT : cfg.inputField();
    String subPrompt = pipelineCtx.get(inputField);

    var reqBuilder = InferPipelineRequest.newBuilder()
      .setPipelineId(cfg.pipelineId())
      .putAllContext(pipelineCtx.snapshot());

    if (!pipelineCtx.tools().isEmpty()) {
      reqBuilder.addAllTools(pipelineCtx.tools());
    }

    boolean hasSystemPrompt = !cfg.systemPrompt().isBlank();

    if (cfg.forwardMessages() && pipelineCtx.messages() != null) {
      var messageList = pipelineCtx.toChatMessageList();
      if (hasSystemPrompt) {
        messageList = prependOrReplaceSystem(messageList, cfg.systemPrompt());
      }
      reqBuilder.setMessages(messageList);
    } else if (hasSystemPrompt) {
      var listBuilder = ChatMessageList.newBuilder()
        .addMessages(
          ChatMessage.newBuilder().setRole(Role.ROLE_SYSTEM).setContent(cfg.systemPrompt()).build()
        )
        .addMessages(
          ChatMessage.newBuilder()
            .setRole(Role.ROLE_USER)
            .setContent(subPrompt != null ? subPrompt : "")
            .build()
        );
      reqBuilder.setMessages(listBuilder.build());
    } else {
      reqBuilder.setPrompt(subPrompt != null ? subPrompt : "");
    }

    var subRequest = reqBuilder.build();
    var accumulator = new StringBuilder();
    var captureStreamRef = new AtomicReference<TokenCaptureStream>();

    return Completable.create(emitter -> {
      var captureStream = TokenCaptureStream.forwardAll(accumulator, emitter, ctx.response());
      captureStreamRef.set(captureStream);
      Disposable d = callback
        .executePipeline(subRequest, captureStream, ctx.callerContext())
        .subscribe(() -> {}, emitter::tryOnError);
      emitter.setCancellable(d::dispose);
    }).andThen(
      Maybe.defer(() -> {
        var lastResp = captureStreamRef.get() != null
          ? captureStreamRef.get().lastResponse()
          : null;
        if (lastResp != null) {
          if (lastResp.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED) {
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
                cfg.pipelineId(),
                fr
              );
              pipelineCtx.signalHalt(
                execContext.getOutputField(cfg.outputField(), stepId, ".output"),
                fr
              );
            }
          } else if (lastResp.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_FAILED) {
            var failed = lastResp.getResponseFailed();
            LOGGER.info(
              "SubPipelineStep '{}': sub-pipeline '{}' failed: {} - {}",
              stepId,
              cfg.pipelineId(),
              failed.getErrorCode(),
              failed.getErrorMessage()
            );
            pipelineCtx.signalHalt(
              execContext.getOutputField(cfg.outputField(), stepId, ".output"),
              FinishReason.FINISH_REASON_GUARD_BLOCKED
            );
            pipelineCtx.setHaltMessage(failed.getErrorMessage());
          }
        }

        String outputField = execContext.getOutputField(cfg.outputField(), stepId, ".output");
        pipelineCtx.set(outputField, accumulator.toString());

        LOGGER.debug(
          "SubPipelineStep '{}': sub-pipeline '{}' completed (remote={}), output in '{}'",
          stepId,
          cfg.pipelineId(),
          !remoteId.isBlank() ? remoteId : "local",
          outputField
        );
        return ctx.rxNextStep(stepId);
      })
    );
  }

  /** The executor for the target, or an {@link IllegalStateException} naming why there is none. */
  private PipelineExecutorCallback resolveCallback(
    String stepId,
    String pipelineId,
    String remoteId
  ) {
    String step = "Step '" + stepId + "': sub-pipeline '" + pipelineId + "' ";
    if (!remoteId.isBlank()) {
      var cb = callbacks.remote(remoteId);
      if (cb == null) {
        throw new IllegalStateException(
          step + "names remote '" + remoteId + "', which is not configured"
        );
      }
      return cb;
    }
    if (execContext.pipelineRegistry().get(pipelineId).isEmpty()) {
      throw new IllegalStateException(step + "is not loaded and no remote_id is set");
    }
    if (callbacks.local() == null) {
      throw new IllegalStateException(step + "is loaded but no local executor is registered");
    }
    return callbacks.local();
  }

  private static ChatMessageList prependOrReplaceSystem(
    ChatMessageList original,
    String systemPrompt
  ) {
    var builder = ChatMessageList.newBuilder();
    builder.addMessages(
      ChatMessage.newBuilder().setRole(Role.ROLE_SYSTEM).setContent(systemPrompt).build()
    );
    for (var msg : original.getMessagesList()) {
      if (msg.getRole() != Role.ROLE_SYSTEM) {
        builder.addMessages(msg);
      }
    }
    return builder.build();
  }
}
