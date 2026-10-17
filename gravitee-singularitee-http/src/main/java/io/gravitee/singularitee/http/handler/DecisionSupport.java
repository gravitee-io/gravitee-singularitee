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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Value;
import io.gravitee.singularitee.engine.api.decision.DecisionMessages;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.service.DecisionTargetException;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;

/**
 * What the two decision routes share: JSON to protocol values, chat-message states, and the
 * error envelope of a decision failure.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class DecisionSupport {

  private static final String PIPELINE_PREFIX = "pipeline:";

  private DecisionSupport() {}

  /** A JSON node as a protobuf value, through the plain Java value templates receive. */
  static Value value(JsonNode node) {
    return DecisionMessages.toValue(Utils.OBJECT_MAPPER.get().convertValue(node, Object.class));
  }

  /** Whether a JSON value carries an image, as an {@code image_url} content part. */
  static boolean hasImagePart(JsonNode node) {
    if (node == null) return false;
    if (node.isObject()) {
      if ("image_url".equals(node.path("type").asText(null))) return true;
      for (JsonNode child : node) if (hasImagePart(child)) return true;
    } else if (node.isArray()) {
      for (JsonNode child : node) if (hasImagePart(child)) return true;
    }
    return false;
  }

  /**
   * Chat messages as protocol messages, or {@code null} when {@code node} is not an array of
   * {@code {role, content}} objects. Text parts of a content array are joined.
   */
  static ChatMessageList messages(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) return null;
    var list = ChatMessageList.newBuilder();
    for (JsonNode m : node) {
      if (!m.isObject() || !m.has("role")) return null;
      Role role = switch (m.path("role").asText("").toLowerCase(Locale.ROOT)) {
        case "system", "developer" -> Role.ROLE_SYSTEM;
        case "user" -> Role.ROLE_USER;
        case "assistant" -> Role.ROLE_ASSISTANT;
        case "tool" -> Role.ROLE_TOOL;
        default -> null;
      };
      if (role == null) return null;
      JsonNode content = m.path("content");
      String text;
      if (content.isArray()) {
        var sb = new StringBuilder();
        for (JsonNode part : content) {
          if (part.has("text")) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(part.path("text").asText(""));
          }
        }
        text = sb.toString();
      } else {
        text = content.isNull() || content.isMissingNode() ? "" : content.asText("");
      }
      list.addMessages(ChatMessage.newBuilder().setRole(role).setContent(text));
    }
    return list.build();
  }

  /**
   * Points {@code request} at the model or the pipeline {@code id} names. A hidden pipeline
   * answers like an unknown id, as on the other routes. Returns {@code false} after writing the
   * error envelope.
   */
  static boolean target(
    RoutingContext rc,
    String id,
    ModelRegistry models,
    PipelineRegistry pipelines,
    DecideRequest.Builder request
  ) {
    if (models.get(id).isPresent()) {
      request.setModelId(id);
      return true;
    }
    boolean prefixed = id.startsWith(PIPELINE_PREFIX);
    String pipelineId = prefixed ? id.substring(PIPELINE_PREFIX.length()) : id;
    var pipeline = pipelines.get(pipelineId);
    if (pipeline.isEmpty() && prefixed) {
      HandlerSupport.modelNotFound(rc, id);
      return false;
    }
    if (pipeline.isPresent()) {
      if (pipeline.get().pipeline().hidden()) {
        HandlerSupport.modelNotFound(rc, id);
        return false;
      }
      request.setPipelineId(pipelineId);
      return true;
    }
    request.setModelId(id);
    return true;
  }

  /** Writes the envelope of a failed decision call. */
  static void fail(RoutingContext rc, Throwable err) {
    switch (err) {
      case DecisionTargetException t when t.notFound() -> JsonResponses.writeError(
        rc,
        400,
        t.getMessage(),
        "invalid_request_error",
        "model",
        "model_not_found"
      );
      case DecisionTargetException t -> JsonResponses.writeError(
        rc,
        400,
        t.getMessage(),
        "invalid_request_error",
        "model",
        "unsupported_model"
      );
      case DecisionException d -> {
        switch (d.reason()) {
          case INVALID_REQUEST -> HandlerSupport.badRequest(rc, d.getMessage(), null);
          case UNSUPPORTED_INPUT -> JsonResponses.writeError(
            rc,
            400,
            d.getMessage(),
            "invalid_request_error",
            null,
            "unsupported_input"
          );
          case UNSUPPORTED_MODALITY -> unsupportedModality(rc, d.getMessage());
          case EVALUATION_FAILED -> JsonResponses.writeError(
            rc,
            500,
            d.getMessage(),
            "server_error",
            null,
            "server_error"
          );
        }
      }
      default -> HandlerSupport.mapServiceError(rc, err);
    }
  }

  /** Adds {@code blocked} when a guard halted the pipeline before it decided. */
  static void blocked(ObjectNode root, DecideResponse resp) {
    if (!resp.hasBlocked()) return;
    ObjectNode blocked = root.putObject("blocked");
    blocked.put("code", resp.getBlocked().getCode());
    blocked.put("message", resp.getBlocked().getMessage());
  }

  /** Writes the 400 {@code unsupported_modality} envelope. */
  static void unsupportedModality(RoutingContext rc, String message) {
    JsonResponses.writeError(
      rc,
      400,
      message,
      "invalid_request_error",
      null,
      "unsupported_modality"
    );
  }

  /** The message of an image a decision cannot read. */
  static final String NO_IMAGES = "image input is not supported for decisions on this server";
}
