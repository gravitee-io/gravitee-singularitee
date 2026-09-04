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
package io.gravitee.singularitee.engine.pipeline;

import static java.util.function.Function.identity;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.internal.InternalRequest;
import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.TodoSessionStore;
import io.gravitee.singularitee.engine.api.pipeline.TodoStatus;
import io.gravitee.singularitee.engine.api.pipeline.executor.OpenInference;
import io.gravitee.singularitee.engine.api.pipeline.executor.PipelineExecutorCallback;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TracingOptions;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.api.tools.ServerToolNames;
import io.gravitee.singularitee.engine.api.tools.TodoTools;
import io.gravitee.singularitee.engine.pipeline.executor.StepDispatcher;
import io.gravitee.singularitee.protocol.*;
import io.opentelemetry.api.trace.SpanKind;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a pipeline DAG step-by-step reactively, delegating execution to
 * {@link StepDispatcher}.
 *
 * <p>The DAG walk is implemented as a recursive {@code flatMapCompletable} chain: no
 * {@code CountDownLatch}, no blocking. Each step returns a {@link io.reactivex.rxjava3.core.Single}
 * emitting the next step ID; the walk recurses until the chain terminates.
 *
 * <p>Implements {@link PipelineExecutorCallback} so it can be
 * used as a local sub-pipeline callback.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class PipelineExecutor implements PipelineExecutorCallback {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineExecutor.class);

  private final PipelineRegistry pipelineRegistry;
  private final StepDispatcher dispatcher;
  private final Tracer tracer;
  private final InferenceMetrics metrics;
  private final TodoSessionStore todoSessionStore;
  private final ServerToolNames serverToolNames;
  private final ConversationStore conversationStore;

  /** Untraced, store-less constructor (client-side executor, CLI, tests). */
  public PipelineExecutor(PipelineRegistry pipelineRegistry, StepDispatcher dispatcher) {
    this(pipelineRegistry, dispatcher, null, null, null, null, ServerToolNames.DEFAULTS);
  }

  /**
   * @param tracer            the OpenTelemetry tracer, or {@code null} to disable tracing
   * @param metrics           the inference metrics recorder, or {@code null} to disable metrics
   * @param todoSessionStore  cross-request todo persistence, or {@code null} to disable
   * @param serverToolNames   the deployment's server-tool display names, or {@code null}
   *                          for the canonical ones
   * @param conversationStore stored-conversation continuation (previous_response_id),
   *                          or {@code null} to disable
   */
  public PipelineExecutor(
    PipelineRegistry pipelineRegistry,
    StepDispatcher dispatcher,
    Tracer tracer,
    InferenceMetrics metrics,
    TodoSessionStore todoSessionStore,
    ConversationStore conversationStore,
    ServerToolNames serverToolNames
  ) {
    this.pipelineRegistry = pipelineRegistry;
    this.serverToolNames = serverToolNames == null ? ServerToolNames.DEFAULTS : serverToolNames;
    this.dispatcher = dispatcher;
    this.tracer = tracer;
    this.metrics = metrics;
    this.todoSessionStore = todoSessionStore;
    this.conversationStore = conversationStore;
  }

  /**
   * Executes the pipeline identified by the request and writes tokens to the response stream.
   * Returns a {@link Completable} that completes when the pipeline finishes.
   */
  @Override
  public Completable executePipeline(
    InferPipelineRequest request,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    var entryOpt = pipelineRegistry.get(request.getPipelineId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("InferPipeline: pipeline not found: {}", request.getPipelineId());
      return Completable.fromAction(() ->
        endWith(null, response, FinishReason.FINISH_REASON_UNSPECIFIED)
      );
    }

    var entry = entryOpt.get();
    PipelineModel pipeline = entry.pipeline();
    entry.inFlightCount().incrementAndGet();

    return walk(pipeline, request, response, callerContext).doFinally(
      entry.inFlightCount()::decrementAndGet
    );
  }

  // ---------------------------------------------------------------------------
  // DAG walk: recursive reactive chain
  // ---------------------------------------------------------------------------

  private Completable walk(
    PipelineModel pipeline,
    InferPipelineRequest request,
    WriteStream<InferResponse> response,
    Context callerContext
  ) {
    var context = PipelineContext.fromRequest(request);
    context.setServerToolNames(serverToolNames);

    // Stored-conversation continuation: prepend the server-curated transcript
    // (internal tool turns included) so the request's own input is just the
    // new user turn(s). An unknown id fails loudly; silently dropping history
    // would corrupt the conversation.
    if (!request.getPreviousResponseId().isEmpty()) {
      var storedOpt = conversationStore != null
        ? conversationStore.get(request.getPreviousResponseId())
        : Optional.<ConversationStore.StoredConversation>empty();
      if (storedOpt.isEmpty()) {
        LOGGER.warn(
          "Pipeline '{}': previous_response_id '{}' not found, failing request",
          pipeline.id(),
          request.getPreviousResponseId()
        );
        return Completable.fromAction(() ->
          response.end(
            InferResponse.newBuilder()
              .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
              .setResponseFailed(
                ResponseFailed.newBuilder()
                  .setErrorCode("previous_response_not_found")
                  .setErrorMessage(
                    "No stored response '" +
                      request.getPreviousResponseId() +
                      "': it may have expired (ai.conversations.ttl) or storage is disabled"
                  )
              )
              .build()
          )
        );
      }
      var stored = storedOpt.get();
      var combined = new ArrayList<>(ConversationStore.toChatTurns(stored));
      if (context.messages() != null) {
        combined.addAll(context.messages());
      }
      context.setMessages(combined);
      context.restoreTodos(ConversationStore.toTodoItems(stored));
      context.setTodoConstraints(stored.constraints());
      LOGGER.info(
        "Pipeline '{}': continued conversation '{}' ({} stored turn(s), {} todo(s))",
        pipeline.id(),
        request.getPreviousResponseId(),
        stored.turns().size(),
        stored.todos().size()
      );
    }

    var stepMap = pipeline.steps().stream().collect(Collectors.toMap(StepModel::id, identity()));

    // A todo step in the graph implies the server-owned todo tools: register
    // them once so every infer step injects their schemas automatically, and
    // restore a session's plan (paused via ask_user) when the request carries
    // a session key and the store holds one.
    boolean todoPipeline = pipeline
      .steps()
      .stream()
      .anyMatch(s -> StepTypes.TODO.equals(s.type()));
    if (todoPipeline) {
      context.addServerTools(TodoTools.definitions(serverToolNames));
      // Stuck-call signal: the length of the TRAILING run of consecutive
      // assistant tool calls with identical (name, arguments) is a fact of
      // the transcript, seeded so a graph gate can break behavioral loops
      // (the same failing call retried blindly) without model judgment.
      // ask_user is exempt: repeated questions are governed elsewhere.
      context.set(PipelineContext.KEY_REPEATED_CALL, Long.toString(trailingRepeatedCalls(context)));
      // Key-based session recovery is the FALLBACK: a stored-conversation
      // continuation already restored the authoritative plan above, and a
      // stale key-based session (the `user` field maps to cache_key) must
      // never clobber it.
      if (todoSessionStore != null && request.getPreviousResponseId().isEmpty()) {
        todoSessionStore
          .restore(context.cacheKey())
          .ifPresent(restored -> {
            context.restoreTodos(restored.todos());
            context.setTodoConstraints(restored.constraints());
            LOGGER.info(
              "Pipeline '{}': restored {} todo(s) for session '{}'",
              pipeline.id(),
              restored.todos().size(),
              context.cacheKey()
            );
          });
      }
      // Plan-lock policy: a restored plan is locked against set_todos unless
      // it is FINISHED and this request opens with a fresh user message (a
      // tool-result continuation is the same run still executing). Only human
      // input authorizes authoring the next plan.
      var restoredTodos = context.todos();
      if (!restoredTodos.isEmpty()) {
        boolean allDone = restoredTodos.stream().allMatch(t -> t.status() == TodoStatus.DONE);
        var turns = context.messages();
        var last = (turns == null || turns.isEmpty()) ? null : turns.getLast();
        boolean freshUserMessage =
          last != null && last.role() == ChatRole.USER && last.toolCallId() == null;
        context.setPlanLocked(!(allDone && freshUserMessage));
      }
    }

    // Open the singularitee.pipeline span (child of the gRPC server span on the caller context).
    // Step/model spans nest under it via explicit parenting. No-op when tracing is off.
    final Span pipelineSpan = startPipelineSpan(callerContext, pipeline);
    var stepCtx = new StepContext(
      context,
      pipeline,
      response,
      callerContext,
      tracer,
      metrics,
      pipelineSpan,
      new AtomicReference<>()
    );

    // Neutral per-turn facts on the pipeline span: a stable-enough turn identity (the
    // response id), how many assistant turns precede this one, the continuation pointer,
    // and the human turn as an event. No-op when tracing is off. Domain-specific fields
    // (decisions, verdicts) are added by the steps themselves through the same scribe.
    long turnIndex = context.messages() == null
      ? 0
      : context
        .messages()
        .stream()
        .filter(t -> t.role() == ChatRole.ASSISTANT)
        .count();
    // A conversation's turns share the client cache-affinity key (prompt_cache_key / user, or a
    // previous_response_id-driven key); trace tools group by this session id. A one-shot request with
    // no cache key falls back to its own request id.
    String sessionId = sessionId(context.cacheKey(), request.getRequestId());
    stepCtx
      .turnScribe()
      .set(SpanNames.key("session"), sessionId)
      .set(SpanNames.key("turn.index"), turnIndex)
      .set(
        SpanNames.key("previous_response_id"),
        request.getPreviousResponseId().isEmpty() ? null : request.getPreviousResponseId()
      )
      .event(
        SpanNames.key("turn"),
        Map.of("role", "human", "content", spanPreview(lastUserContent(context)))
      );

    // OpenInference: the pipeline is a CHAIN; session id lets trace tools group a conversation.
    // The user input is content, so it rides behind the verbose flag.
    if (TracingOptions.openInference()) {
      var oi = stepCtx
        .turnScribe()
        .set(OpenInference.SPAN_KIND, OpenInference.KIND_CHAIN)
        .set(OpenInference.SESSION_ID, sessionId);
      if (TracingOptions.verbose()) {
        oi
          .set(OpenInference.INPUT_VALUE, lastUserContent(context))
          .set(OpenInference.INPUT_MIME, OpenInference.MIME_TEXT);
      }
    }

    // Emit CREATED event at the start of the pipeline.
    var created = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_CREATED)
      .setResponseCreated(
        ResponseCreated.newBuilder()
          .setResponseId(request.getRequestId() != null ? request.getRequestId() : "")
          .setModel(request.getPipelineId())
          .build()
      )
      .build();
    response.write(created);

    final Throwable[] error = { null };
    return walkStep(pipeline.entryStepId(), stepMap, stepCtx, context)
      .andThen(
        Completable.defer(() -> {
          if (context.isHalted()) {
            FinishReason haltReason = context.haltReason() != null
              ? context.haltReason()
              : FinishReason.FINISH_REASON_STOP;
            stepCtx.turnScribe().set(SpanNames.key("finish_reason"), haltReason.name());
            if (TracingOptions.openInference()) {
              stepCtx.turnScribe().set(OpenInference.METADATA, oiMeta(haltReason, turnIndex));
              if (TracingOptions.verbose()) {
                // OpenInference output.value on the CHAIN span (literal key: the runtime engine-api
                // may be older than this build). Fills the trace-level output for gated turns.
                stepCtx.turnScribe().set("output.value", finalOutput(context));
              }
            }
            if (
              metrics != null && context.haltReason() == FinishReason.FINISH_REASON_GUARD_BLOCKED
            ) {
              metrics.recordFailureSignal(pipeline.id(), "pipeline", "guard_blocked");
            }
            return Completable.fromAction(() -> emitHaltResponse(context, response));
          }
          FinishReason reason = context.lastEngineFinishReason() != null
            ? context.lastEngineFinishReason()
            : FinishReason.FINISH_REASON_STOP;
          stepCtx.turnScribe().set(SpanNames.key("finish_reason"), reason.name());
          if (TracingOptions.openInference()) {
            stepCtx.turnScribe().set(OpenInference.METADATA, oiMeta(reason, turnIndex));
            if (TracingOptions.verbose()) {
              stepCtx.turnScribe().set("output.value", finalOutput(context));
            }
          }
          return Completable.fromAction(() -> endWith(context, response, reason));
        })
      )
      .doOnError(e -> error[0] = e)
      .doFinally(() -> {
        persistTodoSession(pipeline, context, todoPipeline);
        persistConversation(request, context);
        endPipelineSpan(callerContext, pipelineSpan, error[0]);
      });
  }

  /** The content of the last USER turn, or empty when there is none. */
  /**
   * The session id for a turn's spans: the client cache-affinity key when supplied, so every turn of
   * one conversation shares a session, else the per-turn request id.
   */
  static String sessionId(String cacheKey, String requestId) {
    return cacheKey != null && !cacheKey.isBlank() ? cacheKey : requestId;
  }

  private static String lastUserContent(PipelineContext context) {
    var messages = context.messages();
    if (messages == null) return "";
    for (int i = messages.size() - 1; i >= 0; i--) {
      var turn = messages.get(i);
      if (turn.role() == ChatRole.USER) {
        return turn.content() == null ? "" : turn.content();
      }
    }
    return "";
  }

  /**
   * The turn's final visible output for the pipeline (CHAIN) span's {@code output.value}: the halt
   * message when a step halted the turn (an elicitation or a refusal), otherwise the
   * last assistant turn (the reply step's answer or a voiced question).
   */
  private static String finalOutput(PipelineContext context) {
    String halt = context.haltMessage();
    if (halt != null && !halt.isBlank()) {
      return halt;
    }
    var messages = context.messages();
    if (messages != null) {
      for (int i = messages.size() - 1; i >= 0; i--) {
        var turn = messages.get(i);
        if (
          turn.role() == ChatRole.ASSISTANT && turn.content() != null && !turn.content().isBlank()
        ) {
          return turn.content();
        }
      }
    }
    return "";
  }

  /** A minimal OpenInference `metadata` JSON for the turn. */
  private static String oiMeta(FinishReason reason, long turnIndex) {
    return "{\"finish_reason\":\"" + reason.name() + "\",\"turn_index\":" + turnIndex + "}";
  }

  /** A short, one-line, span-safe preview of possibly large or sensitive text (never null). */
  private static String spanPreview(String text) {
    if (text == null || text.isEmpty()) return "";
    String oneLine = text.strip().replace('\n', ' ');
    return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
  }

  /**
   * Counts how many consecutive assistant tool-call turns at the TAIL of the
   * conversation carry an identical single (name, arguments) call. Different
   * arguments, a different tool, or any intervening assistant prose resets
   * the run; TOOL result turns between the calls are skipped.
   */
  private static long trailingRepeatedCalls(PipelineContext context) {
    var messages = context.messages();
    if (messages == null || messages.isEmpty()) return 0;
    String signature = null;
    long run = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {
      var turn = messages.get(i);
      if (turn.role() == ChatRole.TOOL) {
        continue; // results between the calls do not break the run
      }
      if (turn.role() != ChatRole.ASSISTANT) {
        break;
      }
      var calls = turn.toolCalls();
      if (calls.size() != 1) break;
      var call = calls.getFirst();
      if (TodoTools.ASK_USER.equals(context.serverToolNames().canonical(call.name()))) break;
      String sig = call.name() + "\u0000" + call.argumentsJson();
      if (signature == null) {
        signature = sig;
        run = 1;
      } else if (signature.equals(sig)) {
        run++;
      } else {
        break;
      }
    }
    return run <= 1 ? 0 : run;
  }

  /**
   * End-of-request conversation storage (OpenAI `store`, default true when the
   * request carries an id): the transcript the pipeline built (internal tool
   * turns included) plus the todo plan, under the response id, so the next
   * turn can continue via previous_response_id with server-curated history.
   */
  private void persistConversation(InferPipelineRequest request, PipelineContext context) {
    if (conversationStore == null || !conversationStore.isEnabled()) {
      return;
    }
    String responseId = request.getRequestId();
    if (responseId == null || responseId.isBlank()) {
      return;
    }
    boolean store = !request.hasStore() || request.getStore();
    if (!store) {
      return;
    }
    var turns = context.messages();
    if (turns == null || turns.isEmpty()) {
      return;
    }
    // A turn that halted on client-bound tool calls must store the CALL
    // itself: the client answers with function_call_output only, and a
    // stored transcript missing the assistant call leaves the result
    // pairing with the wrong call on replay (the model then repeats the
    // call forever, blind to its own history).
    var pendingCalls = context.extractedToolCalls();
    if (pendingCalls != null && !pendingCalls.isEmpty()) {
      turns = new ArrayList<>(turns);
      turns.add(
        new ChatTurn(
          ChatRole.ASSISTANT,
          "",
          List.of(),
          pendingCalls
            .stream()
            .map(c -> new ChatTurn.ToolCallTurn(c.getId(), c.getName(), c.getArgumentsJson()))
            .toList(),
          null,
          null
        )
      );
    }
    conversationStore.put(responseId, turns, context.todos(), context.todoConstraints());
    LOGGER.debug(
      "Stored conversation '{}' ({} turn(s), {} todo(s))",
      responseId,
      turns.size(),
      context.todos().size()
    );
  }

  /**
   * End-of-request session bookkeeping: an unfinished plan is saved so the
   * next turn with the same session key resumes it; a completed plan clears
   * its session so it cannot leak into an unrelated conversation.
   */
  private void persistTodoSession(
    PipelineModel pipeline,
    PipelineContext context,
    boolean todoPipeline
  ) {
    if (todoSessionStore == null || !todoPipeline) {
      return;
    }
    String key = context.cacheKey();
    if (key == null || key.isBlank()) {
      return;
    }
    var todos = context.todos();
    boolean allDone =
      !todos.isEmpty() && todos.stream().allMatch(t -> t.status() == TodoStatus.DONE);
    if (todos.isEmpty() || allDone) {
      todoSessionStore.clear(key);
      if (allDone) {
        LOGGER.debug("Pipeline '{}': plan completed, session '{}' cleared", pipeline.id(), key);
      }
    } else {
      todoSessionStore.save(key, todos, context.todoConstraints());
    }
  }

  /** Opens the {@code singularitee.pipeline} span on the caller context, or returns {@code null}. */
  private Span startPipelineSpan(Context callerContext, PipelineModel pipeline) {
    if (tracer == null || callerContext == null) {
      return null;
    }
    InternalRequest request = InternalRequest.builder()
      .name(SpanNames.key("pipeline"))
      .attributes(Map.of("pipeline.id", pipeline.id()))
      .spanKind(SpanKind.INTERNAL)
      .build();
    return tracer.startSpanFrom(callerContext, request);
  }

  private void endPipelineSpan(Context callerContext, Span span, Throwable error) {
    if (span == null || tracer == null) {
      return;
    }
    if (error != null) {
      tracer.endOnError(callerContext, span, error);
    } else {
      tracer.end(callerContext, span);
    }
  }

  private Completable walkStep(
    String stepId,
    Map<String, StepModel> stepMap,
    StepContext stepCtx,
    PipelineContext context
  ) {
    if (stepId == null || stepId.isBlank() || context.isHalted()) {
      return Completable.complete();
    }

    StepModel step = stepMap.get(stepId);
    if (step == null) {
      LOGGER.warn("Pipeline '{}': step '{}' not found, halting", stepCtx.pipeline().id(), stepId);
      return Completable.complete();
    }

    LOGGER.info(
      "Pipeline '{}': executing step '{}' (type={}{})",
      stepCtx.pipeline().id(),
      step.id(),
      step.type(),
      StepTypes.INFER.equals(step.type()) ? ", role=" + step.role() : ""
    );

    return dispatcher
      .dispatch(step, stepCtx)
      .flatMapCompletable(nextStepId -> {
        if (context.isHalted()) return Completable.complete();
        return walkStep(nextStepId, stepMap, stepCtx, context);
      });
  }

  // ---------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------

  private static void emitHaltResponse(
    PipelineContext context,
    WriteStream<InferResponse> response
  ) {
    FinishReason reason = context.haltReason() != null
      ? context.haltReason()
      : FinishReason.FINISH_REASON_STOP;

    if (reason == FinishReason.FINISH_REASON_GUARD_BLOCKED) {
      LOGGER.info(
        "Pipeline halted due to guard block: reason={}, output_field={}",
        reason,
        context.breakOutputField()
      );
      // Emit FAILED event for guard blocks.
      var failed = InferResponse.newBuilder()
        .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
        .setResponseFailed(
          ResponseFailed.newBuilder()
            .setErrorCode("content_filter")
            .setErrorMessage(
              context.haltMessage() != null ? context.haltMessage() : "Guard blocked"
            )
            .build()
        )
        .build();
      response.end(failed);
    } else {
      LOGGER.debug(
        "Pipeline halted: reason={}, output_field={}",
        reason,
        context.breakOutputField()
      );
      endWith(context, response, reason);
    }
  }

  private static void endWith(
    PipelineContext context,
    WriteStream<InferResponse> response,
    FinishReason reason
  ) {
    var completedBuilder = ResponseCompleted.newBuilder().setFinishReason(reason);
    if (context != null) {
      completedBuilder
        .setUsage(context.buildTotalUsage())
        .setPerformance(context.buildTotalPerformance())
        .addAllToolCalls(context.extractedToolCalls());
    }
    var completed = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)
      .setResponseCompleted(completedBuilder.build())
      .build();
    response.end(completed);
  }
}
