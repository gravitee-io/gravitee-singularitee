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

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.InferencePerformance;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.TokenUsage;
import io.gravitee.singularitee.protocol.ToolCall;
import io.gravitee.singularitee.protocol.ToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-execution scratchpad for a single pipeline run.
 *
 * <p>All step inputs and outputs are stored as string-valued entries keyed by
 * context field names (e.g. {@code "prompt"}, {@code "reason.output"},
 * {@code "quality.label"}).  Non-string values (scores, embeddings) are also
 * stored as strings: scores as decimal strings, float arrays as JSON arrays.
 *
 * <p>Additionally carries:
 * <ul>
 *   <li>A monotonically-increasing {@code stepIndex} counter shared across
 *       the whole run (used to set {@code InferResponse.step_index}).</li>
 *   <li>A per-step {@code iterationCounters} map for bounded loop tracking.</li>
 *   <li>An optional {@code breakOutputField} set when a Break or Guard step
 *       fires, pointing to the context field whose value should be returned as
 *       the final response.</li>
 * </ul>
 *
 * <p><strong>Threading contract:</strong> one instance exists per pipeline request, and the
 * DAG walk executes steps strictly sequentially — the plain collections ({@code fields},
 * {@code generatedMessages}, {@code verdicts}, {@code iterationCounters}) are only ever
 * mutated between steps, where the reactive chain provides the happens-before edge across
 * any thread hop. The {@code volatile} scalars and the {@code synchronized} todo/server-tool
 * accessors exist because those members are additionally touched from streaming callbacks
 * (capture streams, progress emission) that may run concurrently with a step. Do not mutate
 * the plain collections from a callback.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class PipelineContext {

  /** The entry prompt from {@code InferPipelineRequest}. */
  public static final String KEY_PROMPT = "prompt";

  /**
   * Request-level reasoning effort, seeded from the request's context. Also the
   * template variable name under which it is exposed to Jinja rendering.
   */
  public static final String KEY_REASONING_EFFORT = "reasoning_effort";

  /**
   * Per-request caller instructions (OpenAI Responses {@code instructions}),
   * seeded from the request's context. Injected as a system turn at prompt
   * render time only — never part of the transcript, so a stored conversation
   * does not carry them over and each continuation's own instructions apply.
   */
  public static final String KEY_INSTRUCTIONS = "instructions";

  /** Written by a guard step when its action is {@code WARN} or {@code REDACT}. */
  public static final String KEY_GUARD_TRIGGERED = "__guard_triggered";

  /**
   * Written by a tool-select step: the shortlist of tool names the downstream
   * infer step should inject. Stored in the string field map as a comma-joined
   * list (for templates/debugging); the typed view is {@link #selectedTools()}.
   * Absent (null) = no selection ran → inject all tools. Empty list = the
   * selector decided no tools are needed → inject none.
   */
  public static final String KEY_SELECTED_TOOLS = "__selected_tools";

  /**
   * Written by a tool-select step when {@code trim_descriptions} is enabled:
   * condensed injection descriptions keyed by tool name. Stored in the string
   * field map as {@code name=description} pairs joined with {@code ;} (for
   * templates/debugging); the typed view is {@link #condensedToolDescriptions()}.
   * Absent (null) = no trimming ran → inject original descriptions.
   */
  public static final String KEY_CONDENSED_TOOL_DESCRIPTIONS = "__condensed_tool_descriptions";

  /**
   * Stuck-call signal seeded by {@code PipelineExecutor} on todo pipelines:
   * the length of the trailing run of identical assistant tool calls.
   */
  public static final String KEY_REPEATED_CALL = "conversation.repeated_call";

  /** Mirrored todo-plan scalar: total item count. */
  public static final String KEY_TODOS_TOTAL = "todos.total";

  /** Mirrored todo-plan scalar: {@code done} item count. */
  public static final String KEY_TODOS_COMPLETED = "todos.completed";

  /** Mirrored todo-plan scalar: not-yet-{@code done} item count. */
  public static final String KEY_TODOS_REMAINING = "todos.remaining";

  /**
   * An assistant message produced by a pipeline step.
   */
  public record GeneratedMessage(String stepId, String content) {}

  /**
   * A safety verdict produced by a guard step. Accumulated in execution order.
   * {@code details} carries type-specific data: the full verdict text for
   * LLM guards, a decimal score string for classifier guards.
   */
  public record VerdictMessage(String stepId, String verdict, String details) {}

  /** One item of the engine-managed todo plan (see {@code STEP_TYPE_TODO}). */
  public record TodoItem(String id, String title, TodoStatus status, String proof) {}

  // Use a LinkedHashMap so snapshot() iterates in insertion (execution) order.
  private final Map<String, String> fields;
  private final Map<String, Integer> iterationCounters = new HashMap<>();
  private final List<GeneratedMessage> generatedMessages = new ArrayList<>();
  private final List<VerdictMessage> verdicts = new ArrayList<>();
  private final List<TodoItem> todos = new ArrayList<>();
  // See setTodos/setPlanLocked: install locks, restore-time policy unlocks.
  private volatile boolean planLocked;

  /**
   * Tool definitions owned and executed by the SERVER (e.g. the todo tools),
   * injected into every infer step's tool list in addition to the caller's
   * tools. Never subject to tool_select filtering, never returned to the
   * client as pending calls.
   */
  private final List<ToolDefinition> serverTools = new ArrayList<>();

  /**
   * The chat messages from the caller. Mutable — may be updated by a guard
   * step that redacts PII spans before passing to the LLM.
   * Null when the caller sent a flat prompt string instead.
   */
  private volatile List<ChatTurn> messages;

  /**
   * Request-level sampling params from the caller.
   * These override any step-level defaults in the pipeline definition.
   * May be {@code null} if the caller didn't provide any.
   */
  private final SamplingParams requestSamplingParams;

  /**
   * Sampling override installed by a loop step on its RETRY edge (see
   * {@code LoopStepConfig.retry_sampling_params}) and cleared when the loop exits — happy
   * path or fallback — so it never leaks past the loop. Precedence in infer steps:
   * request override > this > step params. {@code null} = no active retry override.
   */
  private volatile SamplingParams retrySamplingParams;

  /**
   * Tool definitions provided at runtime by the caller.
   * Available in prompt templates via the {@code {{tools}}} expression.
   * Empty list when the caller didn't provide any tools.
   */
  private final List<ToolDefinition> tools;

  /**
   * Opaque client cache-affinity key from {@code InferPipelineRequest.cache_key}
   * (OpenAI {@code prompt_cache_key} / {@code user}). Threaded to text-gen steps
   * so the engine's KV prefix cache can route requests sharing a key to the same
   * slot. {@code null} = no affinity.
   */
  private volatile String cacheKey;

  /** Non-null when the pipeline should halt and return the named field. */
  private volatile String breakOutputField = null;
  /** The finish reason to emit on halt. */
  private volatile FinishReason haltReason = null;
  /**
   * The finish reason reported by the last inference step's engine.
   * Propagated to the final pipeline response so callers can distinguish
   * between normal stop, tool calls, length limits, etc.
   */
  private volatile FinishReason lastEngineFinishReason = null;
  /**
   * Optional human-readable message to include in the {@code guard_message} field
   * of the final {@link InferResponse} when a guard
   * step rejects the request.
   */
  private volatile String haltMessage = null;

  public PipelineContext(
    String prompt,
    List<ChatTurn> messages,
    SamplingParams requestSamplingParams,
    List<ToolDefinition> tools,
    Map<String, String> seed
  ) {
    this.fields = new LinkedHashMap<>(seed == null ? Map.of() : seed);
    this.messages = messages;
    this.requestSamplingParams = requestSamplingParams;
    this.tools = tools != null ? tools : List.of();
    if (prompt != null) this.fields.put(KEY_PROMPT, prompt);
  }

  /**
   * Creates a {@link PipelineContext} from a gRPC {@link InferPipelineRequest}.
   *
   * <p>Handles the three input modes:
   * <ol>
   *   <li>Chat messages — converts proto messages to {@link ChatTurn}s,
   *       extracts last user message as flat prompt</li>
   *   <li>Flat prompt string</li>
   *   <li>Empty (no input)</li>
   * </ol>
   */
  public static PipelineContext fromRequest(InferPipelineRequest request) {
    String prompt;
    List<ChatTurn> chatMessages = null;

    if (request.hasMessages()) {
      chatMessages = request
        .getMessages()
        .getMessagesList()
        .stream()
        .map(m -> {
          var role = switch (m.getRole()) {
            case ROLE_SYSTEM -> ChatRole.SYSTEM;
            case ROLE_ASSISTANT -> ChatRole.ASSISTANT;
            case ROLE_TOOL -> ChatRole.TOOL;
            default -> ChatRole.USER;
          };
          return new ChatTurn(
            role,
            m.getContent(),
            List.of(),
            m
              .getToolCallsList()
              .stream()
              .map(tc -> new ChatTurn.ToolCallTurn(tc.getId(), tc.getName(), tc.getArgumentsJson()))
              .toList(),
            m.getToolCallId().isEmpty() ? null : m.getToolCallId(),
            m.getName().isEmpty() ? null : m.getName()
          );
        })
        .toList();
      prompt = ChatTurn.lastUserContent(chatMessages).orElse("");
    } else if (request.hasPrompt()) {
      prompt = request.getPrompt();
      chatMessages = List.of(new ChatTurn(ChatRole.USER, prompt));
    } else {
      prompt = "";
    }

    PipelineContext ctx = new PipelineContext(
      prompt,
      chatMessages,
      request.hasSamplingParams() ? request.getSamplingParams() : null,
      request.getToolsList(),
      request.getContextMap()
    );
    if (!request.getCacheKey().isEmpty()) {
      ctx.setCacheKey(request.getCacheKey());
    }
    return ctx;
  }

  /** Sets the client cache-affinity key (see {@link #cacheKey()}). */
  public void setCacheKey(String cacheKey) {
    this.cacheKey = cacheKey;
  }

  /** Returns the client cache-affinity key, or {@code null} if none. */
  public String cacheKey() {
    return cacheKey;
  }

  /**
   * Returns the chat messages, or {@code null} if the caller
   * sent a flat prompt string.
   */
  public List<ChatTurn> messages() {
    return messages;
  }

  /**
   * Replaces the chat messages (e.g. after PII redaction).
   *
   * @param messages the updated message list
   */
  public void setMessages(List<ChatTurn> messages) {
    this.messages = messages;
  }

  /**
   * Appends a message to the conversation history (for chain-of-thought).
   * If messages is {@code null}, initialises the list first.
   *
   * @param turn the chat turn to append
   */
  public void appendMessage(ChatTurn turn) {
    if (this.messages == null) {
      this.messages = new ArrayList<>();
    } else if (!(this.messages instanceof ArrayList)) {
      // Replace unmodifiable list (e.g. from List.of()) with a mutable copy
      this.messages = new ArrayList<>(this.messages);
    }
    this.messages.add(turn);
  }

  /**
   * Converts the internal {@link ChatTurn} list
   * to a protobuf {@link ChatMessageList}.
   *
   * @return the proto message list, or an empty list if messages is {@code null}
   */
  public ChatMessageList toChatMessageList() {
    var builder = ChatMessageList.newBuilder();
    if (messages != null) {
      for (var turn : messages) {
        var role = switch (turn.role()) {
          case SYSTEM -> Role.ROLE_SYSTEM;
          case ASSISTANT -> Role.ROLE_ASSISTANT;
          case TOOL -> Role.ROLE_TOOL;
          default -> Role.ROLE_USER;
        };
        var msg = ChatMessage.newBuilder()
          .setRole(role)
          .setContent(turn.content() == null ? "" : turn.content());
        for (var call : turn.toolCalls()) {
          msg.addToolCalls(
            ToolCall.newBuilder()
              .setId(call.id() == null ? "" : call.id())
              .setName(call.name())
              .setArgumentsJson(call.argumentsJson())
              .build()
          );
        }
        if (turn.toolCallId() != null) {
          msg.setToolCallId(turn.toolCallId());
        }
        if (turn.name() != null) {
          msg.setName(turn.name());
        }
        builder.addMessages(msg.build());
      }
    }
    return builder.build();
  }

  /**
   * Returns the request-level sampling params, or {@code null} if not provided.
   */
  public SamplingParams requestSamplingParams() {
    return requestSamplingParams;
  }

  /** Returns the active loop-retry sampling override, or {@code null} when none is active. */
  public SamplingParams retrySamplingParams() {
    return retrySamplingParams;
  }

  /** Installs ({@code null} clears) the loop-retry sampling override. */
  public void setRetrySamplingParams(SamplingParams params) {
    this.retrySamplingParams = params;
  }

  /**
   * Returns the tool definitions provided at runtime by the caller.
   * Empty list (never {@code null}) when the caller didn't provide any tools.
   */
  public List<ToolDefinition> tools() {
    return tools;
  }

  /**
   * Shortlist of tool names written by a tool-select step.
   * {@code null} = no selection ran; empty = inject no tools.
   */
  private volatile List<String> selectedTools;

  /**
   * Records the tool shortlist produced by a tool-select step. Also mirrors it
   * into the string field map under {@link #KEY_SELECTED_TOOLS} (comma-joined)
   * so templates and debug snapshots can see it.
   */
  public void setSelectedTools(List<String> toolNames) {
    if (toolNames == null) return;
    this.selectedTools = List.copyOf(toolNames);
    this.fields.put(KEY_SELECTED_TOOLS, String.join(",", toolNames));
  }

  /**
   * Returns the tool shortlist, or {@code null} when no tool-select step ran
   * (meaning: inject all tools, pre-existing behavior).
   */
  public List<String> selectedTools() {
    return selectedTools;
  }

  /**
   * Condensed injection descriptions written by a tool-select step with
   * {@code trim_descriptions} enabled, keyed by tool name.
   * {@code null} = no trimming ran; missing key = keep original description.
   */
  private volatile Map<String, String> condensedToolDescriptions;

  /**
   * Records the condensed tool descriptions produced by a tool-select step.
   * Also mirrors them into the string field map under
   * {@link #KEY_CONDENSED_TOOL_DESCRIPTIONS} so templates and debug snapshots
   * can see them.
   */
  public void setCondensedToolDescriptions(Map<String, String> descriptionsByTool) {
    if (descriptionsByTool == null) return;
    this.condensedToolDescriptions = Map.copyOf(descriptionsByTool);
    this.fields.put(
      KEY_CONDENSED_TOOL_DESCRIPTIONS,
      descriptionsByTool
        .entrySet()
        .stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(java.util.stream.Collectors.joining(";"))
    );
  }

  /**
   * Returns the condensed tool descriptions, or {@code null} when no
   * tool-select step trimmed descriptions (inject originals).
   */
  public Map<String, String> condensedToolDescriptions() {
    return condensedToolDescriptions;
  }

  // ---------------------------------------------------------------------------
  // Field access
  // ---------------------------------------------------------------------------

  /** Returns the value of the named context field, or {@code null} if absent. */
  public String get(String key) {
    return fields.get(key);
  }

  /** Stores {@code value} under {@code key}. Null values are silently ignored. */
  public void set(String key, String value) {
    if (key != null && value != null) fields.put(key, value);
  }

  /** Returns a snapshot of all context fields (for logging / debugging). */
  public Map<String, String> snapshot() {
    return Map.copyOf(fields);
  }

  /**
   * Returns a pretty, truncated, human-readable dump of the entire pipeline
   * context — fields, messages, generated messages and verdicts — suitable
   * for DEBUG/TRACE logging around step execution.
   *
   * <p>Long string values are truncated to {@code maxValueChars} characters
   * with a {@code …} suffix so that big generated outputs don't flood the log.
   * Pass a large value (e.g. {@link Integer#MAX_VALUE}) to get everything.
   */
  public String debugSnapshot(int maxValueChars) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("PipelineContext{\n");

    sb.append("  fields (").append(fields.size()).append("):\n");
    for (var e : fields.entrySet()) {
      sb
        .append("    ")
        .append(e.getKey())
        .append(" = ")
        .append(truncate(e.getValue(), maxValueChars))
        .append('\n');
    }

    if (messages != null && !messages.isEmpty()) {
      sb.append("  messages (").append(messages.size()).append("):\n");
      for (var m : messages) {
        sb
          .append("    [")
          .append(m.role())
          .append("] ")
          .append(truncate(m.content(), maxValueChars))
          .append('\n');
      }
    }

    if (!generatedMessages.isEmpty()) {
      sb.append("  generatedMessages (").append(generatedMessages.size()).append("):\n");
      for (var m : generatedMessages) {
        sb
          .append("    [")
          .append(m.stepId())
          .append("] ")
          .append(truncate(m.content(), maxValueChars))
          .append('\n');
      }
    }

    if (!verdicts.isEmpty()) {
      sb.append("  verdicts (").append(verdicts.size()).append("):\n");
      for (var v : verdicts) {
        sb
          .append("    [")
          .append(v.stepId())
          .append("] ")
          .append(v.verdict())
          .append(" — ")
          .append(truncate(v.details(), maxValueChars))
          .append('\n');
      }
    }

    if (!iterationCounters.isEmpty()) {
      sb.append("  iterationCounters: ").append(iterationCounters).append('\n');
    }
    if (tools != null && !tools.isEmpty()) {
      sb.append("  tools (").append(tools.size()).append("): [");
      for (int i = 0; i < tools.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(tools.get(i).getName());
      }
      sb.append("]\n");
    }
    appendSamplingSummary(sb, requestSamplingParams);
    if (lastEngineFinishReason != null) {
      sb.append("  lastEngineFinishReason: ").append(lastEngineFinishReason).append('\n');
    }
    if (breakOutputField != null) {
      sb
        .append("  halted: field=")
        .append(breakOutputField)
        .append(" reason=")
        .append(haltReason)
        .append('\n');
    }
    if (haltMessage != null) {
      sb.append("  haltMessage: ").append(truncate(haltMessage, maxValueChars)).append('\n');
    }
    appendTokenUsage(sb);

    sb.append('}');
    return sb.toString();
  }

  /** Shortcut: {@code debugSnapshot(200)} — safe default for step logging. */
  public String debugSnapshot() {
    return debugSnapshot(200);
  }

  private static void appendSamplingSummary(StringBuilder sb, SamplingParams sp) {
    if (sp == null) return;
    StringBuilder parts = new StringBuilder();
    if (sp.getMaxTokens() > 0) appendPart(parts, "max_tokens=" + sp.getMaxTokens());
    if (sp.getTemperature() > 0) appendPart(parts, "temperature=" + sp.getTemperature());
    if (sp.getTopP() > 0) appendPart(parts, "top_p=" + sp.getTopP());
    if (sp.getPresencePenalty() != 0) appendPart(
      parts,
      "presence_penalty=" + sp.getPresencePenalty()
    );
    if (sp.getFrequencyPenalty() != 0) appendPart(
      parts,
      "frequency_penalty=" + sp.getFrequencyPenalty()
    );
    if (sp.getSeed() > 0) appendPart(parts, "seed=" + sp.getSeed());
    if (parts.length() > 0) {
      sb.append("  requestSamplingParams: {").append(parts).append("}\n");
    }
  }

  private void appendTokenUsage(StringBuilder sb) {
    if (
      totalPromptTokens == 0 &&
      totalCompletionTokens == 0 &&
      totalReasoningTokens == 0 &&
      totalToolTokens == 0 &&
      totalTokensGenerated == 0
    ) {
      return;
    }
    sb.append("  tokenUsage: {");
    StringBuilder parts = new StringBuilder();
    if (totalPromptTokens > 0) appendPart(parts, "prompt=" + totalPromptTokens);
    if (totalCompletionTokens > 0) appendPart(parts, "completion=" + totalCompletionTokens);
    if (totalReasoningTokens > 0) appendPart(parts, "reasoning=" + totalReasoningTokens);
    if (totalToolTokens > 0) appendPart(parts, "tool=" + totalToolTokens);
    if (totalTokensGenerated > 0) appendPart(parts, "generated=" + totalTokensGenerated);
    if (totalEvalTimeMs > 0) appendPart(parts, "eval_ms=" + totalEvalTimeMs);
    if (totalPromptEvalTimeMs > 0) appendPart(parts, "prompt_eval_ms=" + totalPromptEvalTimeMs);
    if (totalSamplingTimeMs > 0) appendPart(parts, "sampling_ms=" + totalSamplingTimeMs);
    sb.append(parts).append("}\n");
  }

  private static void appendPart(StringBuilder parts, String kv) {
    if (parts.length() > 0) parts.append(", ");
    parts.append(kv);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "null";
    if (s.length() <= max) return s;
    return s.substring(0, max) + "…(+" + (s.length() - max) + " chars)";
  }

  // ---------------------------------------------------------------------------
  // Generated messages & verdicts (accumulated in execution order)
  // ---------------------------------------------------------------------------

  /**
   * Appends a generated message produced by an inference step to the
   * conversation log. Called alongside the per-step {@code step_id.output}
   * {@link #set}: the latter keeps only the latest value (overwritten on
   * each CoT iteration), while this log preserves every iteration for
   * downstream steps that want to inspect the full reasoning chain.
   */
  public void addGeneratedMessage(String stepId, String content) {
    if (stepId == null || content == null) return;
    generatedMessages.add(new GeneratedMessage(stepId, content));
  }

  /**
   * Appends a safety verdict produced by a guard step. Verdicts are kept in a
   * separate list from {@link #generatedMessages()} so that templates can
   * distinguish assistant outputs from safety metadata.
   */
  public void addVerdict(String stepId, String verdict, String details) {
    if (stepId == null || verdict == null) return;
    verdicts.add(new VerdictMessage(stepId, verdict, details));
  }

  /** Returns an immutable view of all generated messages in execution order. */
  public List<GeneratedMessage> generatedMessages() {
    return Collections.unmodifiableList(generatedMessages);
  }

  /** Returns an immutable view of all verdicts in execution order. */
  public List<VerdictMessage> verdicts() {
    return Collections.unmodifiableList(verdicts);
  }

  // ---------------------------------------------------------------------------
  // Todo plan (STEP_TYPE_TODO)
  // ---------------------------------------------------------------------------

  /**
   * Replaces the engine-managed todo plan. The first item is promoted to
   * {@code in_progress} if every incoming item is {@code pending}. Mirrors
   * {@code todos.total} / {@code todos.completed} / {@code todos.remaining}
   * into the fields map — conditions only read the flat String map.
   */
  public synchronized void setTodos(List<TodoItem> items) {
    todos.clear();
    if (items != null) {
      todos.addAll(items);
    }
    // Installing a plan locks it: set_todos is refused until the lock is
    // lifted, which only happens at request restore when the plan is
    // finished and a fresh user message arrived. Plans are authored on
    // human input, never chained mid-run.
    planLocked = !todos.isEmpty();
    // Keep exactly one item active: when nothing is in_progress, promote the
    // first pending item (done items never regress).
    boolean anyInProgress = todos.stream().anyMatch(t -> t.status() == TodoStatus.IN_PROGRESS);
    if (!anyInProgress) {
      for (int i = 0; i < todos.size(); i++) {
        TodoItem t = todos.get(i);
        if (t.status() == TodoStatus.PENDING) {
          todos.set(i, new TodoItem(t.id(), t.title(), TodoStatus.IN_PROGRESS, t.proof()));
          break;
        }
      }
    }
    mirrorTodoFields();
  }

  /**
   * Marks the item with the given id {@code done} and promotes the next
   * {@code pending} item to {@code in_progress}. Returns {@code false} when
   * no item carries that id (the caller reports the miss to the model).
   *
   * <p>{@code proof} is the completion evidence (the tool call's {@code note});
   * it replaces the item's proof when non-blank. Because an internal work
   * step's prose is never appended to the conversation, this proof is the only
   * durable record of the item's result.
   */
  public synchronized boolean completeTodo(String id, String proof) {
    boolean found = false;
    for (int i = 0; i < todos.size(); i++) {
      TodoItem t = todos.get(i);
      if (t.id().equals(id)) {
        todos.set(i, completed(t, proof));
        found = true;
        break;
      }
    }
    if (!found) {
      // A model resuming a long session may have lost the plan install to
      // context trimming and guess an id. When exactly one item is
      // in_progress there is no ambiguity about which item it means.
      List<Integer> active = new ArrayList<>();
      for (int i = 0; i < todos.size(); i++) {
        if (todos.get(i).status() == TodoStatus.IN_PROGRESS) active.add(i);
      }
      if (active.size() == 1) {
        int i = active.get(0);
        todos.set(i, completed(todos.get(i), proof));
        found = true;
      }
    }
    if (found) {
      boolean anyActive = todos.stream().anyMatch(t -> t.status() == TodoStatus.IN_PROGRESS);
      if (!anyActive) {
        for (int i = 0; i < todos.size(); i++) {
          TodoItem t = todos.get(i);
          if (t.status() == TodoStatus.PENDING) {
            todos.set(i, new TodoItem(t.id(), t.title(), TodoStatus.IN_PROGRESS, t.proof()));
            break;
          }
        }
      }
      mirrorTodoFields();
    }
    return found;
  }

  private static TodoItem completed(TodoItem t, String proof) {
    String kept = (proof != null && !proof.isBlank()) ? proof : t.proof();
    return new TodoItem(t.id(), t.title(), TodoStatus.DONE, kept);
  }

  /** Returns an immutable snapshot of the todo plan. */
  public synchronized List<TodoItem> todos() {
    return List.copyOf(todos);
  }

  /** Whether set_todos is currently refused (see setTodos for the policy). */
  public boolean isPlanLocked() {
    return planLocked;
  }

  public void setPlanLocked(boolean locked) {
    this.planLocked = locked;
  }

  /**
   * Plan-level constraints: the locked user decisions (tools, language,
   * frameworks) recorded by {@code set_todos} and re-injected into every step
   * prompt via the {@code constraints} Jinja variable. Deliberately NOT
   * mirrored into the flat field map — a {@code todos.}-prefixed scalar would
   * be nested under the {@code todos} template key and clobbered by the item
   * list (see {@code JinjaContextHelper}).
   */
  private volatile String todoConstraints;

  /** The plan-level constraints paragraph, or {@code null} when none recorded. */
  public String todoConstraints() {
    return todoConstraints;
  }

  /** Records the plan-level constraints; blank normalizes to {@code null}. */
  public void setTodoConstraints(String constraints) {
    this.todoConstraints = (constraints == null || constraints.isBlank())
      ? null
      : constraints.trim();
  }

  /**
   * Restores a previously persisted plan verbatim — statuses are kept exactly
   * (no first-item promotion) so a session resumes where it paused. Mirrors
   * the {@code todos.*} scalar fields like every other mutation.
   */
  public synchronized void restoreTodos(List<TodoItem> items) {
    // An empty restore must leave the context untouched: mirroring
    // todos.total = "0" would make a plan-presence gate (condition type
    // `empty` on todos.total) believe a plan exists and skip planning.
    if (items == null || items.isEmpty()) {
      return;
    }
    todos.clear();
    todos.addAll(items);
    mirrorTodoFields();
  }

  private void mirrorTodoFields() {
    long completed = todos
      .stream()
      .filter(t -> t.status() == TodoStatus.DONE)
      .count();
    set(KEY_TODOS_TOTAL, Long.toString(todos.size()));
    set(KEY_TODOS_COMPLETED, Long.toString(completed));
    set(KEY_TODOS_REMAINING, Long.toString(todos.size() - completed));
  }

  /** Registers server-owned tool definitions (deduplicated by name). */
  public synchronized void addServerTools(List<ToolDefinition> defs) {
    if (defs == null) return;
    for (var def : defs) {
      if (serverTools.stream().noneMatch(t -> t.getName().equals(def.getName()))) {
        serverTools.add(def);
      }
    }
  }

  /** Returns an immutable view of the server-owned tool definitions. */
  public synchronized List<ToolDefinition> serverTools() {
    return List.copyOf(serverTools);
  }

  // ---------------------------------------------------------------------------
  // Loop counters
  // ---------------------------------------------------------------------------

  /**
   * Increments and returns the iteration count for the given loop step.
   *
   * @param loopStepId the step_id of the {@code STEP_TYPE_LOOP} step
   * @return the new iteration count (1-based)
   */
  public int incrementIteration(String loopStepId) {
    return iterationCounters.merge(loopStepId, 1, Integer::sum);
  }

  // ---------------------------------------------------------------------------
  // Halt signalling
  // ---------------------------------------------------------------------------

  /**
   * Signals that the pipeline should halt and return the value of the named
   * context field as the final response.
   *
   * @param outputField the context field whose value becomes the final response
   * @param reason      the finish reason to emit
   */
  public void signalHalt(String outputField, FinishReason reason) {
    this.breakOutputField = outputField;
    this.haltReason = reason;
  }

  /** Returns {@code true} if a halt has been signalled. */
  public boolean isHalted() {
    return breakOutputField != null;
  }

  /**
   * Returns the context field whose value should be returned as the final response,
   * or {@code null} if no halt has been signalled.
   */
  public String breakOutputField() {
    return breakOutputField;
  }

  /** Returns the finish reason to emit on halt, or {@code null} if not halted. */
  public FinishReason haltReason() {
    return haltReason;
  }

  /**
   * Records the finish reason reported by the last inference step's engine.
   */
  public void setLastEngineFinishReason(FinishReason reason) {
    this.lastEngineFinishReason = reason;
  }

  /**
   * Returns the finish reason from the last inference step's engine, or {@code null}
   * if no inference step has run yet.
   */
  public FinishReason lastEngineFinishReason() {
    return lastEngineFinishReason;
  }

  /**
   * Structured tool calls extracted from the last inference step's tool span via its Jinja
   * extraction template. Surfaced on the final {@code ResponseCompleted} event so clients get
   * structured data instead of re-parsing dialect text. Empty when nothing extracted.
   */
  private volatile List<ToolCall> extractedToolCalls = List.of();

  /** Records the tool calls extracted from the last inference step (replaces earlier steps'). */
  public void setExtractedToolCalls(List<ToolCall> calls) {
    this.extractedToolCalls = calls == null ? List.of() : List.copyOf(calls);
  }

  /** The tool calls extracted from the last inference step; empty when none. */
  public List<ToolCall> extractedToolCalls() {
    return extractedToolCalls;
  }

  /**
   * Sets the human-readable guard message to be surfaced in
   * {@code InferResponse.guard_message} when the pipeline is halted by a guard step.
   *
   * @param message the resolved (template-expanded) message string
   */
  public void setHaltMessage(String message) {
    this.haltMessage = message;
  }

  /**
   * Returns the resolved guard message, or {@code null} if none was configured.
   */
  public String haltMessage() {
    return haltMessage;
  }

  // ---------------------------------------------------------------------------
  // Pending narration — an INTERNAL step's answer-channel text, surfaced when a
  // client-tool halt ends the turn so the user sees what the agent is doing.
  // ---------------------------------------------------------------------------

  private String pendingNarration;

  public void setPendingNarration(String narration) {
    this.pendingNarration = narration;
  }

  /** Returns and CLEARS the pending narration (one halt consumes it). */
  public String consumePendingNarration() {
    String n = pendingNarration;
    pendingNarration = null;
    return n;
  }

  // ---------------------------------------------------------------------------
  // Token usage / performance accumulation
  // ---------------------------------------------------------------------------

  private int totalPromptTokens;
  private int totalCompletionTokens;
  private int totalReasoningTokens;
  private int totalToolTokens;
  private long totalEvalTimeMs;
  private long totalPromptEvalTimeMs;
  private long totalSamplingTimeMs;
  private int totalTokensGenerated;
  private int totalPromptTokensEvaluated;

  /**
   * Adds the usage and performance from a step's final response to the
   * running totals.
   */
  public void accumulateUsage(TokenUsage usage, InferencePerformance perf) {
    if (usage != null) {
      totalPromptTokens += usage.getPromptTokens();
      totalCompletionTokens += usage.getCompletionTokens();
      totalReasoningTokens += usage.getReasoningTokens();
      totalToolTokens += usage.getToolTokens();
    }
    if (perf != null) {
      totalEvalTimeMs += perf.getEvalTimeMs();
      totalPromptEvalTimeMs += perf.getPromptEvalTimeMs();
      totalSamplingTimeMs += perf.getSamplingTimeMs();
      totalTokensGenerated += perf.getTokensGenerated();
      totalPromptTokensEvaluated += perf.getPromptTokensEvaluated();
    }
  }

  /** Builds the accumulated {@link TokenUsage} proto. */
  public TokenUsage buildTotalUsage() {
    return TokenUsage.newBuilder()
      .setPromptTokens(totalPromptTokens)
      .setCompletionTokens(totalCompletionTokens)
      .setReasoningTokens(totalReasoningTokens)
      .setToolTokens(totalToolTokens)
      .build();
  }

  /**
   * Builds the accumulated {@link InferencePerformance} proto. Raw counters
   * only — the wire contract carries no derived rates; consumers compute
   * tokens/second from {@code tokens_generated} and {@code eval_time_ms}.
   */
  public InferencePerformance buildTotalPerformance() {
    return InferencePerformance.newBuilder()
      .setEvalTimeMs(totalEvalTimeMs)
      .setPromptEvalTimeMs(totalPromptEvalTimeMs)
      .setSamplingTimeMs(totalSamplingTimeMs)
      .setTokensGenerated(totalTokensGenerated)
      .setPromptTokensEvaluated(totalPromptTokensEvaluated)
      .build();
  }
}
