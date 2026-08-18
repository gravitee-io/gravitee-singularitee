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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.translation.wire.ChatUsage;
import io.gravitee.singularitee.http.translation.wire.LogprobPosition;
import io.gravitee.singularitee.protocol.PositionLogprobs;
import java.util.List;

/** JSON fragments shared by the chat, completion and responses formatters: usage, logprobs. */
final class ResponseJsonWriters {

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  private ResponseJsonWriters() {}

  /** Writes {@code usage} (including {@code completion_tokens_details}) into the response. */
  static void writeUsage(ObjectNode response, SequenceAccumulator accumulator) {
    ChatUsage usage = new ChatUsage(
      accumulator.promptTokens(),
      accumulator.completionTokens(),
      accumulator.reasoningTokens(),
      accumulator.toolTokens()
    );
    response.set("usage", OBJECT_MAPPER.get().valueToTree(usage));
  }

  /**
   * Writes an OpenAI {@code logprobs} object ({@code {"content": [...]}}) on the choice,
   * or {@code null} when no logprobs were collected.
   */
  static void writeLogprobs(ObjectNode choice, List<PositionLogprobs> logprobs) {
    if (logprobs == null || logprobs.isEmpty()) {
      choice.putNull("logprobs");
      return;
    }
    ObjectNode node = choice.putObject("logprobs");
    ArrayNode contentArr = node.putArray("content");
    for (var position : logprobs) {
      contentArr.add(OBJECT_MAPPER.get().valueToTree(new LogprobPosition(position)));
    }
  }

  /**
   * Builds a usage-only SSE chunk.
   *
   * @param isChat {@code true} for chat completions ({@code "chat.completion.chunk"}),
   *               {@code false} for legacy completions ({@code "text_completion"}).
   */
  static ObjectNode usageChunk(
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
    ChatUsage usage = new ChatUsage(
      token.promptTokens(),
      token.completionTokens(),
      token.reasoningTokens(),
      token.toolTokens()
    );
    chunk.set("usage", OBJECT_MAPPER.get().valueToTree(usage));
    return chunk;
  }
}
