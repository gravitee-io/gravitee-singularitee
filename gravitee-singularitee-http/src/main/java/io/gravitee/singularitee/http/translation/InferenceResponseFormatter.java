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

import static io.gravitee.singularitee.http.json.Utils.writeCompletionTokensDetails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formats a {@code Flowable<TokenMessage>} into OpenAI-compatible responses: Chat Completions,
 * legacy Completions and Responses API, in both streaming (SSE {@link ServerEvent}) and buffered
 * (aggregate JSON) forms. {@code reasoning}/thinking deltas render as {@code reasoning_content}.
 */
public final class InferenceResponseFormatter {

  private static final Logger log = LoggerFactory.getLogger(InferenceResponseFormatter.class);

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  /**
   * Tool-markup openers recognized by {@link #parseToolCalls(String)}. Content suffixes that are a
   * prefix of one of these are held back during streaming until disambiguated.
   */
  private static final List<String> TOOL_MARKUP_OPENERS = List.of(
    "<tool_call>",
    "<function=",
    "<|tool_call>"
  );

  private InferenceResponseFormatter() {}

  // ═══════════════════════════════════════════════════════════════════════
  // SSE streaming helpers
  // ═══════════════════════════════════════════════════════════════════════

  public static Flowable<ServerEvent> chatStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    long created = Instant.now().getEpochSecond();
    String responseId = "chatcmpl-" + created;
    AtomicBoolean roleEmitted = new AtomicBoolean(false);

