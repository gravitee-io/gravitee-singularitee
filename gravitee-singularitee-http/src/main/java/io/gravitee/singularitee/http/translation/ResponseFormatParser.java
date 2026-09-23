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

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.singularitee.protocol.StructuredOutputFormat;
import java.util.Optional;

/**
 * Translates the OpenAI structured-output request field into a {@link StructuredOutputFormat}.
 *
 * <p>Chat Completions carries it as {@code response_format}, with the schema nested under
 * {@code json_schema}; Responses carries it as {@code text.format}, with {@code name},
 * {@code schema} and {@code strict} on the format object itself. Both accept the types
 * {@code text}, {@code json_object} and {@code json_schema}. {@code strict} needs no handling:
 * constrained decoding enforces the schema either way.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ResponseFormatParser {

  /** The payload field the format was read from, for error envelopes. */
  public static final String CHAT_PARAM = "response_format";
  public static final String RESPONSES_PARAM = "text.format";

  private ResponseFormatParser() {}

  /**
   * @return the constraint, or empty when the payload asks for plain text or names no format
   * @throws InvalidResponseFormatException when the field is malformed
   */
  public static Optional<StructuredOutputFormat> parse(JsonNode payload, EndpointType endpoint) {
    return switch (endpoint) {
      case CHAT -> {
        JsonNode format = payload.at("/response_format");
        yield parse(format, format.path("json_schema"), CHAT_PARAM, CHAT_PARAM + ".json_schema");
      }
      case RESPONSES -> {
        JsonNode format = payload.at("/text/format");
        yield parse(format, format, RESPONSES_PARAM, RESPONSES_PARAM);
      }
      default -> Optional.empty();
    };
  }

  private static Optional<StructuredOutputFormat> parse(
    JsonNode format,
    JsonNode schemaHolder,
    String param,
    String schemaParam
  ) {
    if (format.isMissingNode() || format.isNull()) {
      return Optional.empty();
    }
    if (!format.isObject() || !format.path("type").isTextual()) {
      throw new InvalidResponseFormatException(param, "`" + param + ".type` is required");
    }
    String type = format.path("type").asText();
    return switch (type) {
      case "text" -> Optional.empty();
      case "json_object" -> Optional.of(
        StructuredOutputFormat.newBuilder().setJsonObject(true).build()
      );
      case "json_schema" -> Optional.of(jsonSchema(schemaHolder, schemaParam));
      default -> throw new InvalidResponseFormatException(
        param,
        "`" + param + ".type` must be one of text, json_object, json_schema; got `" + type + "`"
      );
    };
  }

  private static StructuredOutputFormat jsonSchema(JsonNode holder, String param) {
    JsonNode name = holder.path("name");
    if (!name.isTextual() || name.asText().isBlank()) {
      throw new InvalidResponseFormatException(param, "`" + param + ".name` is required");
    }
    JsonNode schema = holder.path("schema");
    if (!schema.isObject()) {
      throw new InvalidResponseFormatException(
        param,
        "`" + param + ".schema` is required and must be a JSON Schema object"
      );
    }
    return StructuredOutputFormat.newBuilder()
      .setJsonSchema(schema.toString())
      .setName(name.asText())
      .build();
  }

  /** A malformed structured-output field; {@link #param()} names it for the error envelope. */
  public static final class InvalidResponseFormatException extends IllegalArgumentException {

    private final String param;

    public InvalidResponseFormatException(String param, String message) {
      super(message);
      this.param = param;
    }

    public String param() {
      return param;
    }
  }
}
