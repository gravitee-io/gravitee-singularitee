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
package io.gravitee.singularitee.http.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared JSON helpers for the HTTP API: a configured ObjectMapper and usage-detail writing. */
public final class Utils {

  /**
   * Shared thread-local ObjectMapper. {@code USE_BIG_DECIMAL_FOR_FLOATS} keeps embedding
   * floats exact when echoed back as JSON.
   */
  public static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = ThreadLocal.withInitial(() -> {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    return mapper;
  });

  private Utils() {}

  /**
   * Parses a JSON request body, throwing {@link IllegalArgumentException} on empty or invalid input
   * (mapped to an OpenAI {@code invalid_request_error} by the failure handler).
   */
  public static JsonNode parsePayload(String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("Request body is required");
    }
    try {
      return OBJECT_MAPPER.get().readTree(body);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON payload", e);
    }
  }

  /**
   * Writes the {@code completion_tokens_details} sub-object into a {@code usage} node when
   * reasoning or tool tokens are present.
   */
  public static void writeCompletionTokensDetails(
    ObjectNode usage,
    Integer reasoningTokens,
    Integer toolTokens
  ) {
    int reasoning = reasoningTokens == null ? 0 : reasoningTokens;
    int tool = toolTokens == null ? 0 : toolTokens;
    // Always written, all buckets explicit. completion_tokens = answer + reasoning + tool;
    // reasoning_tokens follows the OpenAI field name, answer_tokens/tool_tokens are explicit
    // extensions so the breakdown always sums to completion_tokens.
    ObjectNode details = usage.putObject("completion_tokens_details");
    int completion = usage.path("completion_tokens").asInt(0);
    details.put("answer_tokens", Math.max(0, completion - reasoning - tool));
    details.put("reasoning_tokens", reasoning);
    details.put("tool_tokens", tool);
  }
}
