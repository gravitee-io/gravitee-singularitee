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
package io.gravitee.singularitee.http.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.validation.PayloadValidator;
import io.gravitee.singularitee.http.validation.SchemaName;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;

/** Shared parsing / validation / error-mapping helpers for the HTTP API handlers. */
public final class HandlerSupport {

  private HandlerSupport() {}

  /** Parses the request body; on failure writes a 400 envelope and returns {@code null}. */
  public static JsonNode parseOrFail(RoutingContext rc) {
    try {
      String body = rc.body() == null ? null : rc.body().asString();
      return Utils.parsePayload(body);
    } catch (IllegalArgumentException e) {
      JsonResponses.writeError(
        rc,
        400,
        e.getMessage(),
        "invalid_request_error",
        null,
        "invalid_request_error"
      );
      return null;
    }
  }

  /** Extracts the required {@code model} field; on absence writes a 400 envelope and returns {@code null}. */
  public static String requireModel(RoutingContext rc, JsonNode payload) {
    String model = payload.at("/model").asText(null);
    if (model == null || model.isBlank()) {
      JsonResponses.writeError(
        rc,
        400,
        "Missing required parameter: 'model'",
        "invalid_request_error",
        "model",
        "invalid_request_error"
      );
      return null;
    }
    return model;
  }

  public static void modelNotFound(RoutingContext rc, String model) {
    JsonResponses.writeError(
      rc,
      400,
      "The model `" + model + "` does not exist",
      "invalid_request_error",
      "model",
      "model_not_found"
    );
  }

  public static void badRequest(RoutingContext rc, String message, String param) {
    JsonResponses.writeError(
      rc,
      400,
      message,
      "invalid_request_error",
      param,
      "invalid_request_error"
    );
  }

  /** Reads a JSON value that may be a string or an array of strings into a list. */
  public static List<String> stringOrArray(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node.isTextual()) {
      out.add(node.asText());
    } else if (node.isArray()) {
      for (JsonNode n : node) {
        if (n.isTextual()) {
          out.add(n.asText());
        }
      }
    }
    return out;
  }

  /**
   * Validates the payload against the endpoint's JSON schema. On failure writes a 400 envelope and
   * returns {@code false}.
   */
  public static boolean validate(RoutingContext rc, JsonNode payload, SchemaName schema) {
    List<String> errors = PayloadValidator.INSTANCE.validate(schema, payload);
    if (errors.isEmpty()) {
      return true;
    }
    JsonResponses.writeError(
      rc,
      400,
      "Invalid request payload: " + String.join("; ", errors),
      "invalid_request_error",
      null,
      "invalid_request_error"
    );
    return false;
  }

  /** Maps a service {@code Future} failure to the appropriate OpenAI error envelope + status. */
  public static void mapServiceError(RoutingContext rc, Throwable err) {
    String msg = err.getMessage() == null ? "Internal error" : err.getMessage();
    if (msg.startsWith("Model not found")) {
      JsonResponses.writeError(rc, 400, msg, "invalid_request_error", "model", "model_not_found");
    } else if (msg.contains("is not a") || msg.contains("is neither")) {
      JsonResponses.writeError(rc, 400, msg, "invalid_request_error", "model", "unsupported_model");
    } else {
      JsonResponses.writeError(rc, 500, msg, "internal_error", null, "internal_error");
    }
  }
}
