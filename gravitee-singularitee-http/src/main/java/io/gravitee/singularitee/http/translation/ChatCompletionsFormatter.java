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

import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.usageChunk;
import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.writeLogprobs;
import static io.gravitee.singularitee.http.translation.ResponseJsonWriters.writeUsage;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.contentWithToolFallback;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.fromWireToolCalls;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.isToolCandidate;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.narrationText;
import static io.gravitee.singularitee.http.translation.ToolCallResolver.resolveToolCalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.protocol.PositionLogprobs;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Formats a token stream into OpenAI Chat Completions shapes: live SSE streaming (with optional
 * tool-markup holdback), buffered-then-SSE streaming and the non-streaming JSON response.
 */
public final class ChatCompletionsFormatter {

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  private ChatCompletionsFormatter() {}

  public static Flowable<ServerEvent> chatStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    // Deferred: the per-stream state below must be created per subscription,
    // not per factory call, or a re-subscribe replays corrupted state.
    return Flowable.defer(() ->
      chatStreamEventsOnce(tokenStream, modelName, includeUsage, onFinal)
    );
  }

  private static Flowable<ServerEvent> chatStreamEventsOnce(
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
   * {@link ToolCallResolver#parseToolCalls(String)} and emitted as structured
   * {@code delta.tool_calls} chunks with finish reason {@code tool_calls}. Text preceding the
   * first opener has already streamed, which matches OpenAI semantics (content, then tool_calls).
   * If the opener was a false alarm (finish {@code stop}, or the markup does not parse), the
   * withheld text is flushed as ordinary content before the final chunk.
   */
  public static Flowable<ServerEvent> chatStreamEventsWithToolHoldback(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    // Deferred: per-subscription accumulators (see chatStreamEvents).
    return Flowable.defer(() ->
      chatStreamEventsWithToolHoldbackOnce(
        tokenStream,
        modelName,
        includeUsage,
        onFinal,
        toolParameterSchemas
      )
    );
  }

  private static Flowable<ServerEvent> chatStreamEventsWithToolHoldbackOnce(
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
            int openerIndex = ToolCallResolver.indexOfToolMarkupOpener(pending);
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
              int holdback = ToolCallResolver.toolMarkupHoldbackLength(pending);
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

  /**
   * Buffers all tokens, then emits Chat Completions SSE events. If the model produced tool calls,
   * emits structured {@code delta.tool_calls} instead of raw content, avoiding leaked markup.
   */
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
          onFinal.accept(accumulator.finalToken());
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
          TokenMessage usageToken = TokenMessage.builder()
            .isFinal(true)
            .finishReason(accumulator.finishReason())
            .promptTokens(accumulator.promptTokens())
            .completionTokens(accumulator.completionTokens())
            .build();
          events.add(
            new ServerEvent(usageChunk(responseId, created, modelName, true, usageToken).toString())
          );
        }
        events.add(new ServerEvent("[DONE]"));
        return Flowable.fromIterable(events);
      });
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
    List<PositionLogprobs> logprobs
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
}
