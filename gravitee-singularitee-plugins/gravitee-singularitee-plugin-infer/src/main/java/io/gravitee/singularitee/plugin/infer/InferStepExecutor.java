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
package io.gravitee.singularitee.plugin.infer;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.OpenInference;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.pipeline.executor.TokenCaptureStream;
import io.gravitee.singularitee.engine.api.pipeline.executor.TokenStreamWriter;
import io.gravitee.singularitee.engine.api.pipeline.executor.TracingOptions;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignals;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry.ModelEntry;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.ToolCall;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>Fully reactive, no blocking. The step returns a {@link Maybe} that chains on
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
  private final TemplateRenderer templateRenderer;

  public InferStepExecutor(
    StepExecutionContext execContext,
    TemplateRenderer templateRenderer,
    ChatTemplateRenderer chatTemplateRenderer
  ) {
    super(execContext);
    this.templateRenderer = templateRenderer;
    this.promptAssembler = new PromptAssembler(templateRenderer, chatTemplateRenderer);
  }

  @Override
  protected String getModelId(InferStepConfig config) {
    return config.modelId();
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
    // Capture the step-span scribe now, while the active step span is set (the model.id attribute
    // is written here too). finalizeStep runs after the token stream on another context where the
    // active-step-span reference is no longer this step's, so re-reading ctx.stepScribe() there
    // would drop the writes. The captured scribe holds the span directly.
    final SpanScribe stepScribe = ctx.stepScribe();
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
    // The exact prompt the model received (template + context rendered) as OpenInference
    // input.value, so a trace shows what was actually sent. Verbose-gated (large, may hold PII).
    if (
      TracingOptions.openInference() && TracingOptions.verbose() && prompt.renderedPrompt() != null
    ) {
      stepScribe.set(OpenInference.INPUT_VALUE, prompt.renderedPrompt());
    }

    StepRole role = ctx.currentStep() != null
      ? ctx.currentStep().role()
      : StepRole.STEP_ROLE_UNSPECIFIED;
    var structuredOutput = TextGenRequestFactory.resolveStructuredOutput(
      role,
      pctx.requestStructuredOutput()
    );
    var toolsConflict = TextGenRequestFactory.toolsConflict(
      structuredOutput,
      cfg.shouldInjectTools(),
      pctx.tools(),
      stepId
    );
    if (toolsConflict != null) {
      return Maybe.error(toolsConflict);
    }

    var textGenReq = TextGenRequestFactory.create(
      cfg,
      prompt.renderedPrompt(),
      prompt.wireMessages(),
      requestOverrides,
      retryOverrides,
      maxTokens,
      pctx.cacheKey(),
      pctx.get(PipelineContext.KEY_REASONING_EFFORT),
      structuredOutput
    );

    // The engine sequence this generation will run on, resolved once (nextSequenceId advances a
    // counter, so it must not be called twice) and threaded into streamGeneration below.
    int stepSeqId = execContext
      .lookupModel(cfg.modelId())
      .map(ModelEntry::nextSequenceId)
      .orElse(0);

    // How the model was called, on this step span: sampling parameters as the OpenInference
    // llm.invocation_parameters (fixed key), plus the engine sequence id. Not content (no PII), so
    // gated on openInference only, not verbose.
    if (TracingOptions.openInference()) {
      var reasoningEffort = pctx.get(PipelineContext.KEY_REASONING_EFFORT);
      stepScribe
        .set(
          OpenInference.LLM_INVOCATION_PARAMETERS,
          invocationParams(textGenReq, reasoningEffort == null ? null : reasoningEffort.toString())
        )
        .set("singularitee.infer.sequence_id", stepSeqId);
    }

    var captureConfig = captureConfig(cfg, role);

    var accumulator = new StringBuilder();
    var captureStreamRef = new AtomicReference<TokenCaptureStream>();

    // Submit mark for the client-experienced time-to-first-token: the delta to the capture
    // stream's first token spans the queue wait and the engine, i.e. the latency as the client
    // saw it, not the engine's decode-only ttft.
    long submitNanos = System.nanoTime();

    return streamGeneration(
      stepId,
      cfg,
      tge,
      ctx,
      textGenReq,
      stepSeqId,
      accumulator,
      captureConfig,
      captureStreamRef
    ).andThen(
      Maybe.defer(() ->
        finalizeStep(
          stepId,
          cfg,
          ctx,
          stepScribe,
          role,
          captureConfig,
          accumulator,
          captureStreamRef.get(),
          submitNanos
        )
      )
    );
  }

  /**
   * Derives the capture behavior for this step: whether tokens stream to the
   * client, how reasoning is handled (strip_thinking removes
   * reasoning from both the step output and the wire;
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
    boolean forwardThinking = shouldStream || (cfg.shouldStreamThinking());
    StepRole wireRole = (role == StepRole.STEP_ROLE_THINKING)
      ? StepRole.STEP_ROLE_THINKING
      : StepRole.STEP_ROLE_OUTPUT;
    var thinkingMode = cfg.shouldStripThinking()
      ? TokenCaptureStream.ThinkingMode.STRIP
      : TokenCaptureStream.ThinkingMode.ROUTE;
    String thinkOpenTag = null;
    String thinkCloseTag = null;
    if (cfg.reasoningTags() != null) {
      var rt = cfg.reasoningTags();
      if (!rt.getOpenTag().isBlank()) thinkOpenTag = rt.getOpenTag();
      if (!rt.getCloseTag().isBlank()) thinkCloseTag = rt.getCloseTag();
    }
    List<String> cutMarkers = null;
    if (cfg.hasToolOpenTag()) {
      cutMarkers = new ArrayList<>();
      cutMarkers.add(cfg.toolCallTags().getOpenTag());
      cutMarkers.addAll(cfg.toolCallTags().getOpenTagAlternativesList());
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
   * The sampling parameters as a compact JSON object for OpenInference
   * llm.invocation_parameters. Only the fields that were actually set are
   * emitted (a null means the engine default applies), so the trace shows the
   * request as sent rather than a padded shape.
   */
  private static String invocationParams(TextGenRequest req, String reasoningEffort) {
    var parts = new ArrayList<String>();
    if (req.maxTokens() != null) parts.add("\"max_tokens\":" + req.maxTokens());
    if (req.temperature() != null) parts.add("\"temperature\":" + req.temperature());
    if (req.topP() != null) parts.add("\"top_p\":" + req.topP());
    if (req.seed() != null) parts.add("\"seed\":" + req.seed());
    if (reasoningEffort != null && !reasoningEffort.isBlank()) {
      parts.add("\"reasoning_effort\":\"" + reasoningEffort + "\"");
    }
    return "{" + String.join(",", parts) + "}";
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
    int stepSeqId,
    StringBuilder accumulator,
    TokenCaptureStream.CaptureConfig captureConfig,
    AtomicReference<TokenCaptureStream> captureStreamRef
  ) {
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
        cfg.modelId()
      );

      LOGGER.info(
        "InferStep '{}': calling rxAddSequence(seqId={}) on model '{}'",
        stepId,
        stepSeqId,
        cfg.modelId()
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
    SpanScribe stepScribe,
    StepRole role,
    TokenCaptureStream.CaptureConfig captureConfig,
    StringBuilder accumulator,
    TokenCaptureStream captureStream,
    long submitNanos
  ) {
    var pctx = ctx.pipelineContext();
    // Engines that classify tool tokens suppress the tag markers and deliver
    // the bare payload on the TOOL channel (captured separately). Re-wrap it
    // with the step's configured tool tags so the step output, and the
    // assistant turn appended below, keep the tagged-text form; chat templates and downstream tool parsing re-render
    // prior tool calls from that tagged block on later turns.
    String stepOutput = ToolCallOutcomeRecorder.withReWrappedToolCalls(
      accumulator.toString(),
      captureStream != null ? captureStream.toolOutput() : "",
      cfg
    );
    String outputField = resolveOutputField(cfg.outputField(), stepId, ".output");
    pctx.set(outputField, stepOutput);

    // Confidence signals as in-pipeline features: every ConfidenceSignal is written to the context
    // like the token counts, so a downstream step can read the one(s) it wants
    // (e.g. <stepId>.perplexity, <stepId>.min_margin). Present whenever logprobs were captured; all
    // are free byproducts of the one generation.
    if (captureStream != null && captureStream.confidence().size() > 0) {
      ConfidenceSignals.computeAll(captureStream.confidence()).forEach((field, value) ->
        pctx.set(stepId + "." + field, String.valueOf(value))
      );
    }

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
      ctx.metrics(),
      templateRenderer
    );

    LOGGER.debug("InferStep '{}': generated {} chars", stepId, stepOutput.length());

    // Neutral model-turn facts for the diary: role and proposed tool calls on this step
    // span, plus a model turn event on the pipeline span. No-op when tracing is off.
    String toolNames = String.join(
      ",",
      pctx.extractedToolCalls().stream().map(ToolCall::getName).toList()
    );
    if (!pctx.extractedToolCalls().isEmpty()) {
      pctx.set(PipelineContext.KEY_TOOL_CALL_STEP, stepId);
    }
    stepScribe
      .set(SpanNames.key("role"), "model")
      .set(SpanNames.key("tool_calls.count"), pctx.extractedToolCalls().size())
      .set(SpanNames.key("tool_calls.names"), toolNames);
    ctx
      .turnScribe()
      .event(
        SpanNames.key("turn"),
        Map.of("role", "model", "tool_calls", toolNames, "content", spanPreview(stepOutput))
      );

    // OpenInference LLM attributes on this step span (fixed keys, never prefixed), so trace tools
    // read the model, token counts and (behind verbose) the messages. Token counts were stashed
    // by recordUsageAndFinish above.
    if (TracingOptions.openInference()) {
      var oi = stepScribe
        .set(OpenInference.LLM_MODEL_NAME, cfg.modelId())
        .set(OpenInference.LLM_PROVIDER, "singularitee");
      long prompt = longField(pctx, stepId + ".prompt_tokens");
      long completion = longField(pctx, stepId + ".completion_tokens");
      oi
        .set(OpenInference.LLM_TOKEN_PROMPT, prompt)
        .set(OpenInference.LLM_TOKEN_COMPLETION, completion)
        .set(OpenInference.LLM_TOKEN_TOTAL, prompt + completion)
        .set(OpenInference.LLM_TOKEN_REASONING, longField(pctx, stepId + ".reasoning_tokens"));
      // Client-experienced time-to-first-token: submit to the first token the capture stream saw.
      // Absent (0) when the turn produced no content token, e.g. a pure tool call.
      if (captureStream != null && captureStream.firstTokenNanos() != 0L) {
        oi.set(
          "singularitee.infer.ttft_ms",
          (captureStream.firstTokenNanos() - submitNanos) / 1_000_000
        );
      }
      // The same confidence signals on the span (perplexity and its distributional cousins, the
      // top-k margin/entropy). Cheap single-pass features; downstream consumers calibrate them
      // against outcomes, they are not correctness measures. Present only when top_logprobs was
      // requested.
      if (captureStream != null && captureStream.confidence().size() > 0) {
        ConfidenceSignals.computeAll(captureStream.confidence()).forEach((field, value) ->
          oi.set("singularitee.infer." + field, String.valueOf(value))
        );
      }
      var calls = pctx.extractedToolCalls();
      for (int j = 0; j < calls.size(); j++) {
        oi
          .set(OpenInference.outputToolCallName(0, j), calls.get(j).getName())
          .set(OpenInference.outputToolCallArguments(0, j), calls.get(j).getArgumentsJson());
      }
      if (TracingOptions.verbose()) {
        String reasoning = captureStream == null ? "" : captureStream.capturedThinking();
        // The answer, once and clean. The raw step output embeds the reasoning and Harmony channel
        // markers, so logging it verbatim repeats the reasoning and buries the answer in markup.
        // When the turn is a tool call, the answer IS the call: summarise it compactly (the detail
        // is already in the structured tool_calls). Otherwise, take the answer channel (reasoning
        // excluded) with the control markers stripped.
        String answerText;
        if (!pctx.extractedToolCalls().isEmpty()) {
          var sb = new StringBuilder();
          for (ToolCall c : pctx.extractedToolCalls()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.getName()).append('(').append(c.getArgumentsJson()).append(')');
          }
          answerText = sb.toString();
        } else {
          answerText = stripControlMarkers(
            captureStream == null ? stepOutput : captureStream.answerOutput()
          );
        }
        oi.set(OpenInference.outputMessageRole(0), "assistant");
        if (reasoning.isBlank()) {
          oi.set(OpenInference.outputMessageContent(0), answerText);
        } else {
          // One assistant message as a content array: the reasoning, then the answer. No scalar
          // content alongside it (OpenInference carries one or the other, not both).
          oi
            .set(OpenInference.outputContentType(0, 0), OpenInference.CONTENT_TYPE_REASONING)
            .set(OpenInference.outputContentText(0, 0), reasoning)
            .set(OpenInference.outputContentType(0, 1), OpenInference.CONTENT_TYPE_TEXT)
            .set(OpenInference.outputContentText(0, 1), answerText);
        }
        // Top-level readout of the answer (OpenInference output.value). Literal key: the plugin
        // compiles against a newer engine-api than the runtime may provide.
        oi.set("output.value", answerText);
        // The engine-managed to-do plan at this step, so a trace shows the plan the model was
        // working (and how it advances across steps/turns). Neutral OSS primitive.
        if (!pctx.todos().isEmpty()) {
          oi.set(SpanNames.key("todos"), todosSummary(pctx));
        }
        var msgs = pctx.messages();
        if (msgs != null) {
          for (int i = 0; i < msgs.size(); i++) {
            var m = msgs.get(i);
            oi
              .set(OpenInference.inputMessageRole(i), m.role().name().toLowerCase(Locale.ROOT))
              .set(OpenInference.inputMessageContent(i), m.content() == null ? "" : m.content());
            // An assistant turn's own tool calls, with ids, so trace tools link a call to its
            // result; a tool-result turn names the call it answers.
            var tcs = m.toolCalls();
            for (int k = 0; k < tcs.size(); k++) {
              var tc = tcs.get(k);
              oi
                .set(OpenInference.inputToolCallId(i, k), tc.id())
                .set(OpenInference.inputToolCallName(i, k), tc.name())
                .set(OpenInference.inputToolCallArguments(i, k), tc.argumentsJson());
            }
            if (m.role() == ChatRole.TOOL && m.toolCallId() != null) {
              oi.set(OpenInference.inputMessageToolCallId(i), m.toolCallId());
            }
          }
        }
      }
    }
    return ctx.rxNextStep(stepId);
  }

  /** Reads a stashed numeric context field as a long, or 0 when absent/malformed. */
  private static long longField(PipelineContext pctx, String key) {
    String v = pctx.get(key);
    if (v == null || v.isBlank()) return 0;
    try {
      return Long.parseLong(v.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Strips Harmony/channel control markers ({@code <|...|>}) and collapses whitespace. */
  private static String stripControlMarkers(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return text.replaceAll("<\\|[^|]*\\|>", " ").replaceAll("\\s+", " ").trim();
  }

  /** A readable snapshot of the engine-managed to-do plan for a tracing span. */
  private static String todosSummary(PipelineContext pctx) {
    var todos = pctx.todos();
    var sb = new StringBuilder();
    for (int i = 0; i < todos.size(); i++) {
      var t = todos.get(i);
      if (i > 0) sb.append('\n');
      sb
        .append(i + 1)
        .append(". [")
        .append(t.status().wireName())
        .append("] ")
        .append(t.title())
        .append(" (id=")
        .append(t.id())
        .append(')');
      if (t.proof() != null && !t.proof().isBlank()) {
        sb.append(" -> ").append(t.proof());
      }
    }
    return sb.toString();
  }

  /** A short, one-line, span-safe preview of possibly large or sensitive text (never null). */
  private static String spanPreview(String text) {
    if (text == null || text.isEmpty()) return "";
    String oneLine = text.strip().replace('\n', ' ');
    return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
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
    // Append to generated_messages log, preserved across CoT
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
        "InferStep '{}': appended assistant response (role={}), messages grew from {} to {}",
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
            cfg.modelId(),
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
        // prefix cache; the eval TIME is what a re-prefill regression
        // moves.
        pctx.set(
          stepId + ".prompt_ms",
          Long.toString(completed.getPerformance().getPromptEvalTimeMs())
        );
      }
      if (completed.getFinishReason() != FinishReason.FINISH_REASON_UNSPECIFIED) {
        pctx.setLastEngineFinishReason(completed.getFinishReason());
        String reasonLabel = finishReasonLabel(completed.getFinishReason());
        pctx.set(stepId + ".finish_reason", reasonLabel);
        if (ctx.metrics() != null) {
          ctx.metrics().recordFinishReason(cfg.modelId(), reasonLabel);
        }
      }
    }
    pctx.set(stepId + ".thinking_unclosed", Boolean.toString(captureStream.thinkingUnclosed()));
    if (captureStream.thinkingUnclosed() && ctx.metrics() != null) {
      ctx.metrics().recordFailureSignal(cfg.modelId(), "thinking_unclosed");
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
