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
 * Unit tests for the Gemma tool-call flavor in
 * {@link InferenceResponseFormatter#parseToolCalls(String, Map)}:
 * {@code <|tool_call>call:NAME{key:<|"|>value<|"|>,...}<tool_call|>}.
 */
class GemmaToolCallParsingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Map<String, JsonNode> schemas(String toolsJson) {
    try {
      return InferenceResponseFormatter.toolParameterSchemas(MAPPER.readTree(toolsJson));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static JsonNode argsOf(ParsedToolCall call) {
    try {
      return MAPPER.readTree(call.arguments());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** The exact block observed in the field (leaked as plain content before this flavor existed). */
  private static final String OBSERVED =
    ">\n<|channel>thought\n<channel|><|tool_call>call:send_email{body:<|\"|>Hi Jamie, just a " +
    "friendly reminder that you need to finish the quarterly report by tomorrow.<|\"|>,to:" +
    "<|\"|>jamie@acme.com<|\"|>}<tool_call|>";

  @Test
  void parsesObservedGemmaBlock() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(OBSERVED);
    assertThat(calls).hasSize(1);
    ParsedToolCall call = calls.get(0);
    assertThat(call.name()).isEqualTo("send_email");
    JsonNode args = argsOf(call);
    assertThat(args.size()).isEqualTo(2);
    assertThat(args.get("body").asText()).isEqualTo(
      "Hi Jamie, just a friendly reminder that you need to finish the quarterly report by tomorrow."
    );
    assertThat(args.get("to").asText()).isEqualTo("jamie@acme.com");
  }

  @Test
  void stringValuesMayContainCommasBracesAndNewlines() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:send_email{body:<|\"|>Line one, with {braces} and [brackets].\n" +
        "Line two: still the same value.<|\"|>,to:<|\"|>a@b.c<|\"|>}<tool_call|>"
    );
    assertThat(calls).hasSize(1);
    JsonNode args = argsOf(calls.get(0));
    assertThat(args.get("body").asText()).isEqualTo(
      "Line one, with {braces} and [brackets].\nLine two: still the same value."
    );
    assertThat(args.get("to").asText()).isEqualTo("a@b.c");
  }

  @Test
  void bareScalarsAreCoercedViaSchema() {
    Map<String, JsonNode> readSchemas = schemas(
      """
      [{"type":"function","function":{"name":"read","parameters":{
        "type":"object","properties":{
          "filePath":{"type":"string"},
          "limit":{"type":"integer"},
          "recursive":{"type":"boolean"},
          "threshold":{"type":"number"}
        }}}}]
      """
    );
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:read{filePath:<|\"|>/tmp/a.txt<|\"|>,limit:80,recursive:true," +
        "threshold:0.5}<tool_call|>",
      readSchemas
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().name()).isEqualTo("read");
    JsonNode args = argsOf(calls.getFirst());
    assertThat(args.get("filePath").asText()).isEqualTo("/tmp/a.txt");
    assertThat(args.get("limit").isIntegralNumber()).isTrue();
    assertThat(args.get("limit").asLong()).isEqualTo(80L);
    assertThat(args.get("recursive").isBoolean()).isTrue();
    assertThat(args.get("recursive").asBoolean()).isTrue();
    assertThat(args.get("threshold").isDouble()).isTrue();
    assertThat(args.get("threshold").asDouble()).isEqualTo(0.5d);
  }

  @Test
  void bareScalarsWithoutSchemaStayStrings() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:read{limit:80}<tool_call|>"
    );
    assertThat(calls).hasSize(1);
    JsonNode args = argsOf(calls.getFirst());
    assertThat(args.get("limit").isTextual()).isTrue();
    assertThat(args.get("limit").asText()).isEqualTo("80");
  }

  @Test
  void arrayValueWithDelimitedStringsIsCoercedViaSchema() {
    Map<String, JsonNode> tagSchemas = schemas(
      """
      [{"type":"function","function":{"name":"tag","parameters":{
        "type":"object","properties":{
          "tags":{"type":"array","items":{"type":"string"}}
        }}}}]
      """
    );
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:tag{tags:[<|\"|>a,b<|\"|>,<|\"|>c<|\"|>]}<tool_call|>",
      tagSchemas
    );
    assertThat(calls).hasSize(1);
    JsonNode args = argsOf(calls.get(0));
    assertThat(args.get("tags").isArray()).isTrue();
    assertThat(args.get("tags").get(0).asText()).isEqualTo("a,b");
    assertThat(args.get("tags").get(1).asText()).isEqualTo("c");
  }

  @Test
  void multipleGemmaBlocksYieldMultipleCalls() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:first{a:<|\"|>1<|\"|>}<tool_call|>\n" +
        "<|tool_call>call:second{b:<|\"|>2<|\"|>}<tool_call|>"
    );
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).name()).isEqualTo("first");
    assertThat(calls.get(1).name()).isEqualTo("second");
  }

  @Test
  void escapedKeysAreAccepted() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:send_email{<|\"|>to<|\"|>:<|\"|>a@b.c<|\"|>}<tool_call|>"
    );
    assertThat(calls).hasSize(1);
    JsonNode args = argsOf(calls.get(0));
    assertThat(args.get("to").asText()).isEqualTo("a@b.c");
  }

  @Test
  void emptyArgumentsParseToEmptyObject() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<|tool_call>call:ping{}<tool_call|>"
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("ping");
    assertThat(calls.get(0).arguments()).isEqualTo("{}");
  }

  @Test
  void malformedBlockWithoutCallPrefixIsIgnored() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "some text <|tool_call>not a call<tool_call|> more text"
    );
    assertThat(calls).isEmpty();
  }

  @Test
  void qwenJsonFlavorIsUnaffected() {
    List<ParsedToolCall> calls = InferenceResponseFormatter.parseToolCalls(
      "<tool_call>{\"name\":\"read\",\"arguments\":{\"filePath\":\"/tmp/a\"}}</tool_call>"
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("read");
  }
}
