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
package io.gravitee.singularitee.http.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.gravitee.singularitee.http.json.Utils;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates inbound request payloads against per-endpoint JSON schemas (Draft 2020-12) loaded once
 * from {@code /llm-schemas.json}. Schemas are intentionally lenient ({@code additionalProperties}
 * allowed) so SDK-added fields don't break requests; they enforce required fields, types and enums.
 */
public final class PayloadValidator {

  public static final PayloadValidator INSTANCE = new PayloadValidator();

  private final Map<SchemaName, JsonSchema> schemas = new EnumMap<>(SchemaName.class);

  private PayloadValidator() {
    try (InputStream in = PayloadValidator.class.getResourceAsStream("/llm-schemas.json")) {
      if (in == null) {
        throw new IllegalStateException("/llm-schemas.json not found on the classpath");
      }
      JsonNode root = Utils.OBJECT_MAPPER.get().readTree(in);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      for (SchemaName name : SchemaName.values()) {
        JsonNode node = root.at(name.pointer());
        if (node.isMissingNode()) {
          throw new IllegalStateException("Missing schema definition: " + name.pointer());
        }
        schemas.put(name, factory.getSchema(node));
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load /llm-schemas.json", e);
    }
  }

  /** Returns the (sorted, de-duplicated) validation error messages, or an empty list if valid. */
  public List<String> validate(SchemaName name, JsonNode payload) {
    JsonSchema schema = schemas.get(name);
    if (schema == null) {
      return List.of();
    }
    Set<ValidationMessage> messages = schema.validate(payload);
    if (messages.isEmpty()) {
      return List.of();
    }
    return messages.stream().map(ValidationMessage::getMessage).distinct().sorted().toList();
  }
}
