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

/**
 * A normalized token event flowing from the engine adapter to the response formatter.
 *
 * <p>{@code token} carries a content delta; {@code reasoning} carries a thinking/reasoning
 * delta (rendered as OpenAI {@code reasoning_content}); {@code tool} carries a tool-call
 * payload delta ({@code STEP_ROLE_TOOL} on the wire — the BARE payload, tag markers
 * suppressed engine-side; buffered and parsed into structured {@code tool_calls}).
 * At most one of them is set on a non-final token; all are {@code null} on the final token,
 * which instead carries usage, finish reason and performance.
 */
public record TokenMessage(
  String token,
  String reasoning,
  String tool,
  int index,
  boolean isFinal,
  String finishReason,
  int promptTokens,
  int completionTokens,
  Integer reasoningTokens,
  Integer toolTokens,
  PerformanceMessage performance,
  String guardMessage,
  java.util.List<WireToolCall> toolCalls,
  java.util.List<io.gravitee.singularitee.protocol.PositionLogprobs> logprobs
) {
  /** Backwards-compatible constructor without per-token logprobs ({@code logprobs = null}). */
  public TokenMessage(
    String token,
    String reasoning,
    String tool,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    Integer reasoningTokens,
    Integer toolTokens,
    PerformanceMessage performance,
    String guardMessage,
    java.util.List<WireToolCall> toolCalls
  ) {
    this(
      token,
      reasoning,
      tool,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      guardMessage,
      toolCalls,
      null
    );
  }

  /**
   * Backwards-compatible constructor without structured wire tool calls
   * ({@code toolCalls = null}).
   */
  public TokenMessage(
    String token,
    String reasoning,
    String tool,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    Integer reasoningTokens,
    Integer toolTokens,
    PerformanceMessage performance,
    String guardMessage
  ) {
    this(
      token,
      reasoning,
      tool,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      guardMessage,
      null,
      null
    );
  }

  /**
   * Backwards-compatible constructor for content + reasoning tokens without a tool delta.
   * Delegates to the canonical constructor with {@code tool = null}.
   */
  public TokenMessage(
    String token,
    String reasoning,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    Integer reasoningTokens,
    Integer toolTokens,
    PerformanceMessage performance,
    String guardMessage
  ) {
    this(
      token,
      reasoning,
      null,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      guardMessage
    );
  }

  /**
   * Backwards-compatible constructor for content / usage / final tokens that carry no separate
   * reasoning or tool delta.
   */
  public TokenMessage(
    String token,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    Integer reasoningTokens,
    Integer toolTokens,
    PerformanceMessage performance,
    String guardMessage
  ) {
    this(
      token,
      null,
      null,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      guardMessage
    );
  }

  /** A tool-payload delta token ({@code STEP_ROLE_TOOL} on the wire). */
  public static TokenMessage toolDelta(String tool) {
    return new TokenMessage(null, null, tool, 0, false, null, 0, 0, null, null, null, null);
  }
}
