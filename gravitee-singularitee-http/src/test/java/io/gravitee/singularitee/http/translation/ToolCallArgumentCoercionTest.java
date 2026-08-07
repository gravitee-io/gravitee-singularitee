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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for schema-aware coercion of XML-parsed tool-call arguments in
 * {@link InferenceResponseFormatter#parseToolCalls(String, Map)}.
 */
class ToolCallArgumentCoercionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Map<String, JsonNode> schemas(String toolsJson) {
    try {
      return InferenceResponseFormatter.toolParameterSchemas(MAPPER.readTree(toolsJson));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static JsonNode argsOf(List<ParsedToolCall> calls) {
    assertThat(calls).hasSize(1);
    try {
      return MAPPER.readTree(calls.getFirst().arguments());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final Map<String, JsonNode> READ_SCHEMAS = schemas(
    """
    [{"type":"function","function":{"name":"read","parameters":{
      "type":"object","properties":{
        "filePath":{"type":"string"},
        "limit":{"type":"integer"},
        "recursive":{"type":"boolean"},
        "threshold":{"type":"number"},
        "tags":{"type":"array","items":{"type":"string"}}
      }}}}]
    """
  );

  @Test
  void integerParamIsCoercedToNumericJson() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      """
      <tool_call><function=read>
      <parameter=filePath>
      /tmp/a.txt
      </parameter>
      <parameter=limit>
      80
      </parameter>
      </function></tool_call>""",
      READ_SCHEMAS
    );
    JsonNode args = argsOf(calls);
    assertThat(args.get("limit").isIntegralNumber()).isTrue();
    assertThat(args.get("limit").asLong()).isEqualTo(80L);
    assertThat(args.get("filePath").asText()).isEqualTo("/tmp/a.txt");
    assertThat(calls.getFirst().arguments()).contains("\"limit\":80");
  }

  @Test
  void booleanAndNumberParamsAreCoerced() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=recursive>true</parameter>" +
          "<parameter=threshold>0.5</parameter></function>",
        READ_SCHEMAS
      )
    );
    assertThat(args.get("recursive").isBoolean()).isTrue();
    assertThat(args.get("recursive").asBoolean()).isTrue();
    assertThat(args.get("threshold").isDouble()).isTrue();
    assertThat(args.get("threshold").asDouble()).isEqualTo(0.5);
  }

  @Test
  void arrayParamIsParsedAsJson() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=tags>[\"a\",\"b\"]</parameter></function>",
        READ_SCHEMAS
      )
    );
    assertThat(args.get("tags").isArray()).isTrue();
    assertThat(args.get("tags").get(1).asText()).isEqualTo("b");
  }

  @Test
  void paramNotInSchemaStaysString() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=unknown>42</parameter></function>",
        READ_SCHEMAS
      )
    );
    assertThat(args.get("unknown").isTextual()).isTrue();
    assertThat(args.get("unknown").asText()).isEqualTo("42");
  }

  @Test
  void functionWithoutSchemaStaysString() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=other><parameter=limit>80</parameter></function>",
        READ_SCHEMAS
      )
    );
    assertThat(args.get("limit").isTextual()).isTrue();
    assertThat(args.get("limit").asText()).isEqualTo("80");
  }

  @Test
  void emptySchemaMapLeavesEverythingString() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=limit>80</parameter></function>",
        Map.of()
      )
    );
    assertThat(args.get("limit").isTextual()).isTrue();
    assertThat(args.get("limit").asText()).isEqualTo("80");
  }

  @Test
  void multiLineStringParamKeepsLegacyTrimBehavior() {
    String value = "line one\n  line two\nline three";
    JsonNode withSchema = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=filePath>\n" + value + "\n</parameter></function>",
        READ_SCHEMAS
      )
    );
    JsonNode withoutSchema = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=filePath>\n" + value + "\n</parameter></function>"
      )
    );
    assertThat(withSchema.get("filePath").asText()).isEqualTo(value);
    assertThat(withSchema.get("filePath")).isEqualTo(withoutSchema.get("filePath"));
  }

  @Test
  void unparseableIntegerStaysStringFailOpen() {
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=limit>80x</parameter></function>",
        READ_SCHEMAS
      )
    );
    assertThat(args.get("limit").isTextual()).isTrue();
    assertThat(args.get("limit").asText()).isEqualTo("80x");
  }

  @Test
  void jsonFlavorToolCallIsLeftUntouched() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<tool_call>{\"name\":\"read\",\"arguments\":{\"limit\":\"80\",\"n\":7}}</tool_call>",
      READ_SCHEMAS
    );
    JsonNode args = argsOf(calls);
    // Lenient string stays a string; native number stays a number.
    assertThat(args.get("limit").isTextual()).isTrue();
    assertThat(args.get("n").asInt()).isEqualTo(7);
  }

  @Test
  void responsesFlatToolShapeIsSupported() {
    Map<String, JsonNode> flat = schemas(
      """
      [{"type":"function","name":"read","parameters":{
        "type":"object","properties":{"limit":{"type":"integer"}}}}]
      """
    );
    JsonNode args = argsOf(
      InferenceResponseFormatter.parseToolCalls(
        "<function=read><parameter=limit>80</parameter></function>",
        flat
      )
    );
    assertThat(args.get("limit").asLong()).isEqualTo(80L);
  }
}
