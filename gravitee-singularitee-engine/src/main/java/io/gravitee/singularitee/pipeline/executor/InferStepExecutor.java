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
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.registry.ModelRegistry.ModelEntry;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes an INFER step: generates text using a language model and streams tokens.
 *
 * <p>Orchestrates three collaborators: {@link PromptAssembler} resolves the
 * messages and renders the prompt, {@link TextGenRequestFactory} builds the
 * engine request, and {@link ToolCallOutcomeRecorder} publishes the tool-call
 * outcome after generation. This class owns the streaming lifecycle and the
 * post-generation context updates (output fields, conversation append, usage).
 *
 * <p>Fully reactive — no blocking. The step returns a {@link Maybe} that chains on
 * the {@link Completable} from {@link TextGenEngine#rxAddSequence}, completing only
 * after the final token has been delivered to the capture stream.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class InferStepExecutor
  extends ModelBoundStepExecutor<InferStepConfig, TextGenEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(InferStepExecutor.class);

  private final PromptAssembler promptAssembler;

  public InferStepExecutor(StepExecutionContext execContext, JinjaRenderer jinjaRenderer) {
    super(execContext);
    this.promptAssembler = new PromptAssembler(jinjaRenderer, new Jinja4jChatTemplateRenderer());
  }

  @Override
  public InferStepConfig extractConfig(PipelineStep step) {
    return step.getInferConfig();
  }

  @Override
  protected String getModelId(InferStepConfig config) {
    return config.getModelId();
  }

  @Override
  protected Class<TextGenEngine> engineType() {
    return TextGenEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    InferStepConfig cfg,
    TextGenEngine tge,
    StepContext ctx
  ) {
    var pctx = ctx.pipelineContext();
    // Narration is per-step: whatever an earlier internal step stashed is
    // stale the moment another generation starts.
    pctx.setPendingNarration(null);

    var requestOverrides = pctx.requestSamplingParams();
    var retryOverrides = pctx.retrySamplingParams();
    int maxTokens = TextGenRequestFactory.resolveMaxTokens(cfg, requestOverrides, retryOverrides);

    var prompt = promptAssembler.assemble(stepId, cfg, tge, pctx, maxTokens);
    if (LOGGER.isTraceEnabled() && prompt.renderedPrompt() != null) {
      LOGGER.trace(
        "InferStep '{}': rendered prompt ({} chars) ->\n{}",
        stepId,
        prompt.renderedPrompt().length(),
        prompt.renderedPrompt()
      );
    }

    var textGenReq = TextGenRequestFactory.create(
      cfg,
      prompt.renderedPrompt(),
      prompt.wireMessages(),
      requestOverrides,
      retryOverrides,
      maxTokens,
      pctx.cacheKey(),
      pctx.get(PipelineContext.KEY_REASONING_EFFORT)
    );

    StepRole role = ctx.currentStep() != null
      ? ctx.currentStep().getRole()
      : StepRole.STEP_ROLE_UNSPECIFIED;
    var captureConfig = captureConfig(cfg, role);

    var accumulator = new StringBuilder();
    var captureStreamRef = new AtomicReference<TokenCaptureStream>();

    return streamGeneration(
      stepId,
      cfg,
      tge,
      ctx,
      textGenReq,
      accumulator,
      captureConfig,
      captureStreamRef
    ).andThen(
      Maybe.defer(() ->
        finalizeStep(stepId, cfg, ctx, role, captureConfig, accumulator, captureStreamRef.get())
      )
    );
  }

  /**
   * Derives the capture behavior for this step: whether tokens stream to the
   * client, how reasoning is handled (strip_thinking keeps the legacy
   * behavior — reasoning removed from both the step output and the wire;
   * otherwise reasoning is ROUTEd on a separate STEP_ROLE_THINKING flux while
   * the step output keeps the raw text), the reasoning tag pair, and the
   * tool-open markers that cut the forwarded thinking flux.
   */
  private static TokenCaptureStream.CaptureConfig captureConfig(
    InferStepConfig cfg,
    StepRole role
  ) {
    boolean shouldStream = role != StepRole.STEP_ROLE_INTERNAL;
    // stream_thinking: an internal step may still surface its reasoning live.
    boolean forwardThinking = shouldStream || (cfg.hasStreamThinking() && cfg.getStreamThinking());
    StepRole wireRole = (role == StepRole.STEP_ROLE_THINKING)
      ? StepRole.STEP_ROLE_THINKING
      : StepRole.STEP_ROLE_OUTPUT;
    var thinkingMode = (cfg.hasStripThinking() && cfg.getStripThinking())
      ? TokenCaptureStream.ThinkingMode.STRIP
      : TokenCaptureStream.ThinkingMode.ROUTE;
    String thinkOpenTag = null;
    String thinkCloseTag = null;
    if (cfg.hasReasoningTags()) {
      var rt = cfg.getReasoningTags();
      if (!rt.getOpenTag().isBlank()) thinkOpenTag = rt.getOpenTag();
      if (!rt.getCloseTag().isBlank()) thinkCloseTag = rt.getCloseTag();
    }
    List<String> cutMarkers = null;
    if (cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank()) {
      cutMarkers = new ArrayList<>();
      cutMarkers.add(cfg.getToolCallTags().getOpenTag());
      cutMarkers.addAll(cfg.getToolCallTags().getOpenTagAlternativesList());
    }
    return new TokenCaptureStream.CaptureConfig(
      shouldStream,
      forwardThinking,
      wireRole,
      thinkingMode,
      thinkOpenTag,
      thinkCloseTag,
      cutMarkers
    );
  }

  /**
   * Streams the generation: creates the capture stream, subscribes the
   * engine's per-sequence reactive surface into it, and submits the sequence.
   * Completes when the final token has been delivered to the capture stream.
   */
  private Completable streamGeneration(
    String stepId,
    InferStepConfig cfg,
    TextGenEngine tge,
    StepContext ctx,
    TextGenRequest textGenReq,
    StringBuilder accumulator,
    TokenCaptureStream.CaptureConfig captureConfig,
    AtomicReference<TokenCaptureStream> captureStreamRef
  ) {
    int stepSeqId = execContext
      .lookupModel(cfg.getModelId())
      .map(ModelEntry::nextSequenceId)
      .orElse(0);

    return Completable.create(emitter -> {
      var captureStream = new TokenCaptureStream(
        accumulator,
        emitter,
        ctx.response(),
        captureConfig
      );
      captureStreamRef.set(captureStream);

      // Stream tokens into the capture stream via the engine's per-sequence reactive
      // surface: writes land on the caller's event loop and propagate the client's
      // write-queue backpressure (TokenCaptureStream delegates writeQueueFull downstream).
      var handle = TokenStreamWriter.subscribe(
        tge,
        stepSeqId,
        captureStream,
        ctx.callerContext(),
        "",
        cfg.getModelId()
      );

      LOGGER.info(
        "InferStep '{}': calling rxAddSequence(seqId={}) on model '{}'",
        stepId,
        stepSeqId,
        cfg.getModelId()
      );

      Completable addSeq = tge.rxAddSequence(stepSeqId, textGenReq);
      Runnable submit = () -> {
        Disposable d = addSeq.subscribe(
          () -> {},
          err -> {
            handle.cancel();
            emitter.tryOnError(err);
          }
        );
        emitter.setCancellable(() -> {
          handle.cancel();
          d.dispose();
        });
      };

      if (ctx.callerContext() != null) {
        ctx.callerContext().runOnContext(v -> submit.run());
      } else {
        submit.run();
      }
    });
  }

  /**
   * Post-generation phase: publishes the step output, appends the assistant
   * turn (or stashes narration for internal steps), records usage and finish
   * reason, and delegates the tool-call outcome to
   * {@link ToolCallOutcomeRecorder}.
   */
  private Maybe<String> finalizeStep(
    String stepId,
    InferStepConfig cfg,
    StepContext ctx,
    StepRole role,
    TokenCaptureStream.CaptureConfig captureConfig,
    StringBuilder accumulator,
    TokenCaptureStream captureStream
  ) {
    var pctx = ctx.pipelineContext();
    // Engines that classify tool tokens suppress the tag markers and deliver
    // the bare payload on the TOOL channel (captured separately). Re-wrap it
    // with the step's configured tool tags so the step output — and the
    // assistant turn appended below — stays byte-compatible with the legacy
    // tagged text; chat templates and downstream tool parsing re-render
    // prior tool calls from that tagged block on later turns.
    String stepOutput = ToolCallOutcomeRecorder.withReWrappedToolCalls(
      accumulator.toString(),
      captureStream != null ? captureStream.toolOutput() : "",
      cfg
    );
    String outputField = resolveOutputField(cfg.getOutputField(), stepId, ".output");
    pctx.set(outputField, stepOutput);

    if (!stepOutput.isEmpty()) {
      recordStepOutput(stepId, cfg, pctx, role, captureConfig, captureStream, stepOutput);
    }

    if (captureStream != null) {
      recordUsageAndFinish(stepId, cfg, ctx, pctx, captureStream);
    }

    ToolCallOutcomeRecorder.recordOutcome(
      pctx,
      stepId,
      cfg,
      stepOutput,
      captureStream != null ? captureStream.toolOutput() : "",
      ctx.metrics()
    );

    LOGGER.debug("InferStep '{}': generated {} chars", stepId, stepOutput.length());
    return ctx.rxNextStep(stepId);
  }

  /**
   * Appends the step output to {@code generated_messages} and, for
   * non-internal roles, to the conversation; internal steps instead stash
   * their visible answer as pending narration.
   */
  private static void recordStepOutput(
    String stepId,
    InferStepConfig cfg,
    PipelineContext pctx,
    StepRole role,
    TokenCaptureStream.CaptureConfig captureConfig,
    TokenCaptureStream captureStream,
    String stepOutput
  ) {
    // Append to generated_messages log — preserved across CoT
    // iterations, unlike the step_id.output field which is
    // overwritten by each run.
    pctx.addGeneratedMessage(stepId, stepOutput);

    // Append to the conversation only when the role semantically
    // belongs in the chat history. Internal steps (routers, graders,
    // self-evaluators) produce metadata like "YES"/"NO" that would
    // pollute the conversation seen by downstream inference steps.
    if (role != StepRole.STEP_ROLE_INTERNAL) {
      // History hygiene: the reasoning block must not re-enter later
      // prompts - it burns context on every subsequent turn and embeds
      // raw channel markers as content. The conversation turn carries
      // the answer plus re-wrapped tool calls; the step output field
      // keeps the raw text.
      String conversationText = stepOutput;
      if (
        captureConfig.mode() == TokenCaptureStream.ThinkingMode.ROUTE &&
        captureConfig.openTag() != null &&
        captureStream != null
      ) {
        String stripped = ToolCallOutcomeRecorder.withReWrappedToolCalls(
          captureStream.answerOutput(),
          captureStream.toolOutput(),
          cfg
        );
        if (!stripped.isBlank()) {
          conversationText = stripped;
        }
      }
      int msgCountBefore = pctx.messages() != null ? pctx.messages().size() : 0;
      pctx.appendMessage(new ChatTurn(ChatRole.ASSISTANT, conversationText));
      LOGGER.debug(
        "InferStep '{}': appended assistant response (role={}) — messages grew from {} to {}",
        stepId,
        role,
        msgCountBefore,
        pctx.messages().size()
      );
    } else {
      LOGGER.debug(
        "InferStep '{}': skipped conversation append (role={} is internal)",
        stepId,
        role
      );
      // An internal step's visible words (answer channel only) become the
      // narration a client-tool halt surfaces - "I'll run git status now"
      // - so agent UIs see what is happening between tool calls.
      if (captureStream != null) {
        String answer = ToolCallOutcomeRecorder.sanitizeNarration(
          captureStream.answerOutput(),
          pctx
        );
        // The planner's abstention sentinel is protocol, not prose.
        if (!answer.isEmpty() && !"SKIP".equalsIgnoreCase(answer)) {
          pctx.setPendingNarration(answer);
        }
      }
    }
  }

  /** Records usage, performance, finish reason and thinking-health signals from the completed event. */
  private static void recordUsageAndFinish(
    String stepId,
    InferStepConfig cfg,
    StepContext ctx,
    PipelineContext pctx,
    TokenCaptureStream captureStream
  ) {
    var lastResp = captureStream.lastResponse();
    if (
      lastResp != null && lastResp.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED
    ) {
      var completed = lastResp.getResponseCompleted();
      pctx.accumulateUsage(
        completed.hasUsage() ? completed.getUsage() : null,
        completed.hasPerformance() ? completed.getPerformance() : null
      );
      if (completed.hasUsage() && ctx.metrics() != null) {
        var usage = completed.getUsage();
        ctx
          .metrics()
          .recordTokens(
            cfg.getModelId(),
            usage.getPromptTokens(),
            usage.getCompletionTokens(),
            usage.getReasoningTokens(),
            usage.getToolTokens()
          );
      }
      if (completed.hasUsage()) {
        var usage = completed.getUsage();
        pctx.set(stepId + ".prompt_tokens", Long.toString(usage.getPromptTokens()));
        pctx.set(stepId + ".completion_tokens", Long.toString(usage.getCompletionTokens()));
        pctx.set(stepId + ".reasoning_tokens", Long.toString(usage.getReasoningTokens()));
      }
      if (completed.hasPerformance()) {
        // Per-step prefill cost: prompt_tokens alone cannot show how much
        // of the prompt was re-evaluated versus served from the KV
        // prefix cache — the eval TIME is what a re-prefill regression
        // moves.
        pctx.set(
          stepId + ".prompt_ms",
          Long.toString((long) completed.getPerformance().getPromptEvalTimeMs())
        );
      }
      if (completed.getFinishReason() != FinishReason.FINISH_REASON_UNSPECIFIED) {
        pctx.setLastEngineFinishReason(completed.getFinishReason());
        String reasonLabel = finishReasonLabel(completed.getFinishReason());
        pctx.set(stepId + ".finish_reason", reasonLabel);
        if (ctx.metrics() != null) {
          ctx.metrics().recordFinishReason(cfg.getModelId(), reasonLabel);
        }
      }
    }
    pctx.set(stepId + ".thinking_unclosed", Boolean.toString(captureStream.thinkingUnclosed()));
    if (captureStream.thinkingUnclosed() && ctx.metrics() != null) {
      ctx.metrics().recordFailureSignal(cfg.getModelId(), "thinking_unclosed");
    }
  }

  /** Lower-cased context-field label for a finish reason (e.g. {@code stop}, {@code length}). */
  static String finishReasonLabel(FinishReason reason) {
    return switch (reason) {
      case FINISH_REASON_STOP -> "stop";
      case FINISH_REASON_LENGTH -> "length";
      case FINISH_REASON_TOOL_CALLS -> "tool_calls";
      case FINISH_REASON_GUARD_BLOCKED -> "guard_blocked";
      case FINISH_REASON_BREAK_CONDITION -> "break_condition";
      case FINISH_REASON_MAX_ITERATIONS -> "max_iterations";
      case FINISH_REASON_CANCELLED -> "cancelled";
      case FINISH_REASON_STALLED -> "stalled";
      default -> "unspecified";
    };
  }
}
