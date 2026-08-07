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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolParameterDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolDefinitionConverterTest {

  // ── Null / empty inputs ───────────────────────────────────────────────────

  @Nested
  class NullAndEmpty {

    @Test
    void returns_null_for_null_input() {
      assertThat(ToolDefinitionConverter.toOpenAiMaps(null)).isNull();
    }

    @Test
    void returns_null_for_empty_list() {
      assertThat(ToolDefinitionConverter.toOpenAiMaps(List.of())).isNull();
    }
  }

  // ── Single tool, no parameters ────────────────────────────────────────────

  @Nested
  class SingleToolNoParameters {

    @Test
    void produces_one_entry() {
      var tool = ToolDefinition.newBuilder()
        .setName("get_time")
        .setDescription("Returns current time")
        .build();
      var result = ToolDefinitionConverter.toOpenAiMaps(List.of(tool));
      assertThat(result).hasSize(1);
    }

    @Test
    void type_is_function() {
      var tool = ToolDefinition.newBuilder().setName("get_time").build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      assertThat(entry.get("type")).isEqualTo("function");
    }

    @Test
    void function_map_contains_name() {
      var tool = ToolDefinition.newBuilder().setName("get_time").build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      assertThat(function.get("name")).isEqualTo("get_time");
    }

    @Test
    void function_map_contains_description_when_present() {
      var tool = ToolDefinition.newBuilder()
        .setName("get_time")
        .setDescription("Returns current time")
        .build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      assertThat(function.get("description")).isEqualTo("Returns current time");
    }

    @Test
    void function_map_omits_description_when_empty() {
      var tool = ToolDefinition.newBuilder().setName("get_time").build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      assertThat(function).doesNotContainKey("description");
    }

    @Test
    void function_map_omits_parameters_when_none() {
      var tool = ToolDefinition.newBuilder().setName("get_time").build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      assertThat(function).doesNotContainKey("parameters");
    }
  }

  // ── Parameters ────────────────────────────────────────────────────────────

  @Nested
  class Parameters {

    @Test
    void parameters_schema_has_object_type() {
      var param = ToolParameterDef.newBuilder().setName("city").setType("string").build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      assertThat(parameters.get("type")).isEqualTo("object");
    }

    @Test
    void parameter_properties_contain_name_and_type() {
      var param = ToolParameterDef.newBuilder().setName("city").setType("string").build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      @SuppressWarnings("unchecked")
      var properties = (Map<String, Object>) parameters.get("properties");
      assertThat(properties).containsKey("city");
      @SuppressWarnings("unchecked")
      var cityProp = (Map<String, Object>) properties.get("city");
      assertThat(cityProp.get("type")).isEqualTo("string");
    }

    @Test
    void parameter_description_included_when_present() {
      var param = ToolParameterDef.newBuilder()
        .setName("city")
        .setType("string")
        .setDescription("The city name")
        .build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      @SuppressWarnings("unchecked")
      var properties = (Map<String, Object>) parameters.get("properties");
      @SuppressWarnings("unchecked")
      var cityProp = (Map<String, Object>) properties.get("city");
      assertThat(cityProp.get("description")).isEqualTo("The city name");
    }

    @Test
    void parameter_description_omitted_when_empty() {
      var param = ToolParameterDef.newBuilder().setName("city").setType("string").build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      @SuppressWarnings("unchecked")
      var properties = (Map<String, Object>) parameters.get("properties");
      @SuppressWarnings("unchecked")
      var cityProp = (Map<String, Object>) properties.get("city");
      assertThat(cityProp).doesNotContainKey("description");
    }

    @Test
    void required_parameter_appears_in_required_list() {
      var param = ToolParameterDef.newBuilder()
        .setName("city")
        .setType("string")
        .setRequired(true)
        .build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      assertThat(parameters).containsKey("required");
      @SuppressWarnings("unchecked")
      var required = (List<String>) parameters.get("required");
      assertThat(required).containsExactly("city");
    }

    @Test
    void optional_parameter_does_not_appear_in_required_list() {
      var param = ToolParameterDef.newBuilder()
        .setName("units")
        .setType("string")
        .setRequired(false)
        .build();
      var tool = ToolDefinition.newBuilder().setName("get_weather").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      assertThat(parameters).doesNotContainKey("required");
    }

    @Test
    void parameter_type_defaults_to_string_when_empty() {
      var param = ToolParameterDef.newBuilder().setName("value").build(); // no type set
      var tool = ToolDefinition.newBuilder().setName("set_value").addParameters(param).build();
      var entry = ToolDefinitionConverter.toOpenAiMaps(List.of(tool)).get(0);
      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      @SuppressWarnings("unchecked")
      var properties = (Map<String, Object>) parameters.get("properties");
      @SuppressWarnings("unchecked")
      var prop = (Map<String, Object>) properties.get("value");
      assertThat(prop.get("type")).isEqualTo("string");
    }
  }

  // ── Multiple tools ────────────────────────────────────────────────────────

  @Nested
  class MultipleTools {

    @Test
    void all_tools_are_converted() {
      var t1 = ToolDefinition.newBuilder().setName("get_weather").build();
      var t2 = ToolDefinition.newBuilder().setName("search_web").build();
      var t3 = ToolDefinition.newBuilder().setName("send_email").build();
      var result = ToolDefinitionConverter.toOpenAiMaps(List.of(t1, t2, t3));
      assertThat(result).hasSize(3);
    }

    @Test
    void tool_order_is_preserved() {
      var t1 = ToolDefinition.newBuilder().setName("first").build();
      var t2 = ToolDefinition.newBuilder().setName("second").build();
      var result = ToolDefinitionConverter.toOpenAiMaps(List.of(t1, t2));
      @SuppressWarnings("unchecked")
      String name0 = (String) ((Map<String, Object>) result.get(0).get("function")).get("name");
      @SuppressWarnings("unchecked")
      String name1 = (String) ((Map<String, Object>) result.get(1).get("function")).get("name");
      assertThat(name0).isEqualTo("first");
      assertThat(name1).isEqualTo("second");
    }
  }

  // ── End-to-end OpenAI shape ───────────────────────────────────────────────

  @Nested
  class OpenAiShape {

    @Test
    void full_tool_matches_openai_function_calling_schema() {
      var requiredParam = ToolParameterDef.newBuilder()
        .setName("location")
        .setType("string")
        .setDescription("City and country, e.g. 'London, UK'")
        .setRequired(true)
        .build();
      var optionalParam = ToolParameterDef.newBuilder()
        .setName("unit")
        .setType("string")
        .setDescription("Temperature unit: celsius or fahrenheit")
        .setRequired(false)
        .build();
      var tool = ToolDefinition.newBuilder()
        .setName("get_weather")
        .setDescription("Get current weather for a location")
        .addParameters(requiredParam)
        .addParameters(optionalParam)
        .build();

      var result = ToolDefinitionConverter.toOpenAiMaps(List.of(tool));
      assertThat(result).hasSize(1);

      var entry = result.get(0);
      assertThat(entry.get("type")).isEqualTo("function");

      @SuppressWarnings("unchecked")
      var function = (Map<String, Object>) entry.get("function");
      assertThat(function.get("name")).isEqualTo("get_weather");
      assertThat(function.get("description")).isEqualTo("Get current weather for a location");

      @SuppressWarnings("unchecked")
      var parameters = (Map<String, Object>) function.get("parameters");
      assertThat(parameters.get("type")).isEqualTo("object");

      @SuppressWarnings("unchecked")
      var properties = (Map<String, Object>) parameters.get("properties");
      assertThat(properties).containsKeys("location", "unit");

      @SuppressWarnings("unchecked")
      var required = (List<String>) parameters.get("required");
      assertThat(required).containsExactly("location");
      assertThat(required).doesNotContain("unit");
    }
  }
}
