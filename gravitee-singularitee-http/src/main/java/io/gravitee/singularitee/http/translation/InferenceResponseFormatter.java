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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Facade over the per-dialect formatters: {@link ChatCompletionsFormatter} (Chat Completions),
 * {@link LegacyCompletionsFormatter} (legacy Completions), {@link ResponsesFormatter}
 * (Responses API) and {@link ToolCallResolver} (tool-call extraction). Streaming variants emit
 * SSE {@link ServerEvent}s; buffered variants aggregate JSON. {@code reasoning}/thinking deltas
 * render as {@code reasoning_content}.
 */
public final class InferenceResponseFormatter {

  private InferenceResponseFormatter() {}

  // ═══════════════════════════════════════════════════════════════════════
  // Chat Completions
  // ═══════════════════════════════════════════════════════════════════════

  public static Flowable<ServerEvent> chatStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    return ChatCompletionsFormatter.chatStreamEvents(tokenStream, modelName, includeUsage, onFinal);
  }

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
    return ChatCompletionsFormatter.chatStreamEventsWithToolHoldback(
      tokenStream,
      modelName,
      includeUsage,
      onFinal,
      toolParameterSchemas
    );
  }

  public static Flowable<ServerEvent> chatBufferedStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ChatCompletionsFormatter.chatBufferedStreamEvents(
      tokenStream,
      modelName,
      includeUsage,
      onFinal,
      toolParameterSchemas
    );
  }

  public static ObjectNode buildChatResponse(
    String modelName,
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ChatCompletionsFormatter.buildChatResponse(modelName, accumulator, toolParameterSchemas);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Legacy Completions
  // ═══════════════════════════════════════════════════════════════════════

  public static Flowable<ServerEvent> completionStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    boolean includeUsage,
    Consumer<TokenMessage> onFinal
  ) {
    return LegacyCompletionsFormatter.completionStreamEvents(
      tokenStream,
      modelName,
      includeUsage,
      onFinal
    );
  }

  public static ObjectNode buildCompletionResponse(
    String modelName,
    SequenceAccumulator accumulator
  ) {
    return LegacyCompletionsFormatter.buildCompletionResponse(modelName, accumulator);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Responses API
  // ═══════════════════════════════════════════════════════════════════════

  /** @param responseIdOverride stable response id (stored-conversation continuation); null = derive from epoch */
  public static Flowable<ServerEvent> responsesStreamEvents(
    Flowable<TokenMessage> tokenStream,
    String modelName,
    Consumer<TokenMessage> onFinal,
    String responseIdOverride
  ) {
    return ResponsesFormatter.responsesStreamEvents(
      tokenStream,
      modelName,
      onFinal,
      responseIdOverride
    );
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
    return ResponsesFormatter.responsesBufferedStreamEvents(
      tokenStream,
      modelName,
      onFinal,
      toolParameterSchemas,
      responseIdOverride
    );
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
    return ResponsesFormatter.buildResponsesResponse(
      modelName,
      accumulator,
      toolParameterSchemas,
      responseIdOverride
    );
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Tool call parsing
  // ═══════════════════════════════════════════════════════════════════════

  public static List<ParsedToolCall> parseToolCalls(String content) {
    return ToolCallResolver.parseToolCalls(content);
  }

  public static List<ParsedToolCall> parseToolCalls(
    String content,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ToolCallResolver.parseToolCalls(content, toolParameterSchemas);
  }

  public static List<ParsedToolCall> parseBareToolCalls(
    String toolContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ToolCallResolver.parseBareToolCalls(toolContent, toolParameterSchemas);
  }

  public static List<ParsedToolCall> resolveToolCalls(
    String toolContent,
    String fullContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ToolCallResolver.resolveToolCalls(toolContent, fullContent, toolParameterSchemas);
  }

  public static List<ParsedToolCall> fromWireToolCalls(
    List<WireToolCall> wireToolCalls,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return ToolCallResolver.fromWireToolCalls(wireToolCalls, toolParameterSchemas);
  }

  public static Map<String, JsonNode> toolParameterSchemas(JsonNode tools) {
    return ToolCallResolver.toolParameterSchemas(tools);
  }
}
