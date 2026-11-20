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
import com.fasterxml.jackson.databind.node.TextNode;
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

  /** Whether a JSON value carries an image, as an {@code image_url} or {@code input_image} part. */
  static boolean hasImagePart(JsonNode node) {
    if (node == null) return false;
    if (node.isObject()) {
      String type = node.path("type").asText("");
      if (type.equals("image_url") || type.equals("input_image")) return true;
      for (JsonNode child : node) if (hasImagePart(child)) return true;
    } else if (node.isArray()) {
      for (JsonNode child : node) if (hasImagePart(child)) return true;
    }
    return false;
  }

  /**
   * An array of content parts as one text, parts joined by a newline; any other node as is.
   * An {@code input_json} part contributes its {@code data} as JSON text.
   */
  static JsonNode flattenParts(JsonNode node) {
    if (!node.isArray() || node.isEmpty()) return node;
    for (JsonNode part : node) if (!part.isObject() || !part.has("type")) return node;
    return TextNode.valueOf(partsText(node));
  }

  private static String partsText(JsonNode parts) {
    var sb = new StringBuilder();
    for (JsonNode part : parts) {
      String text;
      if (part.has("text")) text = part.path("text").asText("");
      else if (part.has("data")) text = part.get("data").toString();
      else if (part.has("refusal")) text = part.path("refusal").asText("");
      else continue;
      if (!sb.isEmpty()) sb.append('\n');
      sb.append(text);
    }
    return sb.toString();
  }

  /**
   * OpenAI input items as protocol messages, or {@code null} when {@code node} is not an item
   * list: an array whose every element is an object carrying a {@code role} or a {@code type}.
   * Within an item list, an item of an unknown type or role throws {@link UnreadableItem} rather
   * than degrading the whole conversation to JSON. Content parts are joined as text, an {@code
   * input_json} part as JSON text. A tool call becomes an assistant message reading {@code
   * name(arguments)} and its output a tool message, since a decision prompt shows messages by
   * role and content only.
   */
  static ChatMessageList messages(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) return null;
    for (JsonNode m : node) if (!m.isObject() || !(m.has("role") || m.has("type"))) return null;
    var list = ChatMessageList.newBuilder();
    for (int i = 0; i < node.size(); i++) {
      JsonNode m = node.get(i);
      var message = ChatMessage.newBuilder();
      String type = m.path("type").asText("");
      switch (type) {
        case "function_call", "custom_tool_call" -> {
          JsonNode arguments = m.has("arguments") ? m.get("arguments") : m.path("input");
          message
            .setRole(Role.ROLE_ASSISTANT)
            .setContent(m.path("name").asText("") + "(" + text(arguments) + ")");
        }
        case "function_call_output", "custom_tool_call_output" -> {
          JsonNode output = m.path("output");
          message
            .setRole(Role.ROLE_TOOL)
            .setToolCallId(m.path("call_id").asText(""))
            .setContent(output.isArray() ? partsText(output) : text(output));
        }
        case "", "message" -> {
          String name = m.path("role").asText("");
          Role role = switch (name.toLowerCase(Locale.ROOT)) {
            case "system", "developer" -> Role.ROLE_SYSTEM;
            case "user" -> Role.ROLE_USER;
            case "assistant" -> Role.ROLE_ASSISTANT;
            case "tool" -> Role.ROLE_TOOL;
            default -> throw new UnreadableItem(
              "input[" + i + "]: unsupported role \"" + name + "\"; " + ROLES
            );
          };
          JsonNode content = m.path("content");
          message.setRole(role).setContent(content.isArray() ? partsText(content) : text(content));
        }
        default -> throw new UnreadableItem(
          "input[" + i + "]: unsupported item type \"" + type + "\"; " + ITEM_TYPES
        );
      }
      list.addMessages(message);
    }
    return list.build();
  }

  private static final String ROLES = "accepted roles: user, developer, system, assistant, tool";
  private static final String ITEM_TYPES =
    "accepted types: message, function_call, function_call_output, custom_tool_call, custom_tool_call_output";

  /** An item of an {@code input} item list this server cannot read. */
  static final class UnreadableItem extends RuntimeException {

    UnreadableItem(String message) {
      super(message, null, false, false);
    }
  }

  private static String text(JsonNode node) {
    if (node.isNull() || node.isMissingNode()) return "";
    return node.isValueNode() ? node.asText("") : node.toString();
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
