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
import io.gravitee.singularitee.engine.ToolDefinitionConverter;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import io.gravitee.singularitee.engine.tools.TodoTools;
import io.gravitee.singularitee.engine.tools.ToolCallExtractor;
import io.gravitee.singularitee.engine.tools.ToolMarkerResidues;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.registry.ModelRegistry.ModelEntry;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes an INFER step: generates text using a language model and streams tokens.
 *
 * <p>Uses Jinja4j for all template resolution — both message content resolution
 * (replacing the old regex-based TemplateResolver) and the model's chat template
 * rendering (replacing engine-specific template application).
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

  private static final ThreadLocal<StepRole> STEP_ROLE = new ThreadLocal<>();

  private final JinjaRenderer jinjaRenderer;
  private final Jinja4jChatTemplateRenderer chatTemplateRenderer;

  public InferStepExecutor(StepExecutionContext execContext, JinjaRenderer jinjaRenderer) {
    super(execContext);
    this.jinjaRenderer = jinjaRenderer;
    this.chatTemplateRenderer = new Jinja4jChatTemplateRenderer();
  }

  @Override
  public InferStepConfig extractConfig(PipelineStep step) {
    STEP_ROLE.set(step.getRole());
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

    // ── 1. Build the Jinja4j rendering context ─────────────────────────
    Map<String, Object> jinjaCtx = buildJinjaContext(pctx, tge, cfg);

    // Effective completion reservation, resolved once (request override wins
    // over the loop-retry override, which wins over the step's sampling params)
    // and reused for both the context-window trim below and the TextGenRequest.
    var requestOverrides = pctx.requestSamplingParams();
    var retryOverrides = pctx.retrySamplingParams();
    var stepSp = cfg.hasSamplingParams()
      ? cfg.getSamplingParams()
      : SamplingParams.getDefaultInstance();
    int maxTokens = pick(
      requestOverrides != null ? requestOverrides.getMaxTokens() : 0,
      pick(retryOverrides != null ? retryOverrides.getMaxTokens() : 0, stepSp.getMaxTokens())
    );

    // ── 2. Resolve messages and render prompt ──────────────────────────
    String renderedPrompt;
    List<ChatTurn> originalMessages = pctx.messages(); // retained for multimodal
    List<ChatTurn> wireMessages = originalMessages;

    if (!cfg.getRawTemplate().isBlank()) {
      // Raw template mode — resolve with Jinja4j, bypass chat template
      renderedPrompt = resolveJinjaString(cfg.getRawTemplate(), jinjaCtx);
    } else {
      // Resolve messages
      List<Map<String, Object>> messages;
      if (!cfg.getMessagesList().isEmpty()) {
        // YAML overrides messages — resolve each content with Jinja4j
        messages = cfg
          .getMessagesList()
          .stream()
          .map(md -> {
            String resolvedContent = resolveJinjaString(md.getContent(), jinjaCtx);
            return Map.<String, Object>of("role", md.getRole(), "content", resolvedContent);
          })
          .toList();
      } else if (pctx.messages() != null) {
        // Implicit passthrough — caller's messages as-is
        messages = pctx.messages().stream().map(InferStepExecutor::toTemplateMessage).toList();
      } else {
        // Bare prompt fallback
        String bare = pctx.get(PipelineContext.KEY_PROMPT);
        messages = List.of(Map.of("role", "user", "content", bare != null ? bare : ""));
      }

      // Step system prompt (yaml `system:`), Jinja-resolved so live pipeline
      // state ({{todos}}, {{prompt}}, step outputs) can steer a step WITHOUT
      // replacing the conversation the way a prompt.messages override does.
      // COMBINES with a caller-supplied system message rather than yielding to
      // it: the caller's prompt establishes identity, the step's establishes
      // its role in the graph — silently dropping the step steering left
      // agent-harness requests (which always carry their own system prompt)
      // running the pipeline without its instructions.
      String systemPrompt = cfg.getSystemPrompt().isBlank()
        ? cfg.getSystemPrompt()
        : resolveJinjaString(cfg.getSystemPrompt(), jinjaCtx);
      if (!systemPrompt.isBlank()) {
        int callerSystem = -1;
        for (int i = 0; i < messages.size(); i++) {
          if ("system".equals(messages.get(i).get("role"))) {
            callerSystem = i;
            break;
          }
        }
        if (callerSystem < 0) {
          List<Map<String, Object>> withSystem = new ArrayList<>();
          withSystem.add(Map.of("role", "system", "content", systemPrompt));
          withSystem.addAll(messages);
          messages = withSystem;
        } else {
          // Merge into the caller's system turn (caller first): some chat
          // templates only honor a single system message.
          List<Map<String, Object>> merged = new ArrayList<>(messages);
          Map<String, Object> sys = new LinkedHashMap<>(merged.get(callerSystem));
          sys.put("content", sys.get("content") + "\n\n" + systemPrompt);
          merged.set(callerSystem, sys);
          messages = merged;
        }
      }

      // ── Chat window limiting ───────────────────────────────────────
      // Trim older turns so the prompt + completion reservation fits the
      // model's context window. Enabled unless trim_history is explicitly
      // false, and only when the engine reports its window size.
      boolean trimHistory = !cfg.hasTrimHistory() || cfg.getTrimHistory();
      if (trimHistory && tge.contextSize() > 0) {
        String toolOpenTag = (cfg.hasToolCallTags() &&
            !cfg.getToolCallTags().getOpenTag().isBlank())
          ? cfg.getToolCallTags().getOpenTag()
          : ChatWindowTrimmer.DEFAULT_TOOL_OPEN_TAG;
        TokenCounter counter = counterOf(tge);
        var trimmed = ChatWindowTrimmer.trim(
          messages,
          tge.contextSize(),
          maxTokens,
          counter,
          toolOpenTag
        );
        if (trimmed != messages) {
          long keptTokens = trimmed
            .stream()
            .mapToLong(m -> counter.count(String.valueOf(m.getOrDefault("content", ""))))
            .sum();
          LOGGER.info(
            "InferStep '{}': trimmed {}→{} messages (~{} tokens) to fit context budget {}",
            stepId,
            messages.size(),
            trimmed.size(),
            keptTokens,
            tge.contextSize()
          );
          messages = trimmed;
        }
      }

      // Put resolved messages into context for the chat template, with the model's special
      // tokens neutralised in every message body first. Prompts are tokenized with
      // parse_special enabled — required so the template's own scaffolding becomes real control
      // tokens — and that same pass applies to message text. Left alone, a message containing
      // <|im_start|>, <|channel|> or <start_of_turn> is tokenized as the control token and
      // forges conversation structure from inside a message.
      jinjaCtx.put("messages", neutralizeSpecialTokens(messages, tge.specialTokenTexts()));

      // Render using the step's chat_template override when present, otherwise
      // the model's own (GGUF-metadata) chat template.
      boolean hasOverride = cfg.hasChatTemplate() && !cfg.getChatTemplate().isBlank();
      String templateString = hasOverride ? cfg.getChatTemplate() : tge.chatTemplateString();
      if (templateString != null) {
        List<Map<String, Object>> tools = shouldInjectTools(cfg)
          ? ToolDefinitionConverter.toOpenAiMaps(injectableTools(pctx, cfg))
          : null;
        try {
          renderedPrompt = chatTemplateRenderer.render(templateString, null, tools, true, jinjaCtx);
        } catch (RuntimeException e) {
          if (hasOverride) {
            throw new IllegalArgumentException(
              "InferStep '" +
                stepId +
                "': chat_template override is not a valid Jinja template — " +
                e.getMessage(),
              e
            );
          }
          throw e;
        }
      } else {
        // No chat template available (e.g. remote metadata not fetched yet).
        // Never degrade to plain "role: content" concatenation — that strips
        // all ChatML scaffolding (assistant header, <think> prefill) and
        // silently breaks models like Qwen3. Ship the structured messages
        // instead: the engine forwards them and the model server renders
        // with its own authoritative template.
        LOGGER.error(
          "InferStep '{}': model '{}' has no chat template — sending structured " +
            "messages for engine-side rendering (template variables like " +
            "enable_thinking are forwarded via template_context)",
          stepId,
          cfg.getModelId()
        );
        renderedPrompt = null;
        // Passthrough case: keep the caller's original turns so multimodal
        // media survives (the resolved maps carry role/content only). YAML-
        // defined messages and bare prompts never carry media — convert those.
        wireMessages = (cfg.getMessagesList().isEmpty() && originalMessages != null)
          ? originalMessages
          : toChatTurns(messages);
      }
    }

    // ── 3. Build TextGenRequest ────────────────────────────────────────
    if (LOGGER.isTraceEnabled() && renderedPrompt != null) {
      LOGGER.trace(
        "InferStep '{}': rendered prompt ({} chars) ->\n{}",
        stepId,
        renderedPrompt.length(),
        renderedPrompt
      );
    }
    var textGenReq = buildTextGenRequest(
      cfg,
      renderedPrompt,
      wireMessages,
      requestOverrides,
      retryOverrides,
      maxTokens,
      pctx.cacheKey(),
      pctx.get("reasoning_effort")
    );

    // ── 4. Stream tokens (unchanged from before) ───────────────────────
    StepRole role = STEP_ROLE.get();
    STEP_ROLE.remove();
    boolean shouldStream = role != StepRole.STEP_ROLE_INTERNAL;
    // stream_thinking: an internal step may still surface its reasoning live.
    boolean forwardThinking = shouldStream || (cfg.hasStreamThinking() && cfg.getStreamThinking());
    StepRole wireRole = (role == StepRole.STEP_ROLE_THINKING)
      ? StepRole.STEP_ROLE_THINKING
      : StepRole.STEP_ROLE_OUTPUT;

    int stepSeqId = execContext
      .lookupModel(cfg.getModelId())
      .map(ModelEntry::nextSequenceId)
      .orElse(0);

    var accumulator = new StringBuilder();
    var captureStreamHolder = new TokenCaptureStream[1];

    // ── Thinking handling config ───────────────────────────────────────
    // strip_thinking: true keeps the legacy behavior (reasoning removed
    // from both the step output and the wire). Otherwise reasoning is
    // ROUTEd: forwarded on a separate STEP_ROLE_THINKING flux so clients
    // receive two streams (reasoning + output), while the step output
    // keeps the raw text untouched.
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

    // Effectively-final locals for use in the lambda.
    String finalOpenTag = thinkOpenTag;
    String finalCloseTag = thinkCloseTag;

    return Completable.create(emitter -> {
      var captureStream = new TokenCaptureStream(
        accumulator,
        emitter,
        ctx.response(),
        shouldStream,
        forwardThinking,
        wireRole,
        thinkingMode,
        finalOpenTag,
        finalCloseTag
      );
      if (cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank()) {
        var cutMarkers = new ArrayList<String>();
        cutMarkers.add(cfg.getToolCallTags().getOpenTag());
        cutMarkers.addAll(cfg.getToolCallTags().getOpenTagAlternativesList());
        captureStream.setThinkingCutMarkers(cutMarkers);
      }
      captureStreamHolder[0] = captureStream;

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
    }).andThen(
      Maybe.defer(() -> {
        // Engines that classify tool tokens suppress the tag markers and deliver
        // the bare payload on the TOOL channel (captured separately). Re-wrap it
        // with the step's configured tool tags so the step output — and the
        // assistant turn appended below — stays byte-compatible with the legacy
        // tagged text; chat templates and downstream tool parsing re-render
        // prior tool calls from that tagged block on later turns.
        String stepOutput = withReWrappedToolCalls(
          accumulator.toString(),
          captureStreamHolder[0] != null ? captureStreamHolder[0].toolOutput() : "",
          cfg
        );
        String outputField = resolveOutputField(cfg.getOutputField(), stepId, ".output");
        pctx.set(outputField, stepOutput);

        if (!stepOutput.isEmpty()) {
          // Append to generated_messages log — preserved across CoT
          // iterations, unlike the step_id.output field which is
          // overwritten by each run.
          pctx.addGeneratedMessage(stepId, stepOutput);

          // Append to the conversation only when the role semantically
          // belongs in the chat history. Internal steps (routers, graders,
          // self-evaluators) produce metadata like "YES"/"NO" that would
          // pollute the conversation seen by downstream inference steps.
          boolean appendToConversation = role != StepRole.STEP_ROLE_INTERNAL;
          if (appendToConversation) {
            // History hygiene: the reasoning block must not re-enter later
            // prompts - it burns context on every subsequent turn and embeds
            // raw channel markers as content. The conversation turn carries
            // the answer plus re-wrapped tool calls; the step output field
            // keeps the raw text.
            String conversationText = stepOutput;
            if (
              thinkingMode == TokenCaptureStream.ThinkingMode.ROUTE &&
              finalOpenTag != null &&
              captureStreamHolder[0] != null
            ) {
              String stripped = withReWrappedToolCalls(
                captureStreamHolder[0].answerOutput(),
                captureStreamHolder[0].toolOutput(),
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
            if (captureStreamHolder[0] != null) {
              String answer = sanitizeNarration(captureStreamHolder[0].answerOutput(), pctx);
              // The planner's abstention sentinel is protocol, not prose.
              if (!answer.isEmpty() && !"SKIP".equalsIgnoreCase(answer)) {
                pctx.setPendingNarration(answer);
              }
            }
          }
        }

        var captureStream = captureStreamHolder[0];
        if (captureStream != null) {
          var lastResp = captureStream.lastResponse();
          if (
            lastResp != null &&
            lastResp.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED
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
          pctx.set(
            stepId + ".thinking_unclosed",
            Boolean.toString(captureStream.thinkingUnclosed())
          );
          if (captureStream.thinkingUnclosed() && ctx.metrics() != null) {
            ctx.metrics().recordFailureSignal(cfg.getModelId(), "thinking_unclosed");
          }
        }

        // Template-driven tool-call extraction: when the engine stamped a tool span (bare
        // TOOL-channel payload) or reported a tool_calls finish, render the step's extraction
        // template (or the built-in dialect templates in order) over the span and surface the
        // structured calls on the final ResponseCompleted event. Failure to extract is fail-open:
        // the raw text has already been forwarded/stored, clients treat it as plain content.
        String bareToolSpan = captureStreamHolder[0] != null
          ? captureStreamHolder[0].toolOutput()
          : "";
        boolean toolCandidate =
          !bareToolSpan.isEmpty() ||
          pctx.lastEngineFinishReason() == FinishReason.FINISH_REASON_TOOL_CALLS;
        if (toolCandidate) {
          String extractionInput = !bareToolSpan.isEmpty() ? bareToolSpan : stepOutput;
          var extraction = ToolCallExtractor.extractResult(
            extractionInput,
            toolsData(pctx),
            cfg.getToolExtractionTemplate()
          );
          pctx.setExtractedToolCalls(toWireToolCalls(extraction.calls()));
          setToolSignalFields(pctx, stepId, extraction);
          // Tri-state disambiguation: tool_parse_failed is "true" ONLY when the model
          // attempted a call (span captured / tool_calls finish) and extraction came up
          // empty. A plain prose answer never attempts one and must not trip repair loops.
          pctx.set(stepId + ".tool_parse_failed", Boolean.toString(extraction.calls().isEmpty()));
          if (extraction.calls().isEmpty()) {
            // Name the offender: when the failure is an undeclared tool (a hallucinated
            // built-in like gpt-oss's apply_patch), a repair loop can only converge if
            // the loopback message can tell the model WHICH name was wrong.
            String attempted = attemptedToolName(extractionInput);
            if (!attempted.isEmpty()) {
              pctx.set(stepId + ".attempted_tool", attempted);
            }
            if (ctx.metrics() != null) {
              ctx.metrics().recordFailureSignal(cfg.getModelId(), "tool_parse_failed");
            }
          }
        } else if (hasToolMarkerResidue(stepOutput, cfg)) {
          // The model HALLUCINATED tool-call syntax the tag machine could not
          // recognize (e.g. gpt-oss inventing "<|channel|>functions.name" —
          // no legal form, so enumerating tag variants cannot cover it).
          // Leaked special tokens are never a valid answer: flag a failed
          // attempt so a heal/repair loop retries instead of the raw markers
          // leaking to the client as prose.
          pctx.set(stepId + ".tool_parse_failed", "true");
          pctx.set(stepId + ".parse_error", "unrecognized tool-call markers in output");
          String attempted = attemptedToolName(stepOutput);
          if (!attempted.isEmpty()) {
            pctx.set(stepId + ".attempted_tool", attempted);
          }
          if (ctx.metrics() != null) {
            ctx.metrics().recordFailureSignal(cfg.getModelId(), "tool_parse_failed");
          }
        } else {
          pctx.set(stepId + ".tool_parse_failed", "false");
          maybeExtractMarkerlessToolCalls(pctx, stepId, cfg, stepOutput);
        }

        LOGGER.debug("InferStep '{}': generated {} chars", stepId, stepOutput.length());
        return ctx.rxNextStep(stepId);
      })
    );
  }

  /**
   * Markerless tool dialects (e.g. GLM-4: {@code name\n{json}} as the whole message) never
   * produce a tool span or a {@code tool_calls} finish. When the request declared tools, the
   * engine finished with a plain {@code stop}, and the step explicitly configures an extraction
   * template, attempt extraction on the step's final output text: a non-empty result turns the
   * response into tool calls exactly as a captured span would (finish {@code tool_calls}); an
   * empty result leaves the response untouched. Built-ins are never tried speculatively here —
   * only an explicit template opts a step in.
   *
   * <p>A configured {@code tool_open} tag disqualifies the step outright. Having an explicit
   * extraction template does not make a dialect markerless — Harmony (gpt-oss) declares one
   * because its captured span needs custom parsing, not because it lacks markers. For a
   * marker-based dialect the absence of a span IS the answer: the model made no call. Running
   * markerless extraction over its prose instead lets a name-shaped regex manufacture a call from
   * the first word of ordinary text, and because a non-empty result nulls {@code content}
   * downstream, that phantom silently replaces the answer the model actually gave.
   */
  /**
   * Whether an UNCAPTURED generation contains tool-call marker debris. Only
   * meaningful for marker-based dialects (a configured {@code tool_open}).
   * Marker derivation is dialect-owned — see
   * {@link ToolMarkerResidue}: leaked
   * dialect machinery in the final text means the model attempted a call in a
   * form the tags did not recognize (hallucinated/mutated syntax), never a
   * valid answer.
   */
  /**
   * Narration must be pure prose: a mutated call span with no recognizable
   * open marker (e.g. gpt-oss emitting a bare "ask_user <|constrain|>json...")
   * can share the answer channel with the model's words. Cut at the first
   * dialect special token and strip a trailing tool-name token left behind.
   */
  static String sanitizeNarration(String answer, PipelineContext pctx) {
    if (answer == null) {
      return "";
    }
    String clean = answer;
    int marker = clean.indexOf("<|");
    if (marker >= 0) {
      clean = clean.substring(0, marker);
    }
    clean = clean.strip();
    for (ToolDefinition tool : pctx.tools()) {
      if (clean.endsWith(tool.getName())) {
        clean = clean.substring(0, clean.length() - tool.getName().length()).strip();
      }
    }
    for (ToolDefinition tool : pctx.serverTools()) {
      if (clean.endsWith(tool.getName())) {
        clean = clean.substring(0, clean.length() - tool.getName().length()).strip();
      }
    }
    return clean;
  }

  static boolean hasToolMarkerResidue(String stepOutput, InferStepConfig cfg) {
    boolean markerBased = cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank();
    return (
      markerBased &&
      ToolMarkerResidues.forTemplate(cfg.getToolExtractionTemplate()).isPresent(
        stepOutput,
        cfg.getToolCallTags()
      )
    );
  }

  static void maybeExtractMarkerlessToolCalls(
    PipelineContext pctx,
    String stepId,
    InferStepConfig cfg,
    String stepOutput
  ) {
    boolean markerBased = cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank();
    if (
      pctx.tools().isEmpty() ||
      cfg.getToolExtractionTemplate().isBlank() ||
      markerBased ||
      pctx.lastEngineFinishReason() != FinishReason.FINISH_REASON_STOP
    ) {
      return;
    }
    var extraction = ToolCallExtractor.extractResult(
      stepOutput,
      toolsData(pctx),
      cfg.getToolExtractionTemplate()
    );
    setToolSignalFields(pctx, stepId, extraction);
    if (!extraction.calls().isEmpty()) {
      pctx.setExtractedToolCalls(toWireToolCalls(extraction.calls()));
      pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
      pctx.set(stepId + ".finish_reason", "tool_calls");
    }
  }

  /**
   * Publishes the outcome of a tool-call extraction attempt as pipeline context fields, so
   * {@code loop}/{@code break} conditions and {@code loopback_message} templates can drive a
   * repair loop off a malformed call — the extraction itself stays fail-open.
   */
  static void setToolSignalFields(
    PipelineContext pctx,
    String stepId,
    ToolCallExtractor.ExtractionResult extraction
  ) {
    pctx.set(stepId + ".tool_parse_ok", Boolean.toString(!extraction.calls().isEmpty()));
    pctx.set(stepId + ".tool_call_count", Integer.toString(extraction.calls().size()));
    if (extraction.error() != null) {
      pctx.set(stepId + ".parse_error", extraction.error());
    }
  }

  /**
   * The tool name the model tried to call: the leading identifier of the captured span
   * (Harmony and most dialects start the span with the function name), minus a
   * {@code functions.} namespace prefix. Empty when no identifier leads the span.
   */
  static String attemptedToolName(String span) {
    if (span == null) {
      return "";
    }
    var m = java.util.regex.Pattern.compile("^\\s*([\\w.-]+)").matcher(span);
    if (!m.find()) {
      return "";
    }
    String name = m.group(1);
    return name.startsWith("functions.") ? name.substring("functions.".length()) : name;
  }

  /** Lower-cased context-field label for a finish reason (e.g. {@code stop}, {@code length}). */
  static String finishReasonLabel(io.gravitee.singularitee.protocol.FinishReason reason) {
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

  /**
   * Renders one transcript turn into the OpenAI shape chat templates expect.
   *
   * <p>{@code tool_calls} and the {@code tool_call_id} of a tool result are what let a template
   * show the model what it already did. Omit them and a tool-using conversation replays as a
   * question the assistant never acted on, so it issues the same call again — and again.
   *
   * <p>Arguments are handed over as a parsed map, not as their JSON text: templates iterate them
   * as a mapping, and Gemma's raises outright on a string. Unparseable arguments degrade to an
   * empty map rather than failing the render.
   */
  static Map<String, Object> toTemplateMessage(io.gravitee.singularitee.engine.ChatTurn turn) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", turn.role().name().toLowerCase());
    message.put("content", turn.content() == null ? "" : turn.content());
    if (!turn.toolCalls().isEmpty()) {
      message.put(
        "tool_calls",
        turn
          .toolCalls()
          .stream()
          .map(call -> {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", parseArguments(call.argumentsJson()));
            Map<String, Object> wrapper = new LinkedHashMap<>();
            if (call.id() != null && !call.id().isBlank()) {
              wrapper.put("id", call.id());
            }
            wrapper.put("type", "function");
            wrapper.put("function", function);
            return wrapper;
          })
          .toList()
      );
    }
    if (turn.toolCallId() != null && !turn.toolCallId().isBlank()) {
      message.put("tool_call_id", turn.toolCallId());
    }
    if (turn.name() != null && !turn.name().isBlank()) {
      message.put("name", turn.name());
    }
    return message;
  }

  private static Map<String, Object> parseArguments(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return Map.of();
    }
    try {
      var node = TOOL_JSON_MAPPER.readTree(argumentsJson);
      return node.isObject()
        ? TOOL_JSON_MAPPER.convertValue(
          node,
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
        )
        : Map.of();
    } catch (Exception e) {
      LOGGER.debug("Unparseable tool-call arguments, rendering as empty: {}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Escapes every special-token string in each message's text, so it survives into the prompt as
   * text rather than as a control token.
   *
   * <p>A backslash after the opening character is deliberate: it breaks the tokenizer's exact
   * match while staying visible and obviously-escaped if a model ever echoes it into a file. An
   * invisible escape (zero-width space) would do the same job and then silently corrupt any source
   * the model writes.
   *
   * <p>{@code specials} arrives longest-first so a short marker cannot consume part of a longer
   * one. An empty list (engine cannot enumerate them) leaves messages untouched.
   */
  static List<Map<String, Object>> neutralizeSpecialTokens(
    List<Map<String, Object>> messages,
    List<String> specials
  ) {
    if (messages == null || specials == null || specials.isEmpty()) {
      return messages;
    }
    List<Map<String, Object>> out = null;
    for (int i = 0; i < messages.size(); i++) {
      Map<String, Object> message = messages.get(i);
      @SuppressWarnings("unchecked")
      var escaped = (Map<String, Object>) escapeDeep(message, specials);
      if (escaped == message) {
        continue;
      }
      // First message needing an escape: copy the list, and only then diverge from the input.
      // Nothing to escape is the overwhelmingly common case, so it allocates nothing at all.
      if (out == null) {
        out = new ArrayList<>(messages);
      }
      out.set(i, escaped);
    }
    return out == null ? messages : out;
  }

  /**
   * Escapes every string reachable from {@code value}, returning the original instance when
   * nothing changed.
   *
   * <p>{@code content} is not the only caller-controlled text that reaches the prompt. A tool
   * call's name and arguments, a tool result's id and name, and the tool definitions themselves
   * are all serialized into the transcript by the chat template — so a control sequence hidden in
   * any of them is tokenized as a real transcript marker, which is the same forging avenue that
   * escaping {@code content} was meant to close. Walking the whole structure is the only way to
   * be sure a newly-passed field does not silently reopen it.
   *
   * <p>Map keys are escaped too: templates render tool argument names as text.
   */
  static Object escapeDeep(Object value, List<String> specials) {
    if (value instanceof String text) {
      if (text.isEmpty()) {
        return text;
      }
      String escaped = escapeSpecials(text, specials);
      return escaped.equals(text) ? text : escaped;
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> copy = null;
      for (var entry : map.entrySet()) {
        Object key = escapeDeep(entry.getKey(), specials);
        Object escaped = escapeDeep(entry.getValue(), specials);
        if (escaped == entry.getValue() && key == entry.getKey()) {
          continue;
        }
        if (copy == null) {
          copy = new LinkedHashMap<>(map);
        }
        if (key != entry.getKey()) {
          copy.remove(entry.getKey());
        }
        copy.put(key, escaped);
      }
      return copy == null ? value : copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = null;
      for (int i = 0; i < list.size(); i++) {
        Object escaped = escapeDeep(list.get(i), specials);
        if (escaped == list.get(i)) {
          continue;
        }
        if (copy == null) {
          copy = new ArrayList<>(list);
        }
        copy.set(i, escaped);
      }
      return copy == null ? value : copy;
    }
    // Numbers, booleans and null have no text form a tokenizer could mistake for a marker.
    return value;
  }

  /** Visible for tests. */
  static String escapeSpecials(String text, List<String> specials) {
    String escaped = text;
    for (String special : specials) {
      if (special.length() < 2 || !escaped.contains(special)) {
        continue;
      }
      escaped = escaped.replace(special, special.charAt(0) + "\\" + special.substring(1));
    }
    return escaped;
  }

  /** The request's tools as template data (name/description) for extraction templates. */
  private static java.util.List<java.util.Map<String, Object>> toolsData(PipelineContext pctx) {
    // Request tools PLUS server-owned tools (todo tools): extraction templates
    // validate the called name against this list (phantom-call guard), so a
    // server tool missing here would be rejected as a hallucination.
    return java.util.stream.Stream.concat(
      pctx.tools().stream(),
      undeclaredServerTools(pctx).stream()
    )
      .map(t ->
        java.util.Map.<String, Object>of("name", t.getName(), "description", t.getDescription())
      )
      .toList();
  }

  /**
   * Server tools minus the DELEGABLE ones the caller declared itself: a client
   * that brings its own ask_user schema owns that tool for the request — the
   * model must see the client's schema, not two competing ones. Non-delegable
   * server tools (the plan tools) are always injected, even on a name clash.
   */
  private static java.util.List<ToolDefinition> undeclaredServerTools(PipelineContext pctx) {
    var declared = pctx
      .tools()
      .stream()
      .map(ToolDefinition::getName)
      .collect(java.util.stream.Collectors.toSet());
    return pctx
      .serverTools()
      .stream()
      .filter(t -> !(TodoTools.DELEGABLE.contains(t.getName()) && declared.contains(t.getName())))
      .toList();
  }

  /** Maps extracted calls to wire {@link io.gravitee.singularitee.protocol.ToolCall}s. */
  private static java.util.List<io.gravitee.singularitee.protocol.ToolCall> toWireToolCalls(
    java.util.List<ToolCallExtractor.ExtractedToolCall> extracted
  ) {
    return extracted
      .stream()
      .map(c ->
        io.gravitee.singularitee.protocol.ToolCall.newBuilder()
          // The id is born HERE, server-side: the client answers with this id
          // in function_call_output, and the stored conversation must replay
          // the call under the SAME id or the tool result pairs with the
          // wrong call on the next turn (observed live: a write result
          // attributed to set_todos, and the model re-writing forever).
          .setId("call_" + java.util.UUID.randomUUID().toString().replace("-", ""))
          .setName(c.name())
          .setArgumentsJson(c.argumentsJson())
          .addAllCoercibleArgs(c.coercibleArgs())
          .build()
      )
      .toList();
  }

  private static final String DEFAULT_TOOL_OPEN_TAG = "<tool_call>";
  private static final String DEFAULT_TOOL_CLOSE_TAG = "</tool_call>";

  /**
   * Appends the bare TOOL-channel payload (captured separately when the engine
   * suppresses tag markers) to the step's text output, re-wrapped in the step's
   * configured tool tags ({@code tool_call_tags}, defaulting to
   * {@code <tool_call>...</tool_call>}). This keeps the step output / appended
   * assistant turn identical to what tag-emitting engines produce, so chat
   * templates re-render prior tool calls correctly on later turns of a tool loop.
   * Empty tool payload (legacy tagged-text engines) returns {@code text} untouched.
   */
  static String withReWrappedToolCalls(String text, String toolPayload, InferStepConfig cfg) {
    if (toolPayload == null || toolPayload.isEmpty()) {
      return text;
    }
    String open = DEFAULT_TOOL_OPEN_TAG;
    String close = DEFAULT_TOOL_CLOSE_TAG;
    if (cfg != null && cfg.hasToolCallTags()) {
      var t = cfg.getToolCallTags();
      if (!t.getOpenTag().isBlank()) {
        open = t.getOpenTag();
        close = t.getCloseTag();
      }
    }
    StringBuilder sb = new StringBuilder(text);
    if (!text.isEmpty() && !text.endsWith("\n")) {
      sb.append('\n');
    }
    sb.append(open).append(toolPayload).append(close);
    return sb.toString();
  }

  // -----------------------------------------------------------------------
  // Jinja4j context building
  // -----------------------------------------------------------------------

  /**
   * Builds the complete Jinja4j rendering context from the pipeline context,
   * engine metadata, and per-step configuration. Delegates the shared base
   * (prompt, system, history, messages, generated_messages, verdicts, step
   * outputs) to {@link JinjaContextHelper}; only the engine-specific
   * variables and the per-step context overlay are added here.
   */
  private static Map<String, Object> buildJinjaContext(
    PipelineContext pctx,
    TextGenEngine tge,
    InferStepConfig cfg
  ) {
    Map<String, Object> ctx = JinjaContextHelper.buildBaseContext(pctx);

    // Chat template standard variables
    ctx.put("add_generation_prompt", true);
    ctx.put("bos_token", tge.bosToken() != null ? tge.bosToken() : "");
    ctx.put("eos_token", tge.eosToken() != null ? tge.eosToken() : "");

    // Tools — structured OpenAI format.
    // When the step sets `inject_tools: false` in YAML, we skip this entirely
    // so {{tools}} is undefined in raw templates and empty for chat templates.
    if (shouldInjectTools(cfg)) {
      var tools = ToolDefinitionConverter.toOpenAiMaps(injectableTools(pctx, cfg));
      if (tools != null) {
        // Tool names and descriptions are caller-supplied and rendered into the prompt, so they
        // are the same injection surface as message content.
        @SuppressWarnings("unchecked")
        var safeTools = (List<Map<String, Object>>) escapeDeep(tools, tge.specialTokenTexts());
        ctx.put("tools", safeTools);
      }
    }

    // Per-step extra variables from YAML context: block
    if (cfg.hasContext()) {
      JinjaContextHelper.mergeStepContext(ctx, cfg.getContext());
    }

    // Request-level reasoning_effort overrides any step-config default: the
    // step YAML pins a fallback, the caller decides per request.
    String reasoningEffort = pctx.get("reasoning_effort");
    if (reasoningEffort != null) {
      ctx.put("reasoning_effort", reasoningEffort);
    }

    return ctx;
  }

  /**
   * Returns whether the caller's tools should be exposed to this step.
   *
   * <p>Controlled by the optional {@code inject_tools} field on
   * {@link InferStepConfig}. Defaults to {@code true} when unset, so existing
   * workspaces continue to forward tools as before. When set to {@code false},
   * the step receives no tools — handy for response branches that should let
   * the model's native chat template handle everything EXCEPT tool injection.
   */
  private static boolean shouldInjectTools(InferStepConfig cfg) {
    return !cfg.hasInjectTools() || cfg.getInjectTools();
  }

  /**
   * Returns the caller's tools filtered by a tool-select shortlist when one
   * was written to the pipeline context ({@code KEY_SELECTED_TOOLS}).
   *
   * <ul>
   *   <li>No shortlist ({@code selectedTools() == null}): all tools —
   *       behavior identical to before tool selection existed.</li>
   *   <li>Empty shortlist: no tools are injected (conversational turn).</li>
   *   <li>Otherwise: only tools whose name is in the shortlist.</li>
   * </ul>
   */
  static List<ToolDefinition> injectableTools(PipelineContext pctx, InferStepConfig cfg) {
    var tools = pctx.tools();
    var selected = pctx.selectedTools();
    List<ToolDefinition> filtered;
    if (selected == null) {
      filtered = tools;
    } else if (selected.isEmpty()) {
      filtered = List.of();
    } else {
      filtered = tools
        .stream()
        .filter(t -> selected.contains(t.getName()))
        .toList();
    }
    var condensed = pctx.condensedToolDescriptions();
    if (condensed != null && filtered != null && !filtered.isEmpty()) {
      filtered = filtered
        .stream()
        .map(t -> withCondensedDescription(t, condensed))
        .toList();
    }
    // Server-owned tools (e.g. the todo tools) ride along by default —
    // tool_select shortlists and condensation never apply to them — unless the
    // step opts out (expose_server_tools: false, e.g. a prose-only summarize
    // step where a schema in the prompt only invites a call that can leak).
    boolean exposeServerTools = !cfg.hasExposeServerTools() || cfg.getExposeServerTools();
    var serverTools = exposeServerTools ? undeclaredServerTools(pctx) : List.<ToolDefinition>of();
    if (serverTools.isEmpty()) return filtered;
    var union = new ArrayList<ToolDefinition>();
    if (filtered != null) union.addAll(filtered);
    union.addAll(serverTools);
    return List.copyOf(union);
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_JSON_MAPPER =
    new com.fasterxml.jackson.databind.ObjectMapper();

  /**
   * Rewrites a {@link ToolDefinition} with its
   * condensed injection description (from a tool-select step with
   * {@code trim_descriptions}): sets {@code description} AND patches the
   * {@code description} inside the original tool template JSON — both the
   * nested {@code {type: function, function: {...}}} and the flat shape are
   * handled, mirroring PipelineRequestBuilder.buildToolDefinition. On template
   * parse failure the original template is kept. Tools without a condensed
   * entry are returned unchanged.
   */
  static ToolDefinition withCondensedDescription(
    ToolDefinition tool,
    Map<String, String> condensed
  ) {
    String description = condensed.get(tool.getName());
    if (description == null) return tool;
    var builder = tool.toBuilder().setDescription(description);
    String template = tool.getTemplate();
    if (!template.isEmpty()) {
      try {
        var root = TOOL_JSON_MAPPER.readTree(template);
        var functionNode = root.path("function");
        var target = functionNode.isObject() ? functionNode : root;
        if (target.isObject()) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) target).put("description", description);
          builder.setTemplate(TOOL_JSON_MAPPER.writeValueAsString(root));
        }
      } catch (com.fasterxml.jackson.core.JacksonException e) {
        LOGGER.debug(
          "tool '{}': template JSON unparseable — keeping original template: {}",
          tool.getName(),
          e.toString()
        );
      }
    }
    return builder.build();
  }

  /**
   * Converts resolved Jinja-shaped message maps ({@code [{role, content}]})
   * back into {@link ChatTurn}s for wire transport. Used when the engine has
   * no chat template yet and the structured conversation must be forwarded
   * for engine-side rendering. Unknown roles default to {@code USER}.
   */
  private static List<ChatTurn> toChatTurns(List<Map<String, Object>> messages) {
    var turns = new ArrayList<ChatTurn>(messages.size());
    for (var msg : messages) {
      ChatRole role;
      try {
        role = ChatRole.valueOf(String.valueOf(msg.get("role")).toUpperCase());
      } catch (IllegalArgumentException e) {
        role = ChatRole.USER;
      }
      turns.add(new ChatTurn(role, String.valueOf(msg.getOrDefault("content", ""))));
    }
    return turns;
  }

  /**
   * Resolves a Jinja2 template string against the given context.
   * Templates are compiled once and cached in the shared {@link JinjaRenderer}.
   */
  private String resolveJinjaString(String templateString, Map<String, Object> context) {
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace(
        "InferStep raw_template render — context:\n{}",
        JinjaContextHelper.dump(context, 200)
      );
    }
    return jinjaRenderer.render(templateString, "<step>", context);
  }

  // -----------------------------------------------------------------------
  // Request building
  // -----------------------------------------------------------------------

  private static TextGenRequest buildTextGenRequest(
    InferStepConfig cfg,
    String renderedPrompt,
    List<ChatTurn> wireMessages,
    SamplingParams requestOverrides,
    SamplingParams retryOverrides,
    // Resolved once in rxExecuteWithEngine (request override wins over the
    // loop-retry override, which wins over step sampling params) so the
    // context-window trim and the request agree.
    int maxTokens,
    String cacheKey,
    String reasoningEffort
  ) {
    var stepSp = cfg.hasSamplingParams()
      ? cfg.getSamplingParams()
      : SamplingParams.getDefaultInstance();

    float temperature = pickFloat(
      requestOverrides != null ? requestOverrides.getTemperature() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getTemperature() : 0,
        stepSp.getTemperature()
      )
    );
    float topP = pickFloat(
      requestOverrides != null ? requestOverrides.getTopP() : 0,
      pickFloat(retryOverrides != null ? retryOverrides.getTopP() : 0, stepSp.getTopP())
    );
    float presencePenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getPresencePenalty() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getPresencePenalty() : 0,
        stepSp.getPresencePenalty()
      )
    );
    float frequencyPenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getFrequencyPenalty() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getFrequencyPenalty() : 0,
        stepSp.getFrequencyPenalty()
      )
    );
    int seed = pick(
      requestOverrides != null ? requestOverrides.getSeed() : 0,
      pick(retryOverrides != null ? retryOverrides.getSeed() : 0, stepSp.getSeed())
    );

    io.gravitee.singularitee.inference.api.textgen.TagConfig reasoningTags = null;
    io.gravitee.singularitee.inference.api.textgen.TagConfig toolTags = null;
    if (cfg.hasReasoningTags()) {
      var t = cfg.getReasoningTags();
      if (!t.getOpenTag().isBlank()) {
        // EVERYTHING the proto carries, not just the primary pair. This is the
        // pipeline path — the one agents actually run through — and it silently
        // dropped the open alternatives and the repeatable flag, so Harmony's
        // commentary opener never reached the engine here and its header leaked
        // as "<|channel|>commentary<|message|>We need to..." while the direct
        // Infer path behaved. Two construction sites, one contract.
        reasoningTags = new io.gravitee.singularitee.inference.api.textgen.TagConfig(
          t.getOpenTag(),
          t.getCloseTag(),
          t.getOpenTagAlternativesList(),
          t.getCloseTagAlternativesList(),
          t.hasRepeatable() ? t.getRepeatable() : null
        );
      }
    }
    if (cfg.hasToolCallTags()) {
      var t = cfg.getToolCallTags();
      if (!t.getOpenTag().isBlank()) {
        toolTags = new io.gravitee.singularitee.inference.api.textgen.TagConfig(
          t.getOpenTag(),
          t.getCloseTag(),
          t.getOpenTagAlternativesList(),
          t.getCloseTagAlternativesList(),
          t.hasRepeatable() ? t.getRepeatable() : null
        );
      }
    }

    return new TextGenRequest(
      renderedPrompt,
      // Multimodal media extraction when prompt is set; the actual payload
      // (engine-side rendering) when prompt is null.
      wireMessages,
      maxTokens > 0 ? maxTokens : null,
      temperature > 0 ? temperature : null,
      topP > 0 ? topP : null,
      presencePenalty != 0 ? presencePenalty : null,
      frequencyPenalty != 0 ? frequencyPenalty : null,
      cfg.getStopList().isEmpty() ? null : cfg.getStopList(),
      seed > 0 ? seed : null,
      reasoningTags,
      toolTags,
      cfg.hasLora() ? cfg.getLora().getLoraName() : null,
      cfg.hasLora() ? cfg.getLora().getLoraPath() : null,
      // Per-step context: variables (enable_thinking, …). Only consulted by
      // the engine when it has to render `messages` itself (no pre-rendered
      // prompt); harmless otherwise. Request-level reasoning_effort overrides
      // the step-config value, mirroring buildJinjaContext.
      buildTemplateContext(cfg, reasoningEffort),
      cacheKey
    );
  }

  private static Map<String, Object> buildTemplateContext(
    InferStepConfig cfg,
    String reasoningEffort
  ) {
    if (!cfg.hasContext() && reasoningEffort == null) {
      return null;
    }
    Map<String, Object> ctx = new LinkedHashMap<>();
    if (cfg.hasContext()) {
      ctx.putAll(JinjaContextHelper.structToMap(cfg.getContext()));
    }
    if (reasoningEffort != null) {
      ctx.put("reasoning_effort", reasoningEffort);
    }
    return ctx;
  }

  /**
   * A {@link TokenCounter} backed by the engine's own tokenizer when it has
   * one ({@link TextGenEngine#countTokens} ≥ 0), falling back to the
   * chars-per-token estimator otherwise.
   */
  private static TokenCounter counterOf(TextGenEngine tge) {
    TokenCounter estimator = TokenCounter.estimator();
    return text -> {
      int exact = tge.countTokens(text);
      return exact >= 0 ? exact : estimator.count(text);
    };
  }

  private static int pick(int override, int fallback) {
    return override > 0 ? override : fallback;
  }

  private static float pickFloat(float override, float fallback) {
    return override != 0 ? override : fallback;
  }
}
