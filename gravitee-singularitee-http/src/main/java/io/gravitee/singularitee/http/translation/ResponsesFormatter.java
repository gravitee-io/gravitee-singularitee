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
package io.gravitee.singularitee.http.translation;

import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.closeReasoning;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.contentPartEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.emitBufferedTextItem;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.failedResponseObject;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.functionCallItem;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.outputItemEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.outputTextDeltaEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.outputTextDoneEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.progressEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.reasoningItem;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.reasoningSummaryDelta;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.reasoningSummaryPartEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.responsesEvent;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.responsesMessageItem;
import static io.gravitee.singularitee.http.translation.ResponsesEventFactory.responsesObject;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.contentWithToolFallback;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.isToolCandidate;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.narrationText;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.resolveToolCalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.translation.wire.ResponsesUsage;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Formats a token stream into OpenAI Responses API shapes: live SSE streaming, buffered-then-SSE
 * (tool-aware) streaming and the non-streaming {@code response} object.
 */
public final class ResponsesFormatter {

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  private ResponsesFormatter() {}

  /**
   * Produces SSE events in the OpenAI Responses API streaming format, including the full item /
   * content lifecycle. Reasoning (THINKING) tokens, when present, are surfaced as a {@code reasoning}
   * output item (output_index 0) via {@code response.reasoning_summary_text.*} events; the answer then
   * follows as a {@code message} item:
   *
   * <pre>
   * response.created → response.in_progress
   *   [→ output_item.added(reasoning) → reasoning_summary_part.added
   *      → reasoning_summary_text.delta* → reasoning_summary_text.done
   *      → reasoning_summary_part.done → output_item.done(reasoning)]
   *   → output_item.added(message) → content_part.added → output_text.delta*
   *   → output_text.done → content_part.done → output_item.done(message)
   * → response.completed   (embeds the assembled output)
   * </pre>
   *
   * <p>The Responses protocol terminates on {@code response.completed} — there is <strong>no</strong>
   * {@code [DONE]} sentinel (that is a Chat Completions convention). {@code created_at} is a Unix
   * timestamp in seconds.
   *
   * @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch
   */
  public static Flowable<ServerEvent> responsesStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal,
    String responseIdOverride
  ) {
    // Deferred: per-subscription state must be created per subscription, not per
    // factory call, or a re-subscribe replays corrupted state.
    return Flowable.defer(() ->
      responsesStreamEventsOnce(tokenStream, modelName, onFinal, responseIdOverride)
    );
  }

  private static Flowable<ServerEvent> responsesStreamEventsOnce(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal,
    String responseIdOverride
  ) {
    long created = Instant.now().getEpochSecond();
    String responseId = responseIdOverride != null ? responseIdOverride : "resp-" + created;
    String reasoningId = "rs-" + created;
    String msgId = "msg-" + created;
    AtomicBoolean headerEmitted = new AtomicBoolean(false);
    AtomicBoolean reasoningOpen = new AtomicBoolean(false);
    AtomicBoolean reasoningClosed = new AtomicBoolean(false);
    AtomicBoolean contentOpen = new AtomicBoolean(false);
    AtomicInteger msgIndex = new AtomicInteger(0);
    AtomicLong seq = new AtomicLong(0);
    StringBuilder reasoning = new StringBuilder();
    StringBuilder content = new StringBuilder();

    return tokenStream.flatMap(token -> {
      List<ServerEvent> events = new ArrayList<>();

      if (!headerEmitted.getAndSet(true)) {
        events.add(
          responsesEvent(
            "response.created",
            seq,
            responsesObject(responseId, "in_progress", created, modelName)
          )
        );
        events.add(
          responsesEvent(
            "response.in_progress",
            seq,
            responsesObject(responseId, "in_progress", created, modelName)
          )
        );
      }

      // Auxiliary progress update (engine-managed todo plan): the Responses
      // surface already emits typed non-canonical objects, so a gravitee-
      // namespaced type is the least invasive representation.
      if (token.progress() != null) {
        events.add(progressEvent(seq, token.progress()));
        return Flowable.fromIterable(events);
      }

      if (token.isFinal()) {
        if (onFinal != null) {
          onFinal.accept(token);
        }
        // Any terminal failure (previous_response_not_found, guard block) must
        // surface as response.failed — even after partial output, which a
        // "completed" would misreport as a successful answer. Open items are
        // closed first (partial message as "incomplete") so SDK state machines
        // unwind cleanly before the terminal event.
        if (token.guardMessage() != null) {
          if (reasoningOpen.get() && reasoningClosed.compareAndSet(false, true)) {
            closeReasoning(events, seq, reasoningId, reasoning.toString());
          }
          if (contentOpen.get()) {
            String full = content.toString();
            events.add(outputTextDoneEvent(seq, msgId, msgIndex.get(), full));
            events.add(
              contentPartEvent("response.content_part.done", seq, msgId, msgIndex.get(), full)
            );
            events.add(
              outputItemEvent(
                "response.output_item.done",
                seq,
                msgIndex.get(),
                responsesMessageItem(msgId, "incomplete", full)
              )
            );
          }
          events.add(
            responsesEvent(
              "response.failed",
              seq,
              failedResponseObject(
                responseId,
                created,
                modelName,
                token.guardMessage(),
                reasoning.toString(),
                content.toString()
              )
            )
          );
          return Flowable.fromIterable(events);
        }
        if (reasoningOpen.get() && reasoningClosed.compareAndSet(false, true)) {
          closeReasoning(events, seq, reasoningId, reasoning.toString());
        }
        if (contentOpen.get()) {
          String full = content.toString();
          events.add(outputTextDoneEvent(seq, msgId, msgIndex.get(), full));
          events.add(
            contentPartEvent("response.content_part.done", seq, msgId, msgIndex.get(), full)
          );
          events.add(
            outputItemEvent(
              "response.output_item.done",
              seq,
              msgIndex.get(),
              responsesMessageItem(msgId, "completed", full)
            )
          );
        }

        ObjectNode completed = responsesObject(responseId, "completed", created, modelName);
        ArrayNode output = completed.putArray("output");
        if (reasoningOpen.get()) {
          output.add(reasoningItem(reasoningId, reasoning.toString()));
        }
        if (contentOpen.get()) {
          output.add(responsesMessageItem(msgId, "completed", content.toString()));
        }
        writeResponsesUsage(completed, token.promptTokens(), token.completionTokens());
        events.add(responsesEvent("response.completed", seq, completed));
        return Flowable.fromIterable(events);
      }

      // Reasoning (THINKING) tokens → a reasoning output item at output_index 0.
      if (token.reasoning() != null) {
        if (reasoningOpen.compareAndSet(false, true)) {
          events.add(
            outputItemEvent("response.output_item.added", seq, 0, reasoningItem(reasoningId, null))
          );
          events.add(
            reasoningSummaryPartEvent("response.reasoning_summary_part.added", seq, reasoningId, "")
          );
        }
        reasoning.append(token.reasoning());
        events.add(reasoningSummaryDelta(seq, reasoningId, token.reasoning()));
        return Flowable.fromIterable(events);
      }

      // Content tokens → the message item (output_index 1 when reasoning preceded it, else 0).
      // Stray TOOL-channel deltas degrade to content here (fail-open — the buffered variant
      // handles tool-aware requests).
      String contentDelta = token.tool() != null ? token.tool() : token.token();
      if (contentDelta != null && !contentDelta.isEmpty()) {
        if (reasoningOpen.get() && reasoningClosed.compareAndSet(false, true)) {
          closeReasoning(events, seq, reasoningId, reasoning.toString());
        }
        if (contentOpen.compareAndSet(false, true)) {
          msgIndex.set(reasoningOpen.get() ? 1 : 0);
          events.add(
            outputItemEvent(
              "response.output_item.added",
              seq,
              msgIndex.get(),
              responsesMessageItem(msgId, "in_progress", null)
            )
          );
          events.add(
            contentPartEvent("response.content_part.added", seq, msgId, msgIndex.get(), "")
          );
        }
        content.append(contentDelta);
        events.add(outputTextDeltaEvent(seq, msgId, msgIndex.get(), contentDelta));
      }

      return events.isEmpty() ? Flowable.empty() : Flowable.fromIterable(events);
    });
  }

  /**
   * Buffers all tokens, then emits Responses API SSE events. If the model produced tool calls,
   * emits {@code function_call} items instead of {@code output_text.delta}.
   *
   * @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch
   */
  public static Flowable<ServerEvent> responsesBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas,
    String responseIdOverride
  ) {
    // Buffering must not swallow the PROGRESS side-channel: gravitee.progress
    // events stream through LIVE (a plan update the client sees only after the
    // turn ends is useless), while everything else accumulates as before. The
    // sequence counter is shared so numbering stays monotonic across both.
    return Flowable.defer(() -> {
      SequenceAccumulator accumulator = new SequenceAccumulator();
      AtomicLong seq = new AtomicLong(0);
      AtomicBoolean reasoningOpened = new AtomicBoolean(false);
      AtomicBoolean headerEmitted = new AtomicBoolean(false);
      String responseId = responseIdOverride != null
        ? responseIdOverride
        : "resp-" + accumulator.created();
      return tokenStream
        .concatMap(t -> {
          if (t.progress() != null) {
            // The header pair must precede ANY outbound event — a live progress
            // or reasoning event before response.created violates the lifecycle.
            List<ServerEvent> live = new ArrayList<>(3);
            addHeaderPair(live, headerEmitted, seq, responseId, accumulator.created(), modelName);
            live.add(progressEvent(seq, t.progress()));
            return Flowable.fromIterable(live);
          }
          accumulator.add(t);
          if (t.reasoning() != null) {
            // Reasoning is the one channel safe to release before tool-call
            // parsing: stream the summary lifecycle LIVE (same shape as the
            // unbuffered path) while content/tool text keeps buffering. The
            // item id matches the final train ("rs-" + created), which also
            // closes the item and re-embeds it in response.completed.
            String rid = "rs-" + accumulator.created();
            List<ServerEvent> live = new ArrayList<>(5);
            addHeaderPair(live, headerEmitted, seq, responseId, accumulator.created(), modelName);
            if (reasoningOpened.compareAndSet(false, true)) {
              live.add(
                outputItemEvent("response.output_item.added", seq, 0, reasoningItem(rid, null))
              );
              live.add(
                reasoningSummaryPartEvent("response.reasoning_summary_part.added", seq, rid, "")
              );
            }
            live.add(reasoningSummaryDelta(seq, rid, t.reasoning()));
            return Flowable.fromIterable(live);
          }
          return Flowable.<ServerEvent>empty();
        })
        .concatWith(
          Flowable.defer(() ->
            responsesBufferedFinalEvents(
              accumulator,
              seq,
              modelName,
              onFinal,
              toolParameterSchemas,
              responseId,
              headerEmitted,
              reasoningOpened.get()
            )
          )
        );
    });
  }

  /**
   * Appends the {@code response.created} / {@code response.in_progress} header pair exactly
   * once per stream, before the first outbound event of any kind (progress, live reasoning or
   * the final train).
   */
  private static void addHeaderPair(
    List<ServerEvent> events,
    AtomicBoolean headerEmitted,
    AtomicLong seq,
    String responseId,
    long created,
    String modelName
  ) {
    if (headerEmitted.getAndSet(true)) {
      return;
    }
    events.add(
      responsesEvent(
        "response.created",
        seq,
        responsesObject(responseId, "in_progress", created, modelName)
      )
    );
    events.add(
      responsesEvent(
        "response.in_progress",
        seq,
        responsesObject(responseId, "in_progress", created, modelName)
      )
    );
  }

  /** The end-of-turn event train for the buffered Responses path (everything except live progress). */
  private static Flowable<ServerEvent> responsesBufferedFinalEvents(
    SequenceAccumulator accumulator,
    AtomicLong seq,
    String modelName,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas,
    String responseId,
    AtomicBoolean headerEmitted,
    boolean reasoningStreamed
  ) {
    if (onFinal != null) {
      onFinal.accept(accumulator.finalToken());
    }

    long created = accumulator.created();
    List<ServerEvent> events = new ArrayList<>();

    addHeaderPair(events, headerEmitted, seq, responseId, created, modelName);

    // Any terminal failure surfaces as response.failed — even after partial
    // output, which a "completed" would misreport as a successful answer. A
    // live-streamed reasoning item is closed first so SDK state machines
    // unwind cleanly; the failed response embeds the partial output.
    if (accumulator.guardMessage() != null) {
      if (reasoningStreamed) {
        closeReasoning(events, seq, "rs-" + created, accumulator.reasoning());
      }
      events.add(
        responsesEvent(
          "response.failed",
          seq,
          failedResponseObject(
            responseId,
            created,
            modelName,
            accumulator.guardMessage(),
            accumulator.reasoning(),
            accumulator.content()
          )
        )
      );
      return Flowable.fromIterable(events);
    }

    // A reasoning item streamed live during the run occupies output_index 0:
    // close its lifecycle before any other item, and shift the rest up.
    int itemBase = reasoningStreamed ? 1 : 0;
    if (reasoningStreamed) {
      closeReasoning(events, seq, "rs-" + created, accumulator.reasoning());
    }

    List<ParsedToolCall> toolCalls = isToolCandidate(accumulator)
      ? resolveToolCalls(accumulator, toolParameterSchemas)
      : List.of();

    if (isToolCandidate(accumulator)) {
      if (!toolCalls.isEmpty()) {
        String narration = narrationText(accumulator, toolCalls);
        if (narration != null) {
          emitBufferedTextItem(events, seq, created, narration, itemBase);
        }
        // The narration message item occupies the next index — the calls
        // shift up so SDK item state machines never collide on an index.
        int indexBase = itemBase + (narration != null ? 1 : 0);
        for (int i = 0; i < toolCalls.size(); i++) {
          ParsedToolCall tc = toolCalls.get(i);
          // Full item lifecycle, not just arguments.done: stock Responses
          // SDKs assemble the response from output_item.added/done events
          // and render NOTHING when a call arrives without them (observed
          // live with an agent harness on /v1/responses).
          events.add(
            outputItemEvent(
              "response.output_item.added",
              seq,
              indexBase + i,
              functionCallItem(tc, "in_progress")
            )
          );
          ObjectNode fcEvent = OBJECT_MAPPER.get().createObjectNode();
          fcEvent.put("type", "response.function_call_arguments.done");
          fcEvent.put("sequence_number", seq.getAndIncrement());
          fcEvent.put("item_id", tc.id());
          fcEvent.put("output_index", indexBase + i);
          fcEvent.put("call_id", tc.id());
          fcEvent.put("name", tc.name());
          fcEvent.put("arguments", tc.arguments());
          events.add(new ServerEvent(fcEvent.toString()));
          events.add(
            outputItemEvent(
              "response.output_item.done",
              seq,
              indexBase + i,
              functionCallItem(tc, "completed")
            )
          );
        }
      } else {
        emitBufferedTextItem(events, seq, created, contentWithToolFallback(accumulator), itemBase);
      }
    } else {
      if (!accumulator.content().isEmpty()) {
        emitBufferedTextItem(events, seq, created, accumulator.content(), itemBase);
      }
    }

    // response.completed embeds the assembled output (no [DONE] — that is a Chat convention).
    ObjectNode completed = responsesObject(responseId, "completed", created, modelName);
    ArrayNode output = completed.putArray("output");
    buildOutputItems(output, accumulator, toolCalls, created);
    writeResponsesUsage(completed, accumulator.promptTokens(), accumulator.completionTokens());
    events.add(responsesEvent("response.completed", seq, completed));
    return Flowable.fromIterable(events);
  }

  /**
   * Builds a non-streaming OpenAI Responses API response: a {@code "response"} object with
   * {@code output} items and {@code usage} input/output token counts.
   *
   * @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch
   */
  public static ObjectNode buildResponsesResponse(
    String modelName,
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas,
    String responseIdOverride
  ) {
    ObjectNode response = OBJECT_MAPPER.get().createObjectNode();
    long created = accumulator.created();
    response.put("id", responseIdOverride != null ? responseIdOverride : "resp-" + created);
    response.put("object", "response");
    response.put("created_at", created);
    response.put("model", modelName);

    // A FAILED event (e.g. previous_response_not_found, guard block) renders as
    // the OpenAI failed-response shape — even after partial output, which a
    // "completed" would misreport as a successful answer. Whatever partial
    // output exists is attached (reasoning item, "incomplete" message).
    if (accumulator.guardMessage() != null) {
      response.put("status", "failed");
      ArrayNode partial = response.putArray("output");
      if (!accumulator.reasoning().isEmpty()) {
        partial.add(reasoningItem("rs-" + created, accumulator.reasoning()));
      }
      if (!accumulator.content().isEmpty()) {
        partial.add(responsesMessageItem("msg-" + created, "incomplete", accumulator.content()));
      }
      ResponsesEventFactory.writeFailure(response, accumulator.guardMessage());
      return response;
    }
    response.put("status", "completed");

    ArrayNode output = response.putArray("output");
    List<ParsedToolCall> toolCalls = isToolCandidate(accumulator)
      ? resolveToolCalls(accumulator, toolParameterSchemas)
      : List.of();
    buildOutputItems(output, accumulator, toolCalls, created);
    writeResponsesUsage(response, accumulator.promptTokens(), accumulator.completionTokens());
    return response;
  }

  /**
   * Assembles the final {@code output} array shared by the buffered-stream and non-streaming
   * paths: reasoning item, then narration → function_call items (or the fail-open text), or the
   * plain content message.
   */
  private static void buildOutputItems(
    ArrayNode output,
    SequenceAccumulator accumulator,
    List<ParsedToolCall> toolCalls,
    long created
  ) {
    if (!accumulator.reasoning().isEmpty()) {
      output.add(reasoningItem("rs-" + created, accumulator.reasoning()));
    }
    String msgId = "msg-" + created;
    if (isToolCandidate(accumulator)) {
      if (!toolCalls.isEmpty()) {
        String narration = narrationText(accumulator, toolCalls);
        if (narration != null) {
          output.add(responsesMessageItem(msgId, "completed", narration));
        }
        for (ParsedToolCall tc : toolCalls) {
          output.add(functionCallItem(tc, "completed"));
        }
      } else {
        output.add(responsesMessageItem(msgId, "completed", contentWithToolFallback(accumulator)));
      }
    } else {
      output.add(responsesMessageItem(msgId, "completed", accumulator.content()));
    }
  }

  /** Writes the Responses-shaped {@code usage} block ({@code input/output/total_tokens}). */
  private static void writeResponsesUsage(
    ObjectNode response,
    int promptTokens,
    int completionTokens
  ) {
    var usage = new ResponsesUsage(promptTokens, completionTokens);
    response.set("usage", OBJECT_MAPPER.get().valueToTree(usage));
  }
}
