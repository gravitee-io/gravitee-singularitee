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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OpenAI shaping from STRUCTURED tool calls delivered on the wire ({@code
 * ResponseCompleted.tool_calls}, extracted engine-side by the Jinja extraction templates). The
 * HTTP layer only applies schema-driven coercion of flagged string arguments and formats.
 */
class WireToolCallShapingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Map<String, JsonNode> weatherSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    ObjectNode props = schema.putObject("properties");
    props.putObject("city").put("type", "string");
    props.putObject("days").put("type", "integer");
    return Map.of("get_weather", schema);
  }

  private static TokenMessage finalWithWireCalls(List<WireToolCall> calls) {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason("tool_calls")
      .promptTokens(3)
      .completionTokens(7)
      .toolCalls(calls)
      .build();
  }

  @Test
  void wireCallsShapeIntoChatToolCallsWithCoercion() {
    var acc = new SequenceAccumulator();
    acc.add(
      finalWithWireCalls(
        List.of(
          new WireToolCall(
            null,
            "get_weather",
            "{\"city\":\"Paris\",\"days\":\"3\"}",
            List.of("days")
          )
        )
      )
    );
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, weatherSchema());
    JsonNode message = response.at("/choices/0/message");
    assertThat(message.at("/tool_calls/0/function/name").asText()).isEqualTo("get_weather");
    assertThat(message.at("/tool_calls/0/function/arguments").asText()).isEqualTo(
      "{\"city\":\"Paris\",\"days\":3}"
    );
    assertThat(response.at("/choices/0/finish_reason").asText()).isEqualTo("tool_calls");
  }

  @Test
  void wireCallsWinOverRawToolText() {
    var acc = new SequenceAccumulator();
    acc.add(TokenMessage.toolDelta("garbage that would not parse"));
    acc.add(finalWithWireCalls(List.of(new WireToolCall(null, "f", "{}", List.of()))));
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, Map.of());
    assertThat(response.at("/choices/0/message/tool_calls/0/function/name").asText()).isEqualTo(
      "f"
    );
  }

  @Test
  void unflaggedStringArgumentsAreNotCoerced() {
    var acc = new SequenceAccumulator();
    acc.add(
      finalWithWireCalls(
        List.of(new WireToolCall(null, "get_weather", "{\"days\":\"3\"}", List.of()))
      )
    );
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, weatherSchema());
    assertThat(
      response.at("/choices/0/message/tool_calls/0/function/arguments").asText()
    ).isEqualTo("{\"days\":\"3\"}");
  }

  @Test
  void wireCallsShapeIntoResponsesFunctionCallItems() {
    var acc = new SequenceAccumulator();
    acc.add(finalWithWireCalls(List.of(new WireToolCall(null, "f", "{\"x\":1}", List.of()))));
    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse("m", acc, Map.of());
    JsonNode output = response.at("/output");
    assertThat(output.get(0).get("type").asText()).isEqualTo("function_call");
    assertThat(output.get(0).get("name").asText()).isEqualTo("f");
    assertThat(output.get(0).get("arguments").asText()).isEqualTo("{\"x\":1}");
  }
}