    // Progress updates are not part of the Chat Completions contract — dropped.
    return tokenStream
      .filter(t -> t.progress() == null)
      .flatMap(token -> {
        if (token.isFinal()) {
          if (onFinal != null) {
            onFinal.accept(token);
          }
          ObjectNode finalChunk = chatChunk(
            responseId,
            created,
            modelName,
            null,
            token.finishReason(),
            true
          );
          List<ServerEvent> events = new ArrayList<>();
          events.add(new ServerEvent(finalChunk.toString()));
          if (includeUsage) {
            events.add(
              new ServerEvent(usageChunk(responseId, created, modelName, true, token).toString())
            );
          }
          events.add(new ServerEvent("[DONE]"));
          return Flowable.fromIterable(events);
        }

        boolean isReasoning = token.reasoning() != null;
        // Tool-request-less path: a stray TOOL-channel delta degrades to plain content (fail-open).
        String content = isReasoning ? null : (token.tool() != null ? token.tool() : token.token());
        String reasoning = isReasoning ? token.reasoning() : null;

        // Emit a separate role-only delta before the first content/reasoning token.
        if (!roleEmitted.getAndSet(true)) {
          ObjectNode roleChunk = chatChunk(responseId, created, modelName, "", null, null, false);
          ObjectNode firstChunk = chatChunk(
            responseId,
            created,
            modelName,
            content,
            reasoning,
            null,
            true,
            token.logprobs()
          );
          return Flowable.just(
            new ServerEvent(roleChunk.toString()),
            new ServerEvent(firstChunk.toString())
          );
        }

        ObjectNode chunk = chatChunk(
          responseId,
          created,
          modelName,
          content,
          reasoning,
          null,
          true,
          token.logprobs()
        );
        return Flowable.just(new ServerEvent(chunk.toString()));
      });
  }

  /**
   * Streams Chat Completions SSE events live, holding back only (potential) tool-call markup.
   *
   * <p>Content and reasoning deltas are emitted as they arrive, exactly like
   * {@link #chatStreamEvents}, except that a suffix of the content stream that is a prefix of a
   * tool-markup opener ({@code <tool_call>} / {@code <function=} / {@code <|tool_call>}) is
   * withheld until it is
   * disambiguated. Once an opener is confirmed, the remainder of the generation is buffered
   * silently; on the final token the full accumulated content is parsed with
   * {@link #parseToolCalls(String)} and emitted as structured {@code delta.tool_calls} chunks with
   * finish reason {@code tool_calls}. Text preceding the first opener has already streamed, which
   * matches OpenAI semantics (content, then tool_calls). If the opener was a false alarm (finish
   * {@code stop}, or the markup does not parse), the withheld text is flushed as ordinary content
   * before the final chunk.
   */
  public static Flowable<ServerEvent> chatStreamEventsWithToolHoldback(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    return chatStreamEventsWithToolHoldback(
      tokenStream,
      modelName,
      includeUsage,
      onFinal,
      Map.of()
    );
  }

  public static Flowable<ServerEvent> chatStreamEventsWithToolHoldback(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    long created = Instant.now().getEpochSecond();
    String responseId = "chatcmpl-" + created;
    AtomicBoolean roleEmitted = new AtomicBoolean(false);
    AtomicBoolean toolModeConfirmed = new AtomicBoolean(false);
    // All content seen so far (streamed + withheld), for parseToolCalls on the final token.
    StringBuilder fullContent = new StringBuilder();
    // Content not yet emitted: the hold-back tail, or everything after a confirmed opener.
    StringBuilder pending = new StringBuilder();
    // Bare tool payload from TOOL-channel deltas (engines that suppress tag markers). Never
    // streamed as content — the channel signal replaces the marker-prefix holdback.
    StringBuilder toolContent = new StringBuilder();

    // Progress updates are not part of the Chat Completions contract — dropped.
    return tokenStream
      .filter(t -> t.progress() == null)
      .flatMap(token -> {
        List<ServerEvent> events = new ArrayList<>();

        if (token.isFinal()) {
          if (onFinal != null) {
            onFinal.accept(token);
          }
          if (!roleEmitted.getAndSet(true)) {
            events.add(
              new ServerEvent(chatChunk(responseId, created, modelName, "", null, false).toString())
            );
          }

          String finishReason = token.finishReason();
          List<ParsedToolCall> wireCalls = fromWireToolCalls(
            token.toolCalls(),
            toolParameterSchemas
          );
          boolean toolCandidate =
            "tool_calls".equals(finishReason) || toolModeConfirmed.get() || !toolContent.isEmpty();
          List<ParsedToolCall> toolCalls = !wireCalls.isEmpty()
            ? wireCalls
            : (toolCandidate
                ? resolveToolCalls(
                  toolContent.toString(),
                  fullContent.toString(),
                  toolParameterSchemas
                )
                : List.<ParsedToolCall>of());

          if (!toolCalls.isEmpty()) {
            for (int i = 0; i < toolCalls.size(); i++) {
              events.add(
                new ServerEvent(
                  chatToolCallChunk(responseId, created, modelName, i, toolCalls.get(i)).toString()
                )
              );
            }
            events.add(
              new ServerEvent(
                chatChunk(responseId, created, modelName, null, "tool_calls", true).toString()
              )
            );
          } else {
            // False alarm (or plain stop): flush any withheld tail — and any unparseable bare
            // tool payload (fail-open) — as ordinary content.
            pending.append(toolContent);
            toolContent.setLength(0);
            if (!pending.isEmpty()) {
              events.add(
                new ServerEvent(
                  chatChunk(
                    responseId,
                    created,
                    modelName,
                    pending.toString(),
                    null,
                    true
                  ).toString()
                )
              );
              pending.setLength(0);
            }
            events.add(
              new ServerEvent(
                chatChunk(responseId, created, modelName, null, finishReason, true).toString()
              )
            );
          }

          if (includeUsage) {
            events.add(
              new ServerEvent(usageChunk(responseId, created, modelName, true, token).toString())
            );
          }
          events.add(new ServerEvent("[DONE]"));
          return Flowable.fromIterable(events);
        }

        boolean isReasoning = token.reasoning() != null;

        if (!roleEmitted.getAndSet(true)) {
          events.add(
            new ServerEvent(
              chatChunk(responseId, created, modelName, "", null, null, false).toString()
            )
          );
        }

        // TOOL-channel deltas are buffered silently (never streamed as content); they are
        // parsed into structured tool_calls on the final token.
        if (token.tool() != null) {
          toolContent.append(token.tool());
          return events.isEmpty() ? Flowable.<ServerEvent>empty() : Flowable.fromIterable(events);
        }

        // Reasoning deltas pass through untouched.
        if (isReasoning) {
          events.add(
            new ServerEvent(
              chatChunk(
                responseId,
                created,
                modelName,
                null,
                token.reasoning(),
                null,
                true
              ).toString()
            )
          );
          return Flowable.fromIterable(events);
        }

        String delta = token.token();
        if (delta != null && !delta.isEmpty()) {
          fullContent.append(delta);
          if (!toolModeConfirmed.get()) {
            pending.append(delta);
            int openerIndex = indexOfToolMarkupOpener(pending);
            if (openerIndex >= 0) {
              // Opener confirmed: emit what precedes it, then buffer silently.
              toolModeConfirmed.set(true);
              String safe = pending.substring(0, openerIndex);
              pending.delete(0, openerIndex);
              if (!safe.isEmpty()) {
                events.add(
                  new ServerEvent(
                    chatChunk(responseId, created, modelName, safe, null, null, true).toString()
                  )
                );
              }
            } else {
              int holdback = toolMarkupHoldbackLength(pending);
              String safe = pending.substring(0, pending.length() - holdback);
              pending.delete(0, pending.length() - holdback);
              if (!safe.isEmpty()) {
                events.add(
                  new ServerEvent(
                    chatChunk(responseId, created, modelName, safe, null, null, true).toString()
                  )
                );
              }
            }
          } else {
            pending.append(delta);
          }
        }

        return events.isEmpty() ? Flowable.<ServerEvent>empty() : Flowable.fromIterable(events);
      });
  }

  /** Index of the first complete tool-markup opener in {@code text}, or -1 if none. */
  private static int indexOfToolMarkupOpener(CharSequence text) {
    String s = text.toString();
    int best = -1;
    for (String opener : TOOL_MARKUP_OPENERS) {
      int idx = s.indexOf(opener);
      if (idx >= 0 && (best < 0 || idx < best)) {
        best = idx;
      }
    }
    return best;
  }

  /**
   * Length of the longest suffix of {@code text} that is a proper prefix of a tool-markup opener —
   * the number of trailing chars that must be withheld from streaming.
   */
  private static int toolMarkupHoldbackLength(CharSequence text) {
    String s = text.toString();
    int maxOpener = 0;
    for (String opener : TOOL_MARKUP_OPENERS) {
      maxOpener = Math.max(maxOpener, opener.length());
    }
    int limit = Math.min(s.length(), maxOpener - 1);
    for (int k = limit; k > 0; k--) {
      String suffix = s.substring(s.length() - k);
      for (String opener : TOOL_MARKUP_OPENERS) {
        if (opener.startsWith(suffix)) {
          return k;
        }
      }
    }
    return 0;
  }

  public static Flowable<ServerEvent> completionStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    long created = Instant.now().getEpochSecond();
    String responseId = "cmpl-" + created;

    // Progress updates are not part of the Completions contract — dropped.
    return tokenStream
      .filter(t -> t.progress() == null)
      .flatMap(token -> {
        if (token.isFinal()) {
          if (onFinal != null) {
            onFinal.accept(token);
          }
          ObjectNode finalChunk = completionChunk(
            responseId,
            created,
            modelName,
            null,
            token.finishReason()
          );
          List<ServerEvent> events = new ArrayList<>();
          events.add(new ServerEvent(finalChunk.toString()));
          if (includeUsage) {
            events.add(
              new ServerEvent(usageChunk(responseId, created, modelName, false, token).toString())
            );
          }
          events.add(new ServerEvent("[DONE]"));
          return Flowable.fromIterable(events);
        }
        String text = token.tool() != null ? token.tool() : token.token();
        return Flowable.just(
          new ServerEvent(completionChunk(responseId, created, modelName, text, null).toString())
        );
      });
  }

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
   */
  public static Flowable<ServerEvent> responsesStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal
  ) {
    return responsesStreamEvents(tokenStream, modelName, onFinal, null);
  }

  /** @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch */
  public static Flowable<ServerEvent> responsesStreamEvents(
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
        // A guard-only failure with no generated content (previous_response_not_found,
        // guard block) must surface as response.failed — a fake empty "completed"
        // reads as a real blank answer and hides the error from streaming clients.
        if (token.guardMessage() != null && !reasoningOpen.get() && !contentOpen.get()) {
          events.add(
            responseFailedEvent(seq, responseId, created, modelName, token.guardMessage())
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
        ObjectNode usage = completed.putObject("usage");
        usage.put("input_tokens", token.promptTokens());
        usage.put("output_tokens", token.completionTokens());
        usage.put("total_tokens", token.promptTokens() + token.completionTokens());
        writeTimings(completed, token.performance());
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
        ObjectNode delta = OBJECT_MAPPER.get().createObjectNode();
        delta.put("type", "response.output_text.delta");
        delta.put("sequence_number", seq.getAndIncrement());
        delta.put("item_id", msgId);
        delta.put("output_index", msgIndex.get());
        delta.put("content_index", 0);
        delta.put("delta", contentDelta);
        events.add(new ServerEvent(delta.toString()));
      }

      return events.isEmpty() ? Flowable.empty() : Flowable.fromIterable(events);
    });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Buffered-then-SSE streaming (for tool-aware requests)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Buffers all tokens, then emits Chat Completions SSE events. If the model produced tool calls,
   * emits structured {@code delta.tool_calls} instead of raw content, avoiding leaked markup.
   */
  public static Flowable<ServerEvent> chatBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    return chatBufferedStreamEvents(tokenStream, modelName, includeUsage, onFinal, Map.of());
  }

  public static Flowable<ServerEvent> chatBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return tokenStream
      .filter(t -> t.progress() == null)
      .collect(SequenceAccumulator::new, SequenceAccumulator::add)
      .flatMapPublisher(accumulator -> {
        if (onFinal != null) {
          onFinal.accept(
            new TokenMessage(
              null,
              0,
              true,
              accumulator.finishReason(),
              accumulator.promptTokens(),
              accumulator.completionTokens(),
              null,
              null,
              accumulator.performance(),
              accumulator.guardMessage()
            )
          );
        }

        long created = accumulator.created();
        String responseId = "chatcmpl-" + created;
        List<ServerEvent> events = new ArrayList<>();

        // Role-only chunk (always first).
        events.add(
          new ServerEvent(chatChunk(responseId, created, modelName, "", null, false).toString())
        );

        if (isToolCandidate(accumulator)) {
          List<ParsedToolCall> toolCalls = resolveToolCalls(accumulator, toolParameterSchemas);
          if (!toolCalls.isEmpty()) {
            String narration = narrationText(accumulator, toolCalls);
            if (narration != null) {
              events.add(
                new ServerEvent(
                  chatChunk(responseId, created, modelName, narration, null, false).toString()
                )
              );
            }
            for (int i = 0; i < toolCalls.size(); i++) {
              ParsedToolCall tc = toolCalls.get(i);
              ObjectNode chunk = chatToolCallChunk(responseId, created, modelName, i, tc);
              events.add(new ServerEvent(chunk.toString()));
            }
            ObjectNode finalChunk = chatChunk(
              responseId,
              created,
              modelName,
              null,
              "tool_calls",
              true
            );
            events.add(new ServerEvent(finalChunk.toString()));
          } else {
            events.add(
              new ServerEvent(
                chatChunk(
                  responseId,
                  created,
                  modelName,
                  contentWithToolFallback(accumulator),
                  "stop",
                  true
                ).toString()
              )
            );
          }
        } else {
          if (!accumulator.content().isEmpty()) {
            events.add(
              new ServerEvent(
                chatChunk(
                  responseId,
                  created,
                  modelName,
                  accumulator.content(),
                  null,
                  true
                ).toString()
              )
            );
          }
          events.add(
            new ServerEvent(
              chatChunk(
                responseId,
                created,
                modelName,
                null,
                accumulator.finishReason(),
                true
              ).toString()
            )
          );
        }

        if (includeUsage) {
          TokenMessage usageToken = new TokenMessage(
            null,
            0,
            true,
            accumulator.finishReason(),
            accumulator.promptTokens(),
            accumulator.completionTokens(),
            null,
            null,
            null,
            null
          );
          events.add(
            new ServerEvent(usageChunk(responseId, created, modelName, true, usageToken).toString())
          );
        }
        events.add(new ServerEvent("[DONE]"));
        return Flowable.fromIterable(events);
      });
  }

  /**
   * Buffers all tokens, then emits Responses API SSE events. If the model produced tool calls,
   * emits {@code function_call} items instead of {@code output_text.delta}.
   */
  public static Flowable<ServerEvent> responsesBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal
  ) {
    return responsesBufferedStreamEvents(tokenStream, modelName, onFinal, Map.of());
  }

  public static Flowable<ServerEvent> responsesBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return responsesBufferedStreamEvents(
      tokenStream,
      modelName,
      onFinal,
      toolParameterSchemas,
      null
    );
  }

  /** @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch */
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
      java.util.concurrent.atomic.AtomicBoolean reasoningOpened =
        new java.util.concurrent.atomic.AtomicBoolean(false);
      return tokenStream
        .concatMap(t -> {
          if (t.progress() != null) {
            return Flowable.just(progressEvent(seq, t.progress()));
          }
          accumulator.add(t);
          if (t.reasoning() != null) {
            // Reasoning is the one channel safe to release before tool-call
            // parsing: stream the summary lifecycle LIVE (same shape as the
            // unbuffered path) while content/tool text keeps buffering. The
            // item id matches the final train ("rs-" + created), which also
            // closes the item and re-embeds it in response.completed.
            String rid = "rs-" + accumulator.created();
            List<ServerEvent> live = new ArrayList<>(3);
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
              responseIdOverride,
              reasoningOpened.get()
            )
          )
        );
    });
  }

  /** The end-of-turn event train for the buffered Responses path (everything except live progress). */
  private static Flowable<ServerEvent> responsesBufferedFinalEvents(
    SequenceAccumulator accumulator,
    AtomicLong seq,
    String modelName,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas,
    String responseIdOverride,
    boolean reasoningStreamed
  ) {
    {
      if (onFinal != null) {
        onFinal.accept(
          new TokenMessage(
            null,
            0,
            true,
            accumulator.finishReason(),
            accumulator.promptTokens(),
            accumulator.completionTokens(),
            null,
            null,
            accumulator.performance(),
            accumulator.guardMessage()
          )
        );
      }

      long created = accumulator.created();
      String responseId = responseIdOverride != null ? responseIdOverride : "resp-" + created;
      List<ServerEvent> events = new ArrayList<>();

      ObjectNode createdEvent = OBJECT_MAPPER.get().createObjectNode();
      createdEvent.put("type", "response.created");
      createdEvent.put("sequence_number", seq.getAndIncrement());
      ObjectNode responseMeta = createdEvent.putObject("response");
      responseMeta.put("id", responseId);
      responseMeta.put("object", "response");
      responseMeta.put("status", "in_progress");
      responseMeta.put("created_at", created);
      responseMeta.put("model", modelName);
      events.add(new ServerEvent(createdEvent.toString()));

      // Guard-only failure with no generated content: surface response.failed
      // instead of a fake empty "completed" (mirrors buildResponsesResponse).
      if (
        accumulator.guardMessage() != null &&
        accumulator.content().isEmpty() &&
        accumulator.reasoning().isEmpty()
      ) {
        events.add(
          responseFailedEvent(seq, responseId, created, modelName, accumulator.guardMessage())
        );
        return Flowable.fromIterable(events);
      }

      // A reasoning item streamed live during the run occupies output_index 0:
      // close its lifecycle before any other item, and shift the rest up.
      int itemBase = reasoningStreamed ? 1 : 0;
      if (reasoningStreamed) {
        closeReasoning(events, seq, "rs-" + created, accumulator.reasoning());
      }

      List<ParsedToolCall> resolvedToolCalls = isToolCandidate(accumulator)
        ? resolveToolCalls(accumulator, toolParameterSchemas)
        : List.of();

      if (isToolCandidate(accumulator)) {
        List<ParsedToolCall> toolCalls = resolvedToolCalls;
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
          emitBufferedTextItem(
            events,
            seq,
            created,
            contentWithToolFallback(accumulator),
            itemBase
          );
        }
      } else {
        if (!accumulator.content().isEmpty()) {
          emitBufferedTextItem(events, seq, created, accumulator.content(), itemBase);
        }
      }

      // response.completed embeds the assembled output (no [DONE] — that is a Chat convention).
      ObjectNode completed = responsesObject(responseId, "completed", created, modelName);
      ArrayNode output = completed.putArray("output");
      if (!accumulator.reasoning().isEmpty()) {
        output.add(reasoningItem("rs-" + created, accumulator.reasoning()));
      }
      if (isToolCandidate(accumulator)) {
        if (!resolvedToolCalls.isEmpty()) {
          String narration = narrationText(accumulator, resolvedToolCalls);
          if (narration != null) {
            addTextOutputItem(output, narration);
          }
          for (ParsedToolCall tc : resolvedToolCalls) {
            ObjectNode item = output.addObject();
            item.put("type", "function_call");
            item.put("id", tc.id());
            item.put("call_id", tc.id());
            item.put("name", tc.name());
            item.put("arguments", tc.arguments());
          }
        } else {
          addTextOutputItem(output, contentWithToolFallback(accumulator));
        }
      } else {
        addTextOutputItem(output, accumulator.content());
      }
      ObjectNode usage = completed.putObject("usage");
      usage.put("input_tokens", accumulator.promptTokens());
      usage.put("output_tokens", accumulator.completionTokens());
      usage.put("total_tokens", accumulator.promptTokens() + accumulator.completionTokens());
      writeTimings(completed, accumulator.performance());
      events.add(responsesEvent("response.completed", seq, completed));
      return Flowable.fromIterable(events);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Responses API event helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** A minimal Responses object ({@code id/object/status/created_at/model}); caller adds output/usage. */
  /**
   * Renders a {@code RESPONSE_EVENT_TYPE_PROGRESS} update as a gravitee-namespaced
   * Responses-API event: {@code {"type":"gravitee.progress","sequence_number":n,
   * "step_id":…, "todos":[{id,title,status}…], "completed":n, "total":n}}.
   */
  private static ServerEvent progressEvent(
    AtomicLong seq,
    io.gravitee.singularitee.protocol.ResponseProgress progress
  ) {
    ObjectNode node = OBJECT_MAPPER.get().createObjectNode();
    node.put("type", "gravitee.progress");
    node.put("sequence_number", seq.getAndIncrement());
    node.put("step_id", progress.getStepId());
    ArrayNode todos = node.putArray("todos");
    for (var t : progress.getTodosList()) {
      ObjectNode item = todos.addObject();
      item.put("id", t.getId());
      item.put("title", t.getTitle());
      item.put("status", t.getStatus());
      if (!t.getProof().isEmpty()) {
        item.put("proof", t.getProof());
      }
    }
    node.put("completed", progress.getCompleted());
    node.put("total", progress.getTotal());
    node.put("text", progressText(progress));
    return new ServerEvent(node.toString());
  }

  /**
   * Preformatted multi-line plan view ({@code "1. [x] title\n2. [>] …"}) so a client can
   * print the plan directly instead of laying out the structured items itself.
   * Markers: {@code [x]} done, {@code [>]} in_progress, {@code [ ]} pending.
   */
  private static String progressText(io.gravitee.singularitee.protocol.ResponseProgress progress) {
    StringBuilder sb = new StringBuilder();
    int index = 1;
    for (var t : progress.getTodosList()) {
      String marker = switch (t.getStatus()) {
        case "done" -> "[x]";
        case "in_progress" -> "[>]";
        default -> "[ ]";
      };
      if (index > 1) {
        sb.append('\n');
      }
      sb.append(index++).append(". ").append(marker).append(' ').append(t.getTitle());
      if (!t.getProof().isEmpty()) {
        sb.append(" — proof: ").append(t.getProof());
      }
    }
    return sb.toString();
  }

  /** A Responses-API {@code function_call} output item. */
  private static ObjectNode functionCallItem(ParsedToolCall tc, String status) {
    ObjectNode item = OBJECT_MAPPER.get().createObjectNode();
    item.put("type", "function_call");
    item.put("id", tc.id());
    item.put("call_id", tc.id());
    item.put("name", tc.name());
    item.put("arguments", tc.arguments());
    item.put("status", status);
    return item;
  }

  private static ObjectNode responsesObject(String id, String status, long created, String model) {
    ObjectNode r = OBJECT_MAPPER.get().createObjectNode();
    r.put("id", id);
    r.put("object", "response");
    r.put("status", status);
    r.put("created_at", created);
    r.put("model", model);
    return r;
  }

  /** A Responses {@code message} output item; {@code text == null} → empty content (item still open). */
  /**
   * Emits a full message-item lifecycle for a buffered text answer:
   * output_item.added -> content_part.added -> output_text.delta ->
   * output_text.done -> content_part.done -> output_item.done. Stock Responses
   * SDKs assemble the response from these item events and render NOTHING for a
   * bare output_text.delta (observed live: an agent TUI showing empty answers
   * whenever tools were declared, because tools select the buffered path).
   */
  private static void emitBufferedTextItem(
    List<ServerEvent> events,
    AtomicLong seq,
    long created,
    String text
  ) {
    emitBufferedTextItem(events, seq, created, text, 0);
  }

  private static void emitBufferedTextItem(
    List<ServerEvent> events,
    AtomicLong seq,
    long created,
    String text,
    int outputIndex
  ) {
    String msgId = "msg-" + created;
    events.add(
      outputItemEvent(
        "response.output_item.added",
        seq,
        outputIndex,
        responsesMessageItem(msgId, "in_progress", null)
      )
    );
    events.add(contentPartEvent("response.content_part.added", seq, msgId, outputIndex, ""));
    ObjectNode delta = OBJECT_MAPPER.get().createObjectNode();
    delta.put("type", "response.output_text.delta");
    delta.put("sequence_number", seq.getAndIncrement());
    delta.put("item_id", msgId);
    delta.put("output_index", outputIndex);
    delta.put("content_index", 0);
    delta.put("delta", text);
    events.add(new ServerEvent(delta.toString()));
    events.add(outputTextDoneEvent(seq, msgId, outputIndex, text));
    events.add(contentPartEvent("response.content_part.done", seq, msgId, outputIndex, text));
    events.add(
      outputItemEvent(
        "response.output_item.done",
        seq,
        outputIndex,
        responsesMessageItem(msgId, "completed", text)
      )
    );
  }

  /**
   * The assistant's NARRATION accompanying tool calls — the visible text a model
   * writes before calling ("I'll create the engine module now"), which OpenAI
   * delivers as content alongside tool_calls (Chat) or a message item before the
   * function_call items (Responses). Returns null when there is none, or when
   * the text IS the call payload (markerless dialects put the call in the
   * content — echoing it as narration would duplicate every call as prose).
   */
  private static String narrationText(SequenceAccumulator accumulator, List<ParsedToolCall> calls) {
    String content = accumulator.content();
    if (content == null || content.isBlank()) {
      return null;
    }
    for (ParsedToolCall tc : calls) {
      String args = tc.arguments();
      if (args != null && args.length() > 5 && content.contains(args)) {
        return null;
      }
      if (tc.name() != null && content.trim().startsWith(tc.name())) {
        return null;
      }
    }
    return content.trim();
  }

  private static ObjectNode responsesMessageItem(String id, String status, String text) {
    ObjectNode item = OBJECT_MAPPER.get().createObjectNode();
    item.put("id", id);
    item.put("type", "message");
    item.put("status", status);
    item.put("role", "assistant");
    ArrayNode content = item.putArray("content");
    if (text != null) {
      ObjectNode part = content.addObject();
      part.put("type", "output_text");
      part.put("text", text);
      part.putArray("annotations");
    }
    return item;
  }

  /** Wraps a response object in a top-level Responses stream event ({@code response.*}). */
  /** Terminal {@code response.failed} event for guard-only failures on the streaming paths. */
  private static ServerEvent responseFailedEvent(
    AtomicLong seq,
    String responseId,
    long created,
    String modelName,
    String guardMessage
  ) {
    ObjectNode failed = responsesObject(responseId, "failed", created, modelName);
    ObjectNode error = failed.putObject("error");
    error.put(
      "code",
      guardMessage.startsWith("No stored response") ? "previous_response_not_found" : "server_error"
    );
    error.put("message", guardMessage);
    failed.putArray("output");
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.failed");
    event.put("sequence_number", seq.getAndIncrement());
    event.set("response", failed);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent responsesEvent(String type, AtomicLong seq, ObjectNode response) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.set("response", response);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent outputItemEvent(
    String type,
    AtomicLong seq,
    int outputIndex,
    ObjectNode item
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("output_index", outputIndex);
    event.set("item", item);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent contentPartEvent(
    String type,
    AtomicLong seq,
    String itemId,
    int outputIndex,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", outputIndex);
    event.put("content_index", 0);
    ObjectNode part = event.putObject("part");
    part.put("type", "output_text");
    part.put("text", text);
    part.putArray("annotations");
    return new ServerEvent(event.toString());
  }

  private static ServerEvent outputTextDoneEvent(
    AtomicLong seq,
    String itemId,
    int outputIndex,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.output_text.done");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", outputIndex);
    event.put("content_index", 0);
    event.put("text", text);
    return new ServerEvent(event.toString());
  }

  /** A Responses {@code reasoning} output item; {@code text == null}/empty → empty summary. */
  private static ObjectNode reasoningItem(String id, String text) {
    ObjectNode item = OBJECT_MAPPER.get().createObjectNode();
    item.put("id", id);
    item.put("type", "reasoning");
    ArrayNode summary = item.putArray("summary");
    if (text != null && !text.isEmpty()) {
      ObjectNode s = summary.addObject();
      s.put("type", "summary_text");
      s.put("text", text);
    }
    return item;
  }

  private static ServerEvent reasoningSummaryPartEvent(
    String type,
    AtomicLong seq,
    String itemId,
    String text
  ) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", type);
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    ObjectNode part = event.putObject("part");
    part.put("type", "summary_text");
    part.put("text", text);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent reasoningSummaryDelta(AtomicLong seq, String itemId, String delta) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.reasoning_summary_text.delta");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    event.put("delta", delta);
    return new ServerEvent(event.toString());
  }

  private static ServerEvent reasoningSummaryDone(AtomicLong seq, String itemId, String text) {
    ObjectNode event = OBJECT_MAPPER.get().createObjectNode();
    event.put("type", "response.reasoning_summary_text.done");
    event.put("sequence_number", seq.getAndIncrement());
    event.put("item_id", itemId);
    event.put("output_index", 0);
    event.put("summary_index", 0);
    event.put("text", text);
    return new ServerEvent(event.toString());
  }

  /** Emits the three closing events for a reasoning item (summary done → part done → item done). */
  private static void closeReasoning(
    List<ServerEvent> events,
    AtomicLong seq,
    String reasoningId,
    String text
  ) {
    events.add(reasoningSummaryDone(seq, reasoningId, text));
    events.add(
      reasoningSummaryPartEvent("response.reasoning_summary_part.done", seq, reasoningId, text)
    );
    events.add(
      outputItemEvent("response.output_item.done", seq, 0, reasoningItem(reasoningId, text))
    );
  }

  /** Builds a chat SSE chunk with a structured tool_calls delta. */
  private static ObjectNode chatToolCallChunk(
    String id,
    long created,
    String model,
    int index,
    ParsedToolCall tc
  ) {
    ObjectNode chunk = OBJECT_MAPPER.get().createObjectNode();
    chunk.put("id", id);
    chunk.put("object", "chat.completion.chunk");
    chunk.put("created", created);
    chunk.put("model", model);
    chunk.putNull("system_fingerprint");
    ArrayNode choices = chunk.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    ObjectNode delta = choice.putObject("delta");
    ArrayNode toolCallsArray = delta.putArray("tool_calls");
    ObjectNode toolCall = toolCallsArray.addObject();
    toolCall.put("index", index);
    toolCall.put("id", tc.id());
    toolCall.put("type", "function");
    ObjectNode function = toolCall.putObject("function");
    function.put("name", tc.name());
    function.put("arguments", tc.arguments());
    choice.putNull("logprobs");
    choice.putNull("finish_reason");
    return chunk;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Buffered (non-streaming) response builders
  // ═══════════════════════════════════════════════════════════════════════

  public static ObjectNode buildChatResponse(String modelName, SequenceAccumulator accumulator) {
    return buildChatResponse(modelName, accumulator, Map.of());
  }

  public static ObjectNode buildChatResponse(
    String modelName,
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    ObjectNode response = OBJECT_MAPPER.get().createObjectNode();
    response.put("id", accumulator.responseId("chatcmpl-"));
    response.put("object", "chat.completion");
    response.put("created", accumulator.created());
    response.put("model", modelName);
    response.putNull("system_fingerprint");
    ArrayNode choices = response.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");

    String reasoning = accumulator.reasoning();
    if (reasoning != null && !reasoning.isEmpty()) {
      message.put("reasoning_content", reasoning);
    }

    String finishReason = accumulator.finishReason();
    if (isToolCandidate(accumulator)) {
      List<ParsedToolCall> toolCalls = resolveToolCalls(accumulator, toolParameterSchemas);
      if (!toolCalls.isEmpty()) {
        finishReason = "tool_calls";
        String narration = narrationText(accumulator, toolCalls);
        if (narration != null) {
          message.put("content", narration);
        } else {
          message.putNull("content");
        }
        ArrayNode toolCallsArray = message.putArray("tool_calls");
        for (int i = 0; i < toolCalls.size(); i++) {
          ParsedToolCall tc = toolCalls.get(i);
          ObjectNode toolCallNode = toolCallsArray.addObject();
          toolCallNode.put("id", tc.id());
          toolCallNode.put("type", "function");
          ObjectNode function = toolCallNode.putObject("function");
          function.put("name", tc.name());
          function.put("arguments", tc.arguments());
        }
      } else {
        message.put("content", contentWithToolFallback(accumulator));
      }
    } else {
      message.put("content", accumulator.content());
    }

    writeLogprobs(choice, accumulator.logprobs());
    choice.put("finish_reason", finishReason);
    writeUsage(response, accumulator);
    return response;
  }

  public static ObjectNode buildCompletionResponse(
    String modelName,
    SequenceAccumulator accumulator
  ) {
    ObjectNode response = OBJECT_MAPPER.get().createObjectNode();
    response.put("id", accumulator.responseId("cmpl-"));
    response.put("object", "text_completion");
    response.put("created", accumulator.created());
    response.put("model", modelName);
    ArrayNode choices = response.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    choice.put("text", accumulator.content());
    writeLogprobs(choice, accumulator.logprobs());
    choice.put("finish_reason", accumulator.finishReason());
    writeUsage(response, accumulator);
    return response;
  }

  /**
   * Builds a non-streaming OpenAI Responses API response: a {@code "response"} object with
   * {@code output} items and {@code usage} input/output token counts.
   */
  public static ObjectNode buildResponsesResponse(
    String modelName,
    SequenceAccumulator accumulator
  ) {
    return buildResponsesResponse(modelName, accumulator, Map.of());
  }

  public static ObjectNode buildResponsesResponse(
    String modelName,
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return buildResponsesResponse(modelName, accumulator, toolParameterSchemas, null);
  }

  /** @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch */
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

    // A FAILED event with no generated content (e.g. previous_response_not_found,
    // guard block) renders as the OpenAI failed-response shape, not as an empty
    // "completed" that a client would mistake for a real (blank) answer.
    if (
      accumulator.guardMessage() != null &&
      accumulator.content().isEmpty() &&
      accumulator.reasoning().isEmpty()
    ) {
      response.put("status", "failed");
      ObjectNode error = response.putObject("error");
      error.put(
        "code",
        accumulator.guardMessage().startsWith("No stored response")
          ? "previous_response_not_found"
          : "server_error"
      );
      error.put("message", accumulator.guardMessage());
      response.putArray("output");
      return response;
    }
    response.put("status", "completed");

    ArrayNode output = response.putArray("output");
    if (!accumulator.reasoning().isEmpty()) {
      output.add(reasoningItem("rs-" + created, accumulator.reasoning()));
    }

    if (isToolCandidate(accumulator)) {
      List<ParsedToolCall> toolCalls = resolveToolCalls(accumulator, toolParameterSchemas);
      if (!toolCalls.isEmpty()) {
        String narration = narrationText(accumulator, toolCalls);
        if (narration != null) {
          addTextOutputItem(output, narration);
        }
        for (ParsedToolCall tc : toolCalls) {
          ObjectNode item = output.addObject();
          item.put("type", "function_call");
          item.put("id", tc.id());
          item.put("call_id", tc.id());
          item.put("name", tc.name());
          item.put("arguments", tc.arguments());
        }
      } else {
        addTextOutputItem(output, contentWithToolFallback(accumulator));
      }
    } else {
      addTextOutputItem(output, accumulator.content());
    }

    ObjectNode usage = response.putObject("usage");
    usage.put("input_tokens", accumulator.promptTokens());
    usage.put("output_tokens", accumulator.completionTokens());
    usage.put("total_tokens", accumulator.promptTokens() + accumulator.completionTokens());
    writeTimings(response, accumulator.performance());
    return response;
  }

  private static void addTextOutputItem(ArrayNode output, String text) {
    ObjectNode item = output.addObject();
    item.put("type", "message");
    item.put("role", "assistant");
    item.put("status", "completed");
    ArrayNode content = item.putArray("content");
    ObjectNode textPart = content.addObject();
    textPart.put("type", "output_text");
    textPart.put("text", text);
  }

  /** Writes {@code usage} (including {@code completion_tokens_details}) into the response. */
  private static void writeUsage(ObjectNode response, SequenceAccumulator accumulator) {
    ObjectNode usage = response.putObject("usage");
    usage.put("prompt_tokens", accumulator.promptTokens());
    usage.put("completion_tokens", accumulator.completionTokens());
    usage.put("total_tokens", accumulator.promptTokens() + accumulator.completionTokens());
    accumulator.writeUsageDetails(usage);
    writeTimings(response, accumulator.performance());
  }

  /**
   * Writes the engine-measured {@code timings} object (llama.cpp-server convention) next to
   * {@code usage}. Clients cannot derive tokens/second themselves: wall time includes prompt
   * eval and, for pipelines, every internal step — only the engine knows pure decode time.
   */
  private static void writeTimings(ObjectNode response, PerformanceMessage perf) {
    if (perf == null || (perf.evalTimeMs() <= 0 && perf.promptEvalTimeMs() <= 0)) {
      return;
    }
    ObjectNode timings = response.putObject("timings");
    timings.put("prompt_n", perf.promptTokensEvaluated());
    timings.put("prompt_ms", perf.promptEvalTimeMs());
    timings.put("prompt_per_second", perf.promptTokensPerSecond());
    timings.put("predicted_n", perf.tokensGenerated());
    timings.put("predicted_ms", perf.evalTimeMs());
    timings.put("predicted_per_second", perf.generationTokensPerSecond());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tool call parsing
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Parses tool calls from raw model output into structured tool calls, each assigned a
   * {@code call_<uuid>} id, by rendering the built-in Jinja extraction templates (chatml-json,
   * xml-function, gemma-call — see {@code ToolCallExtractor}) in order until one yields calls.
   * Returns an empty list if nothing extracts (fail-open — the caller surfaces raw text).
   */
  public static List<ParsedToolCall> parseToolCalls(String content) {
    return parseToolCalls(content, Map.of());
  }

  /**
   * Resolves tool calls with the channel-signal-first strategy: the BARE tool payload
   * (accumulated from {@code STEP_ROLE_TOOL} deltas, tag markers suppressed engine-side) is
   * parsed first via {@link #parseBareToolCalls}; when it is empty or unparseable, the legacy
   * marker-based {@link #parseToolCalls(String, Map)} runs over the full content (older
   * engines / vLLM still emit literal tags in the text). Returns an empty list when neither
   * yields calls — callers fail open by flushing the raw text as content.
   */
  /** True when the accumulated stream should be checked for tool calls. */
  private static boolean isToolCandidate(SequenceAccumulator accumulator) {
    return (
      "tool_calls".equals(accumulator.finishReason()) ||
      !accumulator.tool().isEmpty() ||
      (accumulator.wireToolCalls() != null && !accumulator.wireToolCalls().isEmpty())
    );
  }

  /**
   * Fail-open content: the regular content plus any unparseable bare tool payload, so a parse
   * failure surfaces the raw text to the client instead of silently dropping it.
   */
  private static String contentWithToolFallback(SequenceAccumulator accumulator) {
    return accumulator.content() + accumulator.tool();
  }

  /**
   * Resolves tool calls for a fully-accumulated stream: structured wire calls from the final
   * COMPLETED event win (engine-side extraction already ran the configured template); otherwise
   * the client-side fallback extraction runs over the bare tool payload, then the full content.
   */
  private static List<ParsedToolCall> resolveToolCalls(
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    List<ParsedToolCall> wire = fromWireToolCalls(
      accumulator.wireToolCalls(),
      toolParameterSchemas
    );
    if (!wire.isEmpty()) {
      return wire;
    }
    return resolveToolCalls(accumulator.tool(), accumulator.content(), toolParameterSchemas);
  }

  public static List<ParsedToolCall> resolveToolCalls(
    String toolContent,
    String fullContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    List<ParsedToolCall> calls = parseBareToolCalls(toolContent, toolParameterSchemas);
    if (!calls.isEmpty()) {
      return calls;
    }
    return parseToolCalls(fullContent, toolParameterSchemas);
  }

  /**
   * Parses a BARE (marker-less) tool payload, as delivered on the TOOL channel by engines
   * that suppress tag markers:
   * <ul>
   *   <li>one or more concatenated JSON objects {@code {"name":...,"arguments":{...}}}
   *       (Qwen-style), or a JSON array of them;</li>
   *   <li>one or more Gemma-style {@code call:NAME{...}} bodies (the {@code <|tool_call>}
   *       wrapper never appears).</li>
   * </ul>
   * Returns an empty list when nothing parses (fail-open handled by the caller).
   */
  public static List<ParsedToolCall> parseBareToolCalls(
    String toolContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return parseToolCalls(toolContent, toolParameterSchemas);
  }

  /**
   * Same as {@link #parseToolCalls(String)}, but coerces string-valued arguments recovered from
   * untyped dialect text (XML / Gemma flavors — flagged by the extraction template) to their
   * declared JSON-schema types. {@code toolParameterSchemas} maps function name → the tool's
   * {@code parameters} JSON schema (see {@link #toolParameterSchemas(JsonNode)}); an empty map
   * disables coercion. JSON-flavor tool calls already carry native types and are left untouched.
   */
  public static List<ParsedToolCall> parseToolCalls(
    String content,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    var extracted = io.gravitee.singularitee.engine.tools.ToolCallExtractor.extract(
      content,
      toolsData(toolParameterSchemas),
      null
    );
    List<ParsedToolCall> result = new ArrayList<>(extracted.size());
    for (var call : extracted) {
      result.add(
        toParsedToolCall(
          call.name(),
          call.argumentsJson(),
          call.coercibleArgs(),
          toolParameterSchemas
        )
      );
    }
    return result;
  }

  /**
   * Builds a {@link ParsedToolCall} from extracted data, applying schema-driven coercion to the
   * argument names flagged coercible (string values only; unknown types and parse failures keep
   * the string — fail-open, identical to the legacy XML/Gemma behavior).
   */
  private static ParsedToolCall toParsedToolCall(
    String name,
    String argumentsJson,
    List<String> coercibleArgs,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    String arguments = argumentsJson;
    if (coercibleArgs != null && !coercibleArgs.isEmpty()) {
      try {
        JsonNode argsNode = OBJECT_MAPPER.get().readTree(argumentsJson);
        if (argsNode.isObject()) {
          JsonNode parametersSchema = toolParameterSchemas.get(name);
          ObjectNode coerced = OBJECT_MAPPER.get().createObjectNode();
          var fields = argsNode.fields();
          while (fields.hasNext()) {
            var field = fields.next();
            if (coercibleArgs.contains(field.getKey()) && field.getValue().isTextual()) {
              putCoercedArgument(
                coerced,
                field.getKey(),
                field.getValue().asText(),
                parametersSchema
              );
            } else {
              coerced.set(field.getKey(), field.getValue());
            }
          }
          arguments = coerced.toString();
        }
      } catch (Exception e) {
        log.debug("[singularitee] Failed to coerce tool-call arguments: {}", argumentsJson, e);
      }
    }
    return new ParsedToolCall(generateToolCallId(), name, arguments);
  }

  /** Converts structured wire tool calls into {@link ParsedToolCall}s, applying schema coercion. */
  public static List<ParsedToolCall> fromWireToolCalls(
    List<WireToolCall> wireToolCalls,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    if (wireToolCalls == null || wireToolCalls.isEmpty()) {
      return List.of();
    }
    List<ParsedToolCall> result = new ArrayList<>(wireToolCalls.size());
    for (WireToolCall call : wireToolCalls) {
      ParsedToolCall parsed = toParsedToolCall(
        call.name(),
        call.argumentsJson(),
        call.coercibleArgs(),
        toolParameterSchemas
      );
      // The engine-born id is authoritative: the stored conversation replays
      // the call under it, so re-minting one here would break the pairing.
      result.add(
        call.id() != null && !call.id().isBlank()
          ? new ParsedToolCall(call.id(), parsed.name(), parsed.arguments())
          : parsed
      );
    }
    return result;
  }

  /** The {@code tools} variable handed to extraction templates: name + parameters schema. */
  private static List<Map<String, Object>> toolsData(Map<String, JsonNode> toolParameterSchemas) {
    if (toolParameterSchemas == null || toolParameterSchemas.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> tools = new ArrayList<>(toolParameterSchemas.size());
    for (var entry : toolParameterSchemas.entrySet()) {
      tools.add(Map.of("name", entry.getKey()));
    }
    return tools;
  }

  /**
   * Builds the function-name → {@code parameters} JSON-schema map from a request's {@code tools}
   * array. Accepts both the Chat Completions shape ({@code {type, function: {name, parameters}}})
   * and the Responses shape ({@code {type, name, parameters}}). Returns an empty map when there are
   * no usable tools (→ no coercion).
   */
  public static Map<String, JsonNode> toolParameterSchemas(JsonNode tools) {
    if (tools == null || !tools.isArray() || tools.isEmpty()) {
      return Map.of();
    }
    Map<String, JsonNode> schemas = new java.util.HashMap<>();
    for (JsonNode tool : tools) {
      JsonNode fn = tool.has("function") && tool.get("function").isObject()
        ? tool.get("function")
        : tool;
      String name = fn.path("name").asText("");
      JsonNode parameters = fn.get("parameters");
      if (!name.isEmpty() && parameters != null && parameters.isObject()) {
        schemas.put(name, parameters);
      }
    }
    return Map.copyOf(schemas);
  }

  /**
   * Writes an XML-parsed (string) parameter value into {@code arguments}, coerced to the declared
   * schema type when one exists and the value parses cleanly; otherwise the string is kept as-is
   * (fail-open). String / unknown / missing types keep the exact legacy string behavior.
   */
  private static void putCoercedArgument(
    ObjectNode arguments,
    String key,
    String value,
    JsonNode parametersSchema
  ) {
    String type = parametersSchema == null
      ? ""
      : parametersSchema.path("properties").path(key).path("type").asText("");
    switch (type) {
      case "integer" -> {
        try {
          arguments.put(key, Long.parseLong(value));
          return;
        } catch (NumberFormatException ignored) {}
      }
      case "number" -> {
        try {
          arguments.put(key, Double.parseDouble(value));
          return;
        } catch (NumberFormatException ignored) {}
      }
      case "boolean" -> {
        if ("true".equals(value)) {
          arguments.put(key, true);
          return;
        }
        if ("false".equals(value)) {
          arguments.put(key, false);
          return;
        }
      }
      case "array", "object" -> {
        try {
          JsonNode parsed = OBJECT_MAPPER.get().readTree(value);
          if (
            ("array".equals(type) && parsed.isArray()) ||
            ("object".equals(type) && parsed.isObject())
          ) {
            arguments.set(key, parsed);
            return;
          }
        } catch (Exception ignored) {}
      }
      default -> {}
    }
    arguments.put(key, value);
  }

  private static String generateToolCallId() {
    return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Private chunk builders
  // ═══════════════════════════════════════════════════════════════════════

  private static ObjectNode chatChunk(
    String id,
    long created,
    String model,
    String content,
    String finishReason,
    boolean roleEmitted
  ) {
    return chatChunk(id, created, model, content, null, finishReason, roleEmitted);
  }

  private static ObjectNode chatChunk(
    String id,
    long created,
    String model,
    String content,
    String reasoning,
    String finishReason,
    boolean roleEmitted
  ) {
    return chatChunk(id, created, model, content, reasoning, finishReason, roleEmitted, null);
  }

  private static ObjectNode chatChunk(
    String id,
    long created,
    String model,
    String content,
    String reasoning,
    String finishReason,
    boolean roleEmitted,
    List<io.gravitee.singularitee.protocol.PositionLogprobs> logprobs
  ) {
    ObjectNode chunk = OBJECT_MAPPER.get().createObjectNode();
    chunk.put("id", id);
    chunk.put("object", "chat.completion.chunk");
    chunk.put("created", created);
    chunk.put("model", model);
    chunk.putNull("system_fingerprint");
    ArrayNode choices = chunk.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    ObjectNode delta = choice.putObject("delta");
    if (!roleEmitted) {
      delta.put("role", "assistant");
    }
    if (content != null && !content.isEmpty()) {
      delta.put("content", content);
    }
    if (reasoning != null && !reasoning.isEmpty()) {
      delta.put("reasoning_content", reasoning);
    }
    writeLogprobs(choice, logprobs);
    if (finishReason != null) {
      choice.put("finish_reason", finishReason);
    } else {
      choice.putNull("finish_reason");
    }
    return chunk;
  }

  /**
   * Writes an OpenAI {@code logprobs} object ({@code {"content": [...]}}) on the choice,
   * or {@code null} when no logprobs were collected.
   */
  private static void writeLogprobs(
    ObjectNode choice,
    List<io.gravitee.singularitee.protocol.PositionLogprobs> logprobs
  ) {
    if (logprobs == null || logprobs.isEmpty()) {
      choice.putNull("logprobs");
      return;
    }
    ObjectNode node = choice.putObject("logprobs");
    ArrayNode contentArr = node.putArray("content");
    for (var position : logprobs) {
      ObjectNode entry = contentArr.addObject();
      writeLogprobEntry(entry, position.getChosen());
      ArrayNode top = entry.putArray("top_logprobs");
      for (var candidate : position.getTopList()) {
        writeLogprobEntry(top.addObject(), candidate);
      }
    }
  }

  private static void writeLogprobEntry(
    ObjectNode node,
    io.gravitee.singularitee.protocol.TokenLogprob t
  ) {
    node.put("token", t.getToken());
    node.put("logprob", t.getLogprob());
    ArrayNode bytes = node.putArray("bytes");
    for (byte b : t.getRawBytes().toByteArray()) {
      bytes.add(b & 0xFF);
    }
  }

  private static ObjectNode completionChunk(
    String id,
    long created,
    String model,
    String content,
    String finishReason
  ) {
    ObjectNode chunk = OBJECT_MAPPER.get().createObjectNode();
    chunk.put("id", id);
    chunk.put("object", "text_completion");
    chunk.put("created", created);
    chunk.put("model", model);
    ArrayNode choices = chunk.putArray("choices");
    ObjectNode choice = choices.addObject();
    choice.put("index", 0);
    if (content != null) {
      choice.put("text", content);
    }
    choice.putNull("logprobs");
    if (finishReason != null) {
      choice.put("finish_reason", finishReason);
    } else {
      choice.putNull("finish_reason");
    }
    return chunk;
  }

  /**
   * Builds a usage-only SSE chunk.
   *
   * @param isChat {@code true} for chat completions ({@code "chat.completion.chunk"}),
   *               {@code false} for legacy completions ({@code "text_completion"}).
   */
  private static ObjectNode usageChunk(
    String id,
    long created,
    String model,
    boolean isChat,
    TokenMessage token
  ) {
    ObjectNode chunk = OBJECT_MAPPER.get().createObjectNode();
    chunk.put("id", id);
    chunk.put("object", isChat ? "chat.completion.chunk" : "text_completion");
    chunk.put("created", created);
    chunk.put("model", model);
    chunk.putArray("choices");
    ObjectNode usage = chunk.putObject("usage");
    usage.put("prompt_tokens", token.promptTokens());
    usage.put("completion_tokens", token.completionTokens());
    usage.put("total_tokens", token.promptTokens() + token.completionTokens());
    writeCompletionTokensDetails(usage, token.reasoningTokens(), token.toolTokens());
    writeTimings(chunk, token.performance());
    return chunk;
  }
}
