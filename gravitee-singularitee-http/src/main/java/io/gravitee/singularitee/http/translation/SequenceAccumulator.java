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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import java.time.Instant;

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

  public void add(TokenMessage token) {
    if (nonNull(token.token()) && !token.token().isEmpty()) {
      content.append(token.token());
    }
    if (nonNull(token.logprobs()) && !token.logprobs().isEmpty()) {
      if (logprobs == null) {
        logprobs = new java.util.ArrayList<>();
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

  private java.util.List<WireToolCall> wireToolCalls;

  /** Structured tool calls delivered on the final COMPLETED event; {@code null} when absent. */
  public java.util.List<WireToolCall> wireToolCalls() {
    return wireToolCalls;
  }

  private java.util.List<io.gravitee.singularitee.protocol.PositionLogprobs> logprobs;

  /** Per-token logprobs accumulated from content deltas; {@code null} when not collected. */
  public java.util.List<io.gravitee.singularitee.protocol.PositionLogprobs> logprobs() {
    return logprobs;
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

  /** Writes {@code completion_tokens_details} into the usage node when present. */
  public void writeUsageDetails(ObjectNode usage) {
    if (usage == null) {
      return;
    }
    Utils.writeCompletionTokensDetails(usage, reasoningTokens, toolTokens);
  }

  public long created() {
    return created;
  }

  public String responseId(String prefix) {
    return prefix + created;
  }
}
