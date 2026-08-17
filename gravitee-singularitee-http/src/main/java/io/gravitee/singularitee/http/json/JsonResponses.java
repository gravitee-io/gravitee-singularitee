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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers for writing JSON and OpenAI-shaped error responses to a vert.x-web context. */
public final class JsonResponses {

  public static final String APPLICATION_JSON = "application/json";

  private static final Logger log = LoggerFactory.getLogger(JsonResponses.class);

  /** Longest slice of a JSON body worth logging — enough to identify it without flooding the log. */
  private static final int LOGGED_BODY_MAX_LENGTH = 200;

  private JsonResponses() {}

  private static String truncateForLog(String json) {
    return json.length() <= LOGGED_BODY_MAX_LENGTH
      ? json
      : json.substring(0, LOGGED_BODY_MAX_LENGTH) + "… (" + json.length() + " chars)";
  }

  public static void writeJson(RoutingContext rc, int status, String json) {
    var response = rc.response();
    // An error surfacing AFTER the response is (partly) written must not throw
    // "Response has already been written" onto the event loop (observed live:
    // an expired previous_response_id failed the pipeline, the failure was
    // rendered into the response, and a second error write then blew up as an
    // unhandled exception). Once the head is out, status/headers are gone —
    // terminate the stream instead; once ended, there is nothing left to do.
    if (response.ended()) {
      log.debug(
        "Response already ended — dropping late write (status {}): {}",
        status,
        truncateForLog(json)
      );
      return;
    }
    if (response.headWritten()) {
      log.warn(
        "Response head already written — terminating stream instead of writing: {}",
        truncateForLog(json)
      );
      response.end();
      return;
    }
    response.setStatusCode(status).putHeader("content-type", APPLICATION_JSON).end(json);
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
