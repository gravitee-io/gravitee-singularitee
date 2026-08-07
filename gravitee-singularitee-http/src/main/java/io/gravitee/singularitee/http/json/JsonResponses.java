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

import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.ext.web.RoutingContext;

/** Helpers for writing JSON and OpenAI-shaped error responses to a vert.x-web context. */
public final class JsonResponses {

  public static final String APPLICATION_JSON = "application/json";

  private JsonResponses() {}

  public static void writeJson(RoutingContext rc, int status, String json) {
    rc.response().setStatusCode(status).putHeader("content-type", APPLICATION_JSON).end(json);
  }

  public static void writeJson(RoutingContext rc, JsonNode node) {
    writeJson(rc, 200, node.toString());
  }

  public static void writeError(
    RoutingContext rc,
    int status,
    String message,
    String type,
    String param,
    String code
  ) {
    writeJson(rc, status, OpenAiError.json(message, type, param, code));
  }
}
