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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.tools.TodoTools;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.pipeline.TodoSessionStore;
import io.gravitee.singularitee.pipeline.TodoStatus;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.ResponseOutputTextDelta;
import io.gravitee.singularitee.protocol.ResponseProgress;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.TodoStepConfig;
import io.gravitee.singularitee.protocol.ToolCall;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a TODO step: the first server-executed tool in the pipeline.
 *
 * <p>Placed after an infer step (like guards). When the preceding generation
 * contained {@code set_todos}/{@code complete_todo} calls, this step executes
 * them against the engine-managed plan on {@link PipelineContext}, appends the
 * assistant call and a {@code TOOL} result turn to the transcript (a call with
 * no answer poisons template replay), removes the consumed calls from
 * {@link PipelineContext#extractedToolCalls()} so they never leak to the
 * client, emits a {@code PROGRESS} event with the updated plan, and branches
 * to {@code handled_step_id} so the model continues. Client-bound tool calls
 * pass through untouched on the {@code next_step} edge.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TodoStepExecutor implements StepExecutor<TodoStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TodoStepExecutor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicLong CALL_ID_SEQ = new AtomicLong();

  private final TodoSessionStore sessionStore;

  /** Store-less constructor (client-side executor, tests): no session persistence. */
  public TodoStepExecutor() {
    this(null);
  }

  public TodoStepExecutor(TodoSessionStore sessionStore) {
    this.sessionStore = sessionStore;
  }

  @Override
  public TodoStepConfig extractConfig(PipelineStep step) {
    return step.getTodoConfig();
  }

  @Override
  public Maybe<String> execute(String stepId, TodoStepConfig cfg, StepContext ctx) {
    var pctx = ctx.pipelineContext();
    var calls = pctx.extractedToolCalls();

    // A DELEGABLE server tool the caller declared itself (its own ask_user
    // schema, e.g. with multiple-choice options) is CLIENT-bound: the call
    // rides out as a normal function_call and the answer returns as a
    // function_call_output. Plan tools are never delegable.
    Set<String> declared = new HashSet<>();
    for (var t : pctx.tools()) {
      declared.add(t.getName());
    }
    List<ToolCall> todoCalls = new ArrayList<>();
    List<ToolCall> clientCalls = new ArrayList<>();
    for (var call : calls) {
      boolean delegated =
        TodoTools.DELEGABLE.contains(call.getName()) && declared.contains(call.getName());
      boolean serverOwned = TodoTools.NAMES.contains(call.getName()) && !delegated;
      (serverOwned ? todoCalls : clientCalls).add(call);
    }

    if (todoCalls.isEmpty()) {
      if (!clientCalls.isEmpty()) {
        // Client-bound tool calls END the pipeline turn: the client must
        // execute them and reply — looping onward would swallow the call
        // (and a later tag-less step would leak its raw span as text).
        return haltForClientCalls(stepId, pctx, ctx);
      }
      LOGGER.debug("TodoStep '{}': no todo tool call — passing through", stepId);
      return ctx.rxNextStep(stepId);
    }

    String askUserQuestion = null;
    for (var call : todoCalls) {
      String result = executeCall(stepId, pctx, call);
      if (TodoTools.ASK_USER.equals(call.getName())) {
        askUserQuestion = extractQuestion(call);
        // The question must be VISIBLE assistant content in the transcript,
        // not just tool-call arguments: on the next turn the model has to see
        // "assistant: <question>" right before the user's answer, or it
        // re-asks instead of connecting the reply to it.
        recordTurns(pctx, call, result, askUserQuestion);
      } else {
        recordTurns(pctx, call, result, null);
      }
    }

    // The consumed calls must never reach the client: keep only client-bound
    // calls, and when none remain, the generation was purely internal — the
    // finish reason reverts to a plain stop.
    pctx.setExtractedToolCalls(List.copyOf(clientCalls));
    if (clientCalls.isEmpty()) {
      pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_STOP);
    }

    emitProgress(stepId, ctx, pctx);

    var todos = pctx.todos();
    LOGGER.info(
      "TodoStep '{}': executed {} call(s) — plan now {}/{} done{}",
      stepId,
      todoCalls.size(),
      pctx.get(PipelineContext.KEY_TODOS_COMPLETED),
      todos.size(),
      clientCalls.isEmpty() ? "" : ", " + clientCalls.size() + " client call(s) passed through"
    );

    if (sessionStore != null) {
      // Persist the plan under the request's session key so it survives an
      // ask_user pause (PipelineExecutor also saves at end-of-request; this
      // eager save guards against stream errors after the halt).
      sessionStore.save(pctx.cacheKey(), todos, pctx.todoConstraints());
    }

    if (askUserQuestion != null) {
      // ask_user wins over everything: stream the question as the visible
      // assistant answer, then halt the pipeline. BREAK_CONDITION maps to a
      // plain "stop" on the OpenAI surface — a normal end-of-turn.
      streamText(ctx, askUserQuestion);
      pctx.set(stepId + ".question", askUserQuestion);
      pctx.signalHalt(stepId + ".question", FinishReason.FINISH_REASON_BREAK_CONDITION);
      LOGGER.info("TodoStep '{}': paused for user input — plan saved for resume", stepId);
      return ctx.rxNextStep(stepId); // halt flag short-circuits the walk regardless
    }

    if (!clientCalls.isEmpty()) {
      // Mixed generation: the todo calls were executed above; the surviving
      // client calls end the turn so the client can execute them.
      return haltForClientCalls(stepId, pctx, ctx);
    }
    String handled = cfg.getHandledStepId();
    return (handled == null || handled.isBlank()) ? ctx.rxNextStep(stepId) : Maybe.just(handled);
  }

  /**
   * Halts the pipeline with {@code finish_reason: tool_calls}: the remaining
   * calls in {@link PipelineContext#extractedToolCalls()} ride the completed
   * event to the client, whose tool results arrive as the next request.
   */
  private Maybe<String> haltForClientCalls(String stepId, PipelineContext pctx, StepContext ctx) {
    // Surface the generating step's visible words (captured even for internal
    // steps) so the client shows narration next to the tool calls it receives.
    String narration = pctx.consumePendingNarration();
    if (narration != null && !narration.isBlank()) {
      streamText(ctx, narration);
    }
    pctx.set(stepId + ".client_tool_calls", Integer.toString(pctx.extractedToolCalls().size()));
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    pctx.signalHalt(stepId + ".client_tool_calls", FinishReason.FINISH_REASON_TOOL_CALLS);
    LOGGER.info(
      "TodoStep '{}': {} client tool call(s) — ending the turn for the client to execute",
      stepId,
      pctx.extractedToolCalls().size()
    );
    return Maybe.empty();
  }

  /** The question argument of an ask_user call, or a generic fallback. */
  private static String extractQuestion(ToolCall call) {
    try {
      JsonNode args = MAPPER.readTree(call.getArgumentsJson());
      if (args.hasNonNull("question") && !args.get("question").asText().isBlank()) {
        // Models are inconsistent about escaping: a question authored with a
        // LITERAL backslash-n renders as one ugly line in every client.
        // Normalizing it to a real newline is always what the author meant.
        return args.get("question").asText().replace("\\n", "\n");
      }
    } catch (JsonProcessingException e) {
      LOGGER.debug("ask_user: arguments did not parse as JSON: {}", e.getMessage());
    }
    return "I need more information from you to continue. Could you clarify?";
  }

  /** Streams text to the client as an OUTPUT-tagged delta (the question of an ask_user). */
  private static void streamText(StepContext ctx, String text) {
    if (ctx.response() == null || text == null || text.isEmpty()) {
      return;
    }
    ctx
      .response()
      .write(
        InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
          .setResponseOutputTextDelta(ResponseOutputTextDelta.newBuilder().setDelta(text))
          .setStepRole(StepRole.STEP_ROLE_OUTPUT)
          .build()
      );
  }

  /** Executes one todo call and returns the JSON result text for the TOOL turn. */
  private static String executeCall(String stepId, PipelineContext pctx, ToolCall call) {
    try {
      JsonNode args = MAPPER.readTree(
        call.getArgumentsJson() == null || call.getArgumentsJson().isBlank()
          ? "{}"
          : call.getArgumentsJson()
      );
      return switch (call.getName()) {
        case TodoTools.SET_TODOS -> setTodos(pctx, args);
        case TodoTools.COMPLETE_TODO -> completeTodo(pctx, args);
        case TodoTools.ASK_USER -> result(true).put("status", "waiting_for_user").toString();
        default -> error("unknown todo tool");
      };
    } catch (Exception e) {
      // Fail-open: the model gets the error as the tool result and can retry.
      LOGGER.warn(
        "TodoStep '{}': failed to execute {}: {}",
        stepId,
        call.getName(),
        e.getMessage()
      );
      pctx.set(stepId + ".todo_error", String.valueOf(e.getMessage()));
      return error(String.valueOf(e.getMessage()));
    }
  }

  private static String setTodos(PipelineContext pctx, JsonNode args) {
    // Installing a plan locks it (see PipelineContext.setTodos); the lock
    // lifts only at request restore, when the plan is finished AND a fresh
    // user message arrived. So a mid-run set_todos - a model that lost the
    // plan install to context trimming, or one chaining a finished plan
    // into a new one - is always refused, while a NEW request after
    // completion can plan the next task.
    if (pctx.isPlanLocked()) {
      boolean inFlight = pctx
        .todos()
        .stream()
        .anyMatch(t -> t.status() != TodoStatus.DONE);
      return inFlight
        ? error(
          "the current plan is still in progress and cannot be replaced - complete the " +
            "in_progress item and call complete_todo with its id"
        )
        : error("the plan is finished and cannot be replaced here - answer the user directly");
    }
    // Re-planning must be non-destructive: models routinely re-send the whole
    // list to "update" it (sometimes with a status field). Honor an explicit
    // valid status; otherwise inherit the current status of the same id, so a
    // verbatim re-send never resets done items back to pending.
    var existing = new HashMap<String, TodoStatus>();
    for (var t : pctx.todos()) {
      existing.put(t.id(), t.status());
    }
    JsonNode items = args.get("todos");
    // Some dialects (Qwen chatml) encode nested arrays as a JSON STRING:
    // {"todos":"[{\"id\":\"1\",...}]"}. Unwrap before iterating.
    if (items != null && items.isTextual()) {
      try {
        items = MAPPER.readTree(items.asText());
      } catch (JsonProcessingException e) {
        LOGGER.debug("set_todos: string-encoded todos did not parse: {}", e.getMessage());
      }
    }
    List<PipelineContext.TodoItem> parsed = new ArrayList<>();
    if (items != null && items.isArray()) {
      int index = 1;
      for (JsonNode item : items) {
        if (item.isObject()) {
          String id = item.hasNonNull("id") ? item.get("id").asText() : Integer.toString(index);
          String title = item.hasNonNull("title") ? item.get("title").asText() : "";
          String statusText = item.hasNonNull("status") ? item.get("status").asText() : null;
          TodoStatus status = TodoStatus.isValidWireName(statusText)
            ? TodoStatus.fromWire(statusText)
            : existing.getOrDefault(id, TodoStatus.PENDING);
          String proof = item.hasNonNull("proof") ? item.get("proof").asText() : null;
          if (!title.isBlank()) {
            parsed.add(new PipelineContext.TodoItem(id, title, status, proof));
          }
        } else if (item.isTextual() && !item.asText().isBlank()) {
          // Tolerate plain-string items: the index becomes the id.
          String id = Integer.toString(index);
          parsed.add(
            new PipelineContext.TodoItem(
              id,
              item.asText(),
              existing.getOrDefault(id, TodoStatus.PENDING),
              null
            )
          );
        }
        index++;
      }
    }
    if (parsed.isEmpty()) {
      // A plan either has items or does not exist. Installing an empty list
      // would put the pipeline into the work loop with "0/0 done" and nothing
      // to do — models do call set_todos with an empty array. Refuse it: the
      // model gets the error and can re-plan or answer directly, and
      // plan_check keeps routing planless requests correctly.
      return error("todos must contain at least one item with a title");
    }
    pctx.setTodos(parsed);
    // Plan-level constraints (locked user decisions) ride along optionally.
    // Absence on a re-send keeps the existing ones — a plan update must not
    // silently drop what the user already decided.
    JsonNode constraints = args.get("constraints");
    if (constraints != null && constraints.isTextual() && !constraints.asText().isBlank()) {
      pctx.setTodoConstraints(constraints.asText());
    }
    return result(true).put("total", parsed.size()).toString();
  }

  private static String completeTodo(PipelineContext pctx, JsonNode args) {
    String id = args.hasNonNull("id") ? args.get("id").asText() : "";
    // Completing an already-done item is a silent no-op state-wise; without an
    // explicit error the model repeats it forever. Tell it what to do instead.
    boolean alreadyDone = pctx
      .todos()
      .stream()
      .anyMatch(t -> t.id().equals(id) && t.status() == TodoStatus.DONE);
    if (alreadyDone) {
      return result(false)
        .put("error", "item " + id + " is already done")
        .put("in_progress", nextInProgressTitle(pctx))
        .toString();
    }
    boolean found = pctx.completeTodo(id);
    if (!found) {
      return error("no todo with id " + id);
    }
    return result(true)
      .put("remaining", Long.parseLong(pctx.get(PipelineContext.KEY_TODOS_REMAINING)))
      .put("next", nextInProgressTitle(pctx))
      .toString();
  }

  private static String nextInProgressTitle(PipelineContext pctx) {
    return pctx
      .todos()
      .stream()
      .filter(t -> t.status() == TodoStatus.IN_PROGRESS)
      .map(PipelineContext.TodoItem::title)
      .findFirst()
      .orElse("none — all items done");
  }

  /** A fresh {@code {"ok": <ok>}} result node to extend fluently. */
  private static ObjectNode result(boolean ok) {
    return MAPPER.createObjectNode().put("ok", ok);
  }

  /** A serialized {@code {"ok":false,"error":<message>}} result. */
  private static String error(String message) {
    return result(false).put("error", message).toString();
  }

  /**
   * Appends the assistant tool-call turn and its TOOL result to the transcript
   * so subsequent template renders replay a complete call/answer pair.
   */
  private static void recordTurns(
    PipelineContext pctx,
    ToolCall call,
    String result,
    String visibleContent
  ) {
    String callId = call.getId() != null && !call.getId().isBlank()
      ? call.getId()
      : "todo_call_" + CALL_ID_SEQ.incrementAndGet();
    pctx.appendMessage(
      new ChatTurn(
        ChatRole.ASSISTANT,
        visibleContent,
        List.of(),
        List.of(new ChatTurn.ToolCallTurn(callId, call.getName(), call.getArgumentsJson())),
        null,
        null
      )
    );
    pctx.appendMessage(
      new ChatTurn(ChatRole.TOOL, result, List.of(), List.of(), callId, call.getName())
    );
  }

  /** Streams a PROGRESS event with the updated plan snapshot. */
  private static void emitProgress(String stepId, StepContext ctx, PipelineContext pctx) {
    if (ctx.response() == null) {
      return;
    }
    var progress = ResponseProgress.newBuilder().setStepId(stepId);
    int completed = 0;
    for (var t : pctx.todos()) {
      if (t.status() == TodoStatus.DONE) {
        completed++;
      }
      // The proto TodoItem clashes with the domain record's name, so it stays qualified.
      progress.addTodos(
        io.gravitee.singularitee.protocol.TodoItem.newBuilder()
          .setId(t.id())
          .setTitle(t.title())
          .setStatus(t.status().wireName())
          .setProof(t.proof() == null ? "" : t.proof())
      );
    }
    progress.setCompleted(completed).setTotal(pctx.todos().size());
    ctx
      .response()
      .write(
        InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_PROGRESS)
          .setResponseProgress(progress)
          .build()
      );
  }
}
