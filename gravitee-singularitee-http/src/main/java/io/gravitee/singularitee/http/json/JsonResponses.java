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

  /** Longest slice of a JSON body worth logging: enough to identify it without flooding the log. */
  private static final int LOGGED_BODY_MAX_LENGTH = 200;

  private JsonResponses() {}

  private static String truncateForLog(String json) {
    return json.length() <= LOGGED_BODY_MAX_LENGTH
      ? json
      : json.substring(0, LOGGED_BODY_MAX_LENGTH) + "… (" + json.length() + " chars)";
  }

  /**
   * Writes {@code json} with the given status and {@code application/json} content type.
   *
   * <p>Safe to call after the response has been (partly) written: a late write on an ended
   * response is dropped, and one on a response whose head is already out terminates the stream
   * instead, since status and headers can no longer change. Neither case throws.
   */
  public static void writeJson(RoutingContext rc, int status, String json) {
    var response = rc.response();
    if (response.ended()) {
      log.debug(
        "Response already ended, dropping late write (status {}): {}",
        status,
        truncateForLog(json)
      );
      return;
    }
    if (response.headWritten()) {
      log.warn(
        "Response head already written, terminating stream instead of writing: {}",
        truncateForLog(json)
      );
      response.end();
      return;
    }
    response.setStatusCode(status).putHeader("content-type", APPLICATION_JSON).end(json);
  }

  /** Writes {@code node} as a 200 JSON response. */
  public static void writeJson(RoutingContext rc, JsonNode node) {
    writeJson(rc, 200, node.toString());
  }

  /** Writes an {@link OpenAiError} envelope with the given status. */
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
