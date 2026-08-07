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
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the marker-less (TOOL-channel) tool-call flow: engines that classify tool tokens
 * suppress the tag markers, so the payload arrives BARE on {@code TokenMessage.tool()} deltas.
 */
class ToolChannelParsingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage content(String s) {
    return new TokenMessage(s, 0, false, null, 0, 0, null, null, null, null);
  }

  private static TokenMessage reasoning(String s) {
    return new TokenMessage(null, s, 0, false, null, 0, 0, null, null, null, null);
  }

  private static TokenMessage tool(String s) {
    return TokenMessage.toolDelta(s);
  }

  private static TokenMessage fin(String finishReason) {
    return new TokenMessage(null, 0, true, finishReason, 3, 7, null, null, null, null);
  }

  private static Map<String, JsonNode> weatherSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    ObjectNode props = schema.putObject("properties");
    props.putObject("city").put("type", "string");
    props.putObject("days").put("type", "integer");
    return Map.of("get_weather", schema);
  }

  // ── parseBareToolCalls ──────────────────────────────────────────────────

  @Test
  void bareJsonObjectParses() {
    var calls = InferenceResponseFormatter.parseBareToolCalls(
      "{\"name\":\"send_email\",\"arguments\":{\"to\":\"a@b.c\"}}",
      Map.of()
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("send_email");
    assertThat(calls.get(0).arguments()).isEqualTo("{\"to\":\"a@b.c\"}");
  }

  @Test
  void concatenatedBareJsonObjectsParseAsMultipleCalls() {
    var calls = InferenceResponseFormatter.parseBareToolCalls(
      "{\"name\":\"a\",\"arguments\":{}}\n{\"name\":\"b\",\"arguments\":{\"x\":1}}",
      Map.of()
    );
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).name()).isEqualTo("a");
    assertThat(calls.get(1).name()).isEqualTo("b");
    assertThat(calls.get(1).arguments()).isEqualTo("{\"x\":1}");
  }

  @Test
  void bareJsonArrayParses() {
    var calls = InferenceResponseFormatter.parseBareToolCalls(
      "[{\"name\":\"a\",\"arguments\":{}},{\"name\":\"b\"}]",
      Map.of()
    );
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1).arguments()).isEqualTo("{}");
  }

  @Test
  void bareGemmaBodyParsesWithSchemaCoercion() {
    var calls = InferenceResponseFormatter.parseBareToolCalls(
      "call:get_weather{city:<|\"|>Paris, France<|\"|>,days:3}",
      weatherSchema()
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("get_weather");
    assertThat(calls.get(0).arguments()).isEqualTo("{\"city\":\"Paris, France\",\"days\":3}");
  }

  @Test
  void multipleBareGemmaBodiesParse() {
    var calls = InferenceResponseFormatter.parseBareToolCalls("call:a{x:1}call:b{y:2}", Map.of());
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).name()).isEqualTo("a");
    assertThat(calls.get(1).name()).isEqualTo("b");
  }

  @Test
  void garbagePayloadYieldsNoCalls() {
    assertThat(
      InferenceResponseFormatter.parseBareToolCalls("not a tool call", Map.of())
    ).isEmpty();
    assertThat(InferenceResponseFormatter.parseBareToolCalls("{broken json", Map.of())).isEmpty();
    assertThat(InferenceResponseFormatter.parseBareToolCalls(null, Map.of())).isEmpty();
  }

  @Test
  void resolveFallsBackToLegacyMarkerParsingWhenToolChannelEmpty() {
    var calls = InferenceResponseFormatter.resolveToolCalls(
      "",
      "<tool_call>{\"name\":\"f\",\"arguments\":{}}</tool_call>",
      Map.of()
    );
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("f");
  }

  // ── Buffered (non-streaming) ────────────────────────────────────────────

  private static SequenceAccumulator accumulate(TokenMessage... tokens) {
    var acc = new SequenceAccumulator();
    for (TokenMessage t : tokens) {
      acc.add(t);
    }
    return acc;
  }

  @Test
  void bufferedChatResponseParsesToolChannelPayload() {
    var acc = accumulate(
      reasoning("thinking"),
      tool("{\"name\":\"send_email\","),
      tool("\"arguments\":{\"to\":\"a@b.c\"}}"),
      fin("tool_calls")
    );
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, Map.of());
    JsonNode message = response.at("/choices/0/message");
    assertThat(message.get("content").isNull()).isTrue();
    assertThat(message.at("/tool_calls/0/function/name").asText()).isEqualTo("send_email");
    assertThat(message.at("/tool_calls/0/function/arguments").asText()).isEqualTo(
      "{\"to\":\"a@b.c\"}"
    );
    assertThat(message.at("/reasoning_content").asText()).isEqualTo("thinking");
  }

  @Test
  void bufferedChatResponseParsesBareGemmaBody() {
    var acc = accumulate(tool("call:get_weather{city:<|\"|>Paris<|\"|>}"), fin("tool_calls"));
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, weatherSchema());
    assertThat(response.at("/choices/0/message/tool_calls/0/function/name").asText()).isEqualTo(
      "get_weather"
    );
  }

  @Test
  void bufferedChatResponseFailsOpenOnUnparseablePayload() {
    var acc = accumulate(content("Hello "), tool("garbage payload"), fin("tool_calls"));
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, Map.of());
    assertThat(response.at("/choices/0/message/content").asText()).isEqualTo(
      "Hello garbage payload"
    );
    assertThat(response.at("/choices/0/message/tool_calls").isMissingNode()).isTrue();
  }

  @Test
  void bufferedChatResponseParsesToolChannelEvenWithoutToolCallsFinish() {
    // Some engines may finish with stop while still having stamped a tool span.
    var acc = accumulate(tool("{\"name\":\"f\",\"arguments\":{}}"), fin("stop"));
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, Map.of());
    assertThat(response.at("/choices/0/message/tool_calls/0/function/name").asText()).isEqualTo(
      "f"
    );
  }

  @Test
  void bufferedChatResponseLegacyTaggedContentStillParses() {
    var acc = accumulate(
      content("<tool_call>{\"name\":\"f\",\"arguments\":{}}</tool_call>"),
      fin("tool_calls")
    );
    ObjectNode response = InferenceResponseFormatter.buildChatResponse("m", acc, Map.of());
    assertThat(response.at("/choices/0/message/tool_calls/0/function/name").asText()).isEqualTo(
      "f"
    );
  }

  @Test
  void bufferedResponsesResponseParsesToolChannelPayload() {
    var acc = accumulate(tool("{\"name\":\"f\",\"arguments\":{\"x\":1}}"), fin("tool_calls"));
    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse("m", acc, Map.of());
    JsonNode output = response.at("/output");
    assertThat(output.get(0).get("type").asText()).isEqualTo("function_call");
    assertThat(output.get(0).get("name").asText()).isEqualTo("f");
    assertThat(output.get(0).get("arguments").asText()).isEqualTo("{\"x\":1}");
  }

  // ── Streaming (holdback) ────────────────────────────────────────────────

  private List<JsonNode> runHoldback(Map<String, JsonNode> schemas, TokenMessage... tokens) {
    List<ServerEvent> events = InferenceResponseFormatter.chatStreamEventsWithToolHoldback(
      Flowable.fromArray(tokens),
      "m",
      false,
      null,
      schemas
    )
      .toList()
      .blockingGet();
    List<JsonNode> chunks = new ArrayList<>();
    for (ServerEvent e : events) {
      if (!"[DONE]".equals(e.data())) {
        try {
          chunks.add(MAPPER.readTree(e.data()));
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
    }
    return chunks;
  }

  private static List<String> contentDeltas(List<JsonNode> chunks) {
    List<String> out = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode delta = c.at("/choices/0/delta/content");
      if (!delta.isMissingNode() && !delta.asText().isEmpty()) {
        out.add(delta.asText());
      }
    }
    return out;
  }

  private static List<JsonNode> toolCallDeltas(List<JsonNode> chunks) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode tc = c.at("/choices/0/delta/tool_calls");
      if (tc.isArray()) {
        out.add(tc.get(0));
      }
    }
    return out;
  }

  private static String finishReason(List<JsonNode> chunks) {
    String finish = null;
    for (JsonNode c : chunks) {
      JsonNode fr = c.at("/choices/0/finish_reason");
      if (!fr.isMissingNode() && !fr.isNull()) {
        finish = fr.asText();
      }
    }
    return finish;
  }

  @Test
  void streamingToolChannelDeltasNeverStreamAsContent() {
    var chunks = runHoldback(
      Map.of(),
      reasoning("hmm"),
      content("Checking. "),
      tool("{\"name\":\"send_email\","),
      tool("\"arguments\":{}}"),
      fin("tool_calls")
    );
    // Only the plain content streamed; the tool payload never leaked as content.
    assertThat(contentDeltas(chunks)).containsExactly("Checking. ");
    var toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("send_email");
    assertThat(finishReason(chunks)).isEqualTo("tool_calls");
  }

  @Test
  void streamingBareGemmaToolChannelParses() {
    var chunks = runHoldback(
      weatherSchema(),
      tool("call:get_weather{city:<|\"|>Paris<|\"|>,days:2}"),
      fin("tool_calls")
    );
    var toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("get_weather");
    assertThat(toolCalls.get(0).at("/function/arguments").asText()).isEqualTo(
      "{\"city\":\"Paris\",\"days\":2}"
    );
  }

  @Test
  void streamingUnparseableToolChannelFailsOpenAsContent() {
    var chunks = runHoldback(Map.of(), content("Hi "), tool("garbage"), fin("stop"));
    assertThat(toolCallDeltas(chunks)).isEmpty();
    assertThat(String.join("", contentDeltas(chunks))).isEqualTo("Hi garbage");
    assertThat(finishReason(chunks)).isEqualTo("stop");
  }

  @Test
  void streamingReasoningWithoutMarkersStillRoutesAsReasoningContent() {
    var chunks = runHoldback(Map.of(), reasoning("no markers "), reasoning("here"), fin("stop"));
    List<String> reasoningDeltas = new ArrayList<>();
    for (JsonNode c : chunks) {
      JsonNode r = c.at("/choices/0/delta/reasoning_content");
      if (!r.isMissingNode() && !r.asText().isEmpty()) {
        reasoningDeltas.add(r.asText());
      }
    }
    assertThat(reasoningDeltas).containsExactly("no markers ", "here");
    assertThat(contentDeltas(chunks)).isEmpty();
  }

  @Test
  void streamingLegacyTaggedContentFallbackStillWorks() {
    var chunks = runHoldback(
      Map.of(),
      content("<tool_call>{\"name\":\"f\","),
      content("\"arguments\":{}}</tool_call>"),
      fin("tool_calls")
    );
    assertThat(contentDeltas(chunks)).isEmpty();
    var toolCalls = toolCallDeltas(chunks);
    assertThat(toolCalls).hasSize(1);
    assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("f");
  }
}
