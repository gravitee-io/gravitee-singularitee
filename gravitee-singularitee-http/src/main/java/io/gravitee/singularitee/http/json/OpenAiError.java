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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the OpenAI-compatible error envelope:
 *
 * <pre>{@code
 * { "error": { "message": ..., "type": ..., "param": ..., "code": ... } }
 * }</pre>
 *
 * <p>The envelope is built with Jackson (not string formatting) so engine error messages
 * containing quotes or newlines are escaped correctly.
 */
public final class OpenAiError {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OpenAiError() {}

  public static String json(String message, String type, String param, String code) {
    ObjectNode root = MAPPER.createObjectNode();
    ObjectNode error = root.putObject("error");
    error.put("message", message == null ? "" : message);
    error.put("type", type == null ? "invalid_request_error" : type);
    if (param != null) {
      error.put("param", param);
    } else {
      error.putNull("param");
    }
    if (code != null) {
      error.put("code", code);
    } else {
      error.putNull("code");
    }
    return root.toString();
  }
}
