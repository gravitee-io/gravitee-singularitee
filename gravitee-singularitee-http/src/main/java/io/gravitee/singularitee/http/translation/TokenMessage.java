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

import io.gravitee.singularitee.protocol.PositionLogprobs;
import io.gravitee.singularitee.protocol.ResponseProgress;
import java.util.List;

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
  List<WireToolCall> toolCalls,
  List<PositionLogprobs> logprobs,
  ResponseProgress progress
) {
  public static Builder builder() {
    return new Builder();
  }

  /** A content-delta token. */
  public static TokenMessage contentDelta(String token) {
    return builder().token(token).build();
  }

  /** A reasoning-delta token ({@code STEP_ROLE_THINKING} on the wire). */
  public static TokenMessage reasoningDelta(String reasoning) {
    return builder().reasoning(reasoning).build();
  }

  /** A tool-payload delta token ({@code STEP_ROLE_TOOL} on the wire). */
  public static TokenMessage toolDelta(String tool) {
    return builder().tool(tool).build();
  }

  /**
   * An auxiliary progress update ({@code RESPONSE_EVENT_TYPE_PROGRESS} on the wire). Carries
   * no text: the Chat Completions surface drops it, the Responses API renders it as a
   * {@code gravitee.progress} object.
   */
  public static TokenMessage progressUpdate(ResponseProgress progress) {
    return builder().progress(progress).build();
  }

  /** Fluent builder replacing the former telescoping constructors. */
  public static final class Builder {

    private String token;
    private String reasoning;
    private String tool;
    private int index;
    private boolean isFinal;
    private String finishReason;
    private int promptTokens;
    private int completionTokens;
    private Integer reasoningTokens;
    private Integer toolTokens;
    private PerformanceMessage performance;
    private String guardMessage;
    private List<WireToolCall> toolCalls;
    private List<PositionLogprobs> logprobs;
    private ResponseProgress progress;

    private Builder() {}

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder reasoning(String reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    public Builder tool(String tool) {
      this.tool = tool;
      return this;
    }

    public Builder index(int index) {
      this.index = index;
      return this;
    }

    public Builder isFinal(boolean isFinal) {
      this.isFinal = isFinal;
      return this;
    }

    public Builder finishReason(String finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder promptTokens(int promptTokens) {
      this.promptTokens = promptTokens;
      return this;
    }

    public Builder completionTokens(int completionTokens) {
      this.completionTokens = completionTokens;
      return this;
    }

    public Builder reasoningTokens(Integer reasoningTokens) {
      this.reasoningTokens = reasoningTokens;
      return this;
    }

    public Builder toolTokens(Integer toolTokens) {
      this.toolTokens = toolTokens;
      return this;
    }

    public Builder performance(PerformanceMessage performance) {
      this.performance = performance;
      return this;
    }

    public Builder guardMessage(String guardMessage) {
      this.guardMessage = guardMessage;
      return this;
    }

    public Builder toolCalls(List<WireToolCall> toolCalls) {
      this.toolCalls = toolCalls;
      return this;
    }

    public Builder logprobs(List<PositionLogprobs> logprobs) {
      this.logprobs = logprobs;
      return this;
    }

    public Builder progress(ResponseProgress progress) {
      this.progress = progress;
      return this;
    }

    public TokenMessage build() {
      return new TokenMessage(
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
        logprobs,
        progress
      );
    }
  }
}
