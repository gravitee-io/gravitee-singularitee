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

import static java.util.Objects.nonNull;

import io.gravitee.singularitee.protocol.PositionLogprobs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Accumulates a token stream into the full content/reasoning + usage for a non-streaming response. */
public final class SequenceAccumulator {

  private final long created = Instant.now().getEpochSecond();
  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();
  private final StringBuilder tool = new StringBuilder();
  private String finishReason = "stop";
  private String guardMessage;
  private int promptTokens;
  private int completionTokens;
  private int reasoningTokens;
  private int toolTokens;
  private PerformanceMessage performance;
  private List<WireToolCall> wireToolCalls;
  private List<PositionLogprobs> logprobs;

  public void add(TokenMessage token) {
    if (nonNull(token.token()) && !token.token().isEmpty()) {
      content.append(token.token());
    }
    if (nonNull(token.logprobs()) && !token.logprobs().isEmpty()) {
      if (logprobs == null) {
        logprobs = new ArrayList<>();
      }
      logprobs.addAll(token.logprobs());
    }
    if (nonNull(token.reasoning()) && !token.reasoning().isEmpty()) {
      reasoning.append(token.reasoning());
    }
    if (nonNull(token.tool()) && !token.tool().isEmpty()) {
      tool.append(token.tool());
    }
    if (token.isFinal()) {
      finishReason = token.finishReason() == null ? "stop" : token.finishReason();
      guardMessage = token.guardMessage();
      if (token.toolCalls() != null && !token.toolCalls().isEmpty()) {
        wireToolCalls = token.toolCalls();
      }
    }
    promptTokens = token.promptTokens();
    completionTokens = token.completionTokens();
    if (token.reasoningTokens() != null) {
      reasoningTokens = token.reasoningTokens();
    }
    if (token.toolTokens() != null) {
      toolTokens = token.toolTokens();
    }
    if (token.performance() != null) {
      performance = token.performance();
    }
  }

  public String content() {
    return content.toString();
  }

  public String reasoning() {
    return reasoning.toString();
  }

  /** Bare tool-call payload accumulated from TOOL-channel deltas (empty for tagged-text engines). */
  public String tool() {
    return tool.toString();
  }

  /** Structured tool calls delivered on the final COMPLETED event; empty when absent. */
  public List<WireToolCall> wireToolCalls() {
    return wireToolCalls == null ? List.of() : wireToolCalls;
  }

  /** Per-token logprobs accumulated from content deltas; empty when not collected. */
  public List<PositionLogprobs> logprobs() {
    return logprobs == null ? List.of() : logprobs;
  }

  public String finishReason() {
    return finishReason;
  }

  public String guardMessage() {
    return guardMessage;
  }

  public int promptTokens() {
    return promptTokens;
  }

  public int completionTokens() {
    return completionTokens;
  }

  public PerformanceMessage performance() {
    return performance;
  }

  /** Reasoning-channel token count; {@code 0} when the engine reported none. */
  public Integer reasoningTokens() {
    return reasoningTokens;
  }

  /** Tool-channel token count; {@code 0} when the engine reported none. */
  public Integer toolTokens() {
    return toolTokens;
  }

  /** The synthetic final token handed to {@code onFinal} callbacks on buffered paths. */
  public TokenMessage finalToken() {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason(finishReason)
      .promptTokens(promptTokens)
      .completionTokens(completionTokens)
      .performance(performance)
      .guardMessage(guardMessage)
      .build();
  }

  public long created() {
    return created;
  }

  public String responseId(String prefix) {
    return prefix + created;
  }
}
