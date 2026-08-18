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
}
