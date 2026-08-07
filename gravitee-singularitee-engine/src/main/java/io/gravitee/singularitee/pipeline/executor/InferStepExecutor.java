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
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.StepRole;
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

    // ── 1. Build the Jinja4j rendering context ─────────────────────────
    Map<String, Object> jinjaCtx = buildJinjaContext(pctx, tge, cfg);

    // Effective completion reservation, resolved once (request override wins
    // over the step's sampling params) and reused for both the context-window
    // trim below and the TextGenRequest.
    var requestOverrides = pctx.requestSamplingParams();
    var stepSp = cfg.hasSamplingParams()
      ? cfg.getSamplingParams()
      : SamplingParams.getDefaultInstance();
    int maxTokens = pick(
      requestOverrides != null ? requestOverrides.getMaxTokens() : 0,
      stepSp.getMaxTokens()
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

      // Default system prompt (yaml `system:`): prepended only when the request
      // carries none — a caller-supplied system message always wins.
      String systemPrompt = cfg.getSystemPrompt();
      if (
        !systemPrompt.isBlank() && messages.stream().noneMatch(m -> "system".equals(m.get("role")))
      ) {
        List<Map<String, Object>> withSystem = new java.util.ArrayList<>();
        withSystem.add(Map.of("role", "system", "content", systemPrompt));
        withSystem.addAll(messages);
        messages = withSystem;
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
          ? ToolDefinitionConverter.toOpenAiMaps(injectableTools(pctx))
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
      maxTokens,
      pctx.cacheKey(),
      pctx.get("reasoning_effort")
    );

    // ── 4. Stream tokens (unchanged from before) ───────────────────────
    StepRole role = STEP_ROLE.get();
    STEP_ROLE.remove();
    boolean shouldStream = role != StepRole.STEP_ROLE_INTERNAL;
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
        wireRole,
        thinkingMode,
        finalOpenTag,
        finalCloseTag
      );
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
            int msgCountBefore = pctx.messages() != null ? pctx.messages().size() : 0;
            pctx.appendMessage(new ChatTurn(ChatRole.ASSISTANT, stepOutput));
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
          }
        }

        var captureStream = captureStreamHolder[0];
        if (captureStream != null) {
          var lastResp = captureStream.lastResponse();
          if (
            lastResp != null &&
            lastResp.getEventType() ==
            io.gravitee.singularitee.protocol.ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED
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
            if (
              completed.getFinishReason() !=
              io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_UNSPECIFIED
            ) {
              pctx.setLastEngineFinishReason(completed.getFinishReason());
            }
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
          pctx.lastEngineFinishReason() ==
          io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_TOOL_CALLS;
        if (toolCandidate) {
          String extractionInput = !bareToolSpan.isEmpty() ? bareToolSpan : stepOutput;
          var extracted = io.gravitee.singularitee.engine.tools.ToolCallExtractor.extract(
            extractionInput,
            toolsData(pctx),
            cfg.getToolExtractionTemplate()
          );
          pctx.setExtractedToolCalls(toWireToolCalls(extracted));
        } else {
          maybeExtractMarkerlessToolCalls(pctx, cfg, stepOutput);
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
  static void maybeExtractMarkerlessToolCalls(
    PipelineContext pctx,
    InferStepConfig cfg,
    String stepOutput
  ) {
    boolean markerBased = cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank();
    if (
      pctx.tools().isEmpty() ||
      cfg.getToolExtractionTemplate().isBlank() ||
      markerBased ||
      pctx.lastEngineFinishReason() !=
      io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_STOP
    ) {
      return;
    }
    var extracted = io.gravitee.singularitee.engine.tools.ToolCallExtractor.extract(
      stepOutput,
      toolsData(pctx),
      cfg.getToolExtractionTemplate()
    );
    if (!extracted.isEmpty()) {
      pctx.setExtractedToolCalls(toWireToolCalls(extracted));
      pctx.setLastEngineFinishReason(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_TOOL_CALLS
      );
    }
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
    Map<String, Object> message = new java.util.LinkedHashMap<>();
    message.put("role", turn.role().name().toLowerCase());
    message.put("content", turn.content() == null ? "" : turn.content());
    if (!turn.toolCalls().isEmpty()) {
      message.put(
        "tool_calls",
        turn
          .toolCalls()
          .stream()
          .map(call -> {
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", parseArguments(call.argumentsJson()));
            Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
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
    return pctx
      .tools()
      .stream()
      .map(t ->
        java.util.Map.<String, Object>of("name", t.getName(), "description", t.getDescription())
      )
      .toList();
  }

  /** Maps extracted calls to wire {@link io.gravitee.singularitee.protocol.ToolCall}s. */
  private static java.util.List<io.gravitee.singularitee.protocol.ToolCall> toWireToolCalls(
    java.util.List<
      io.gravitee.singularitee.engine.tools.ToolCallExtractor.ExtractedToolCall
    > extracted
  ) {
    return extracted
      .stream()
      .map(c ->
        io.gravitee.singularitee.protocol.ToolCall.newBuilder()
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
      var tools = ToolDefinitionConverter.toOpenAiMaps(injectableTools(pctx));
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
  static List<io.gravitee.singularitee.protocol.ToolDefinition> injectableTools(
    PipelineContext pctx
  ) {
    var tools = pctx.tools();
    var selected = pctx.selectedTools();
    List<io.gravitee.singularitee.protocol.ToolDefinition> filtered;
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
    if (condensed == null || filtered == null || filtered.isEmpty()) return filtered;
    return filtered
      .stream()
      .map(t -> withCondensedDescription(t, condensed))
      .toList();
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_JSON_MAPPER =
    new com.fasterxml.jackson.databind.ObjectMapper();

  /**
   * Rewrites a {@link io.gravitee.singularitee.protocol.ToolDefinition} with its
   * condensed injection description (from a tool-select step with
   * {@code trim_descriptions}): sets {@code description} AND patches the
   * {@code description} inside the original tool template JSON — both the
   * nested {@code {type: function, function: {...}}} and the flat shape are
   * handled, mirroring PipelineRequestBuilder.buildToolDefinition. On template
   * parse failure the original template is kept. Tools without a condensed
   * entry are returned unchanged.
   */
  static io.gravitee.singularitee.protocol.ToolDefinition withCondensedDescription(
    io.gravitee.singularitee.protocol.ToolDefinition tool,
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
    // Resolved once in rxExecuteWithEngine (request override wins over step
    // sampling params) so the context-window trim and the request agree.
    int maxTokens,
    String cacheKey,
    String reasoningEffort
  ) {
    var stepSp = cfg.hasSamplingParams()
      ? cfg.getSamplingParams()
      : SamplingParams.getDefaultInstance();

    float temperature = pickFloat(
      requestOverrides != null ? requestOverrides.getTemperature() : 0,
      stepSp.getTemperature()
    );
    float topP = pickFloat(
      requestOverrides != null ? requestOverrides.getTopP() : 0,
      stepSp.getTopP()
    );
    float presencePenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getPresencePenalty() : 0,
      stepSp.getPresencePenalty()
    );
    float frequencyPenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getFrequencyPenalty() : 0,
      stepSp.getFrequencyPenalty()
    );
    int seed = pick(requestOverrides != null ? requestOverrides.getSeed() : 0, stepSp.getSeed());

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
