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
package io.gravitee.singularitee.engine;

import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolParameterDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts proto {@link ToolDefinition} objects into the OpenAI function-calling
 * format expected by Jinja2 chat templates.
 *
 * <p>The output format matches the OpenAI tool schema:
 * <pre>{@code
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "...",
 *     "description": "...",
 *     "parameters": { "type": "object", "properties": {...}, "required": [...] }
 *   }
 * }
 * }</pre>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ToolDefinitionConverter {

  private ToolDefinitionConverter() {}

  /**
   * Converts a list of {@link ToolDefinition} protos to OpenAI-format tool maps.
   *
   * @param tools the proto tool definitions
   * @return the OpenAI-format maps, or {@code null} if input is null/empty
   */
  public static List<Map<String, Object>> toOpenAiMaps(List<ToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) return null;
    return tools.stream().map(ToolDefinitionConverter::toOpenAiToolMap).toList();
  }

  private static Map<String, Object> toOpenAiToolMap(ToolDefinition tool) {
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", tool.getName());
    if (!tool.getDescription().isEmpty()) {
      function.put("description", tool.getDescription());
    }
    if (!tool.getParametersList().isEmpty()) {
      function.put("parameters", toJsonSchemaParameters(tool.getParametersList()));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "function");
    result.put("function", function);
    return result;
  }

  private static Map<String, Object> toJsonSchemaParameters(List<ToolParameterDef> params) {
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new java.util.ArrayList<>();

    for (ToolParameterDef p : params) {
      Map<String, Object> prop = new LinkedHashMap<>();
      prop.put("type", p.getType().isEmpty() ? "string" : p.getType());
      if (!p.getDescription().isEmpty()) {
        prop.put("description", p.getDescription());
      }
      properties.put(p.getName(), prop);
      if (p.getRequired()) {
        required.add(p.getName());
      }
    }

    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }
}
