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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.engine.ToolDefinitionConverter;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import io.gravitee.singularitee.engine.tools.TodoTools;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.ToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the prompt an INFER step sends to its engine: resolves the step's
 * messages (raw template, YAML override, conversation passthrough or bare
 * prompt), merges the step system prompt into the conversation, trims older
 * turns to the model's context window, neutralises the model's special tokens
 * in message text, and renders the chat template.
 *
 * <p>Produces either a fully rendered prompt string or — when no chat template
 * is available — the structured messages to forward for engine-side rendering,
 * never both.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class PromptAssembler {

  private static final Logger LOGGER = LoggerFactory.getLogger(PromptAssembler.class);

  static final ObjectMapper TOOL_JSON_MAPPER = new ObjectMapper();

  private final JinjaRenderer jinjaRenderer;
  private final Jinja4jChatTemplateRenderer chatTemplateRenderer;

  PromptAssembler(JinjaRenderer jinjaRenderer, Jinja4jChatTemplateRenderer chatTemplateRenderer) {
    this.jinjaRenderer = jinjaRenderer;
    this.chatTemplateRenderer = chatTemplateRenderer;
  }

  /**
   * The assembly result: exactly one of {@code renderedPrompt} (template
   * rendered server-side) or a null prompt with {@code wireMessages} carrying
   * the structured conversation for engine-side rendering. {@code wireMessages}
   * always holds the turns to put on the wire (media extraction when the
   * prompt is set).
   */
  record AssembledPrompt(String renderedPrompt, List<ChatTurn> wireMessages) {}

  /**
   * Assembles the prompt for one INFER step execution.
   *
   * @param maxTokens the effective completion reservation, already resolved
   *                  across request/retry/step sampling params so the
   *                  context-window trim and the request agree
   */
  AssembledPrompt assemble(
    String stepId,
    InferStepConfig cfg,
    TextGenEngine tge,
    PipelineContext pctx,
    int maxTokens
  ) {
    Map<String, Object> jinjaCtx = buildJinjaContext(pctx, tge, cfg);

    List<ChatTurn> originalMessages = pctx.messages(); // retained for multimodal

    if (!cfg.getRawTemplate().isBlank()) {
      // Raw template mode — resolve with Jinja4j, bypass chat template
      return new AssembledPrompt(
        resolveJinjaString(cfg.getRawTemplate(), jinjaCtx),
        originalMessages
      );
    }

    List<Map<String, Object>> messages = resolveMessages(cfg, pctx, jinjaCtx);
    messages = mergeSystemPrompt(cfg, messages, jinjaCtx);
    messages = trimToContextWindow(stepId, cfg, tge, messages, maxTokens);

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
      return new AssembledPrompt(
        renderChatTemplate(stepId, cfg, templateString, hasOverride, pctx, jinjaCtx),
        originalMessages
      );
    }

    // No chat template available (e.g. remote metadata not fetched yet).
    // Never degrade to plain "role: content" concatenation — that strips
    // all ChatML scaffolding (assistant header, <think> prefill) and
    // silently breaks models that depend on it. Ship the structured messages
    // instead: the engine forwards them and the model server renders
    // with its own authoritative template.
    LOGGER.error(
      "InferStep '{}': model '{}' has no chat template — sending structured " +
        "messages for engine-side rendering (template variables like " +
        "enable_thinking are forwarded via template_context)",
      stepId,
      cfg.getModelId()
    );
    // Passthrough case: keep the caller's original turns so multimodal
    // media survives (the resolved maps carry role/content only). YAML-
    // defined messages and bare prompts never carry media — convert those.
    List<ChatTurn> wireMessages = (cfg.getMessagesList().isEmpty() && originalMessages != null)
      ? originalMessages
      : toChatTurns(messages);
    return new AssembledPrompt(null, wireMessages);
  }

  /** Resolves the step's message list: YAML override, conversation passthrough or bare prompt. */
  private List<Map<String, Object>> resolveMessages(
    InferStepConfig cfg,
    PipelineContext pctx,
    Map<String, Object> jinjaCtx
  ) {
    if (!cfg.getMessagesList().isEmpty()) {
      // YAML overrides messages — resolve each content with Jinja4j
      return cfg
        .getMessagesList()
        .stream()
        .map(md -> {
          String resolvedContent = resolveJinjaString(md.getContent(), jinjaCtx);
          return Map.<String, Object>of("role", md.getRole(), "content", resolvedContent);
        })
        .toList();
    }
    if (pctx.messages() != null) {
      // Implicit passthrough — caller's messages as-is
      return pctx.messages().stream().map(PromptAssembler::toTemplateMessage).toList();
    }
    // Bare prompt fallback
    String bare = pctx.get(PipelineContext.KEY_PROMPT);
    return List.of(Map.of("role", "user", "content", bare != null ? bare : ""));
  }

  /**
   * Merges the step system prompt (yaml {@code system:}) into the message
   * list. It is Jinja-resolved so live pipeline state ({{todos}}, {{prompt}},
   * step outputs) can steer a step WITHOUT replacing the conversation the way
   * a prompt.messages override does. It COMBINES with a caller-supplied system
   * message rather than yielding to it: the caller's prompt establishes
   * identity, the step's establishes its role in the graph — dropping either
   * one loses instructions the request depends on.
   */
  private List<Map<String, Object>> mergeSystemPrompt(
    InferStepConfig cfg,
    List<Map<String, Object>> messages,
    Map<String, Object> jinjaCtx
  ) {
    String systemPrompt = cfg.getSystemPrompt().isBlank()
      ? ""
      : resolveJinjaString(cfg.getSystemPrompt(), jinjaCtx);
    if (systemPrompt.isBlank()) {
      return messages;
    }
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
      return withSystem;
    }
    // Merge into the caller's system turn (caller first): some chat
    // templates only honor a single system message.
    List<Map<String, Object>> merged = new ArrayList<>(messages);
    Map<String, Object> sys = new LinkedHashMap<>(merged.get(callerSystem));
    sys.put("content", sys.get("content") + "\n\n" + systemPrompt);
    merged.set(callerSystem, sys);
    return merged;
  }

  /**
   * Trims older turns so the prompt + completion reservation fits the model's
   * context window. Enabled unless {@code trim_history} is explicitly false,
   * and only when the engine reports its window size.
   */
  private static List<Map<String, Object>> trimToContextWindow(
    String stepId,
    InferStepConfig cfg,
    TextGenEngine tge,
    List<Map<String, Object>> messages,
    int maxTokens
  ) {
    boolean trimHistory = !cfg.hasTrimHistory() || cfg.getTrimHistory();
    if (!trimHistory || tge.contextSize() <= 0) {
      return messages;
    }
    String toolOpenTag = (cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank())
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
    }
    return trimmed;
  }

  /** Renders the chat template over the prepared Jinja context. */
  private String renderChatTemplate(
    String stepId,
    InferStepConfig cfg,
    String templateString,
    boolean hasOverride,
    PipelineContext pctx,
    Map<String, Object> jinjaCtx
  ) {
    List<Map<String, Object>> tools = shouldInjectTools(cfg)
      ? ToolDefinitionConverter.toOpenAiMaps(injectableTools(pctx, cfg))
      : null;
    try {
      return chatTemplateRenderer.render(templateString, null, tools, true, jinjaCtx);
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
    String reasoningEffort = pctx.get(PipelineContext.KEY_REASONING_EFFORT);
    if (reasoningEffort != null) {
      ctx.put(PipelineContext.KEY_REASONING_EFFORT, reasoningEffort);
    }

    return ctx;
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
  // Tool injection
  // -----------------------------------------------------------------------

  /**
   * Returns whether the caller's tools should be exposed to this step.
   *
   * <p>Controlled by the optional {@code inject_tools} field on
   * {@link InferStepConfig}. Defaults to {@code true} when unset, so existing
   * workspaces continue to forward tools as before. When set to {@code false},
   * the step receives no tools — handy for response branches that should let
   * the model's native chat template handle everything EXCEPT tool injection.
   */
  static boolean shouldInjectTools(InferStepConfig cfg) {
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

  /**
   * Server tools minus the DELEGABLE ones the caller declared itself: a client
   * that brings its own ask_user schema owns that tool for the request — the
   * model must see the client's schema, not two competing ones. Non-delegable
   * server tools (the plan tools) are always injected, even on a name clash.
   */
  static List<ToolDefinition> undeclaredServerTools(PipelineContext pctx) {
    var declared = pctx.tools().stream().map(ToolDefinition::getName).collect(Collectors.toSet());
    return pctx
      .serverTools()
      .stream()
      .filter(t -> !(TodoTools.DELEGABLE.contains(t.getName()) && declared.contains(t.getName())))
      .toList();
  }

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
          ((ObjectNode) target).put("description", description);
          builder.setTemplate(TOOL_JSON_MAPPER.writeValueAsString(root));
        }
      } catch (JacksonException e) {
        LOGGER.debug(
          "tool '{}': template JSON unparseable — keeping original template: {}",
          tool.getName(),
          e.toString()
        );
      }
    }
    return builder.build();
  }

  // -----------------------------------------------------------------------
  // Message shaping
  // -----------------------------------------------------------------------

  /**
   * Renders one transcript turn into the OpenAI shape chat templates expect.
   *
   * <p>{@code tool_calls} and the {@code tool_call_id} of a tool result are what let a template
   * show the model what it already did. Omit them and a tool-using conversation replays as a
   * question the assistant never acted on, so it issues the same call again — and again.
   *
   * <p>Arguments are handed over as a parsed map, not as their JSON text: templates iterate them
   * as a mapping, and some raise outright on a string. Unparseable arguments degrade to an
   * empty map rather than failing the render.
   */
  static Map<String, Object> toTemplateMessage(ChatTurn turn) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", turn.role().name().toLowerCase(Locale.ROOT));
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
        ? TOOL_JSON_MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {})
        : Map.of();
    } catch (Exception e) {
      LOGGER.debug("Unparseable tool-call arguments, rendering as empty: {}", e.getMessage());
      return Map.of();
    }
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
        role = ChatRole.valueOf(String.valueOf(msg.get("role")).toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        role = ChatRole.USER;
      }
      turns.add(new ChatTurn(role, String.valueOf(msg.getOrDefault("content", ""))));
    }
    return turns;
  }

  // -----------------------------------------------------------------------
  // Special-token neutralisation
  // -----------------------------------------------------------------------

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
}
