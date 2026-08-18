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
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Output-index shifting on the buffered Responses stream: a live reasoning item occupies
 * output_index 0 and pushes the buffered message/function_call items up by one.
 */
class ResponsesBufferedIndexShiftTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage fin() {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason("stop")
      .promptTokens(3)
      .completionTokens(7)
      .build();
  }

  private List<JsonNode> run(TokenMessage... tokens) {
    List<ServerEvent> events = InferenceResponseFormatter.responsesBufferedStreamEvents(
      Flowable.fromArray(tokens),
      "test-model",
      null,
      null
    )
      .toList()
      .blockingGet();
    List<JsonNode> parsed = new ArrayList<>();
    for (ServerEvent e : events) {
      try {
        parsed.add(MAPPER.readTree(e.data()));
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
    return parsed;
  }

  private static List<JsonNode> ofType(List<JsonNode> events, String type) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode e : events) {
      if (type.equals(e.path("type").asText())) {
        out.add(e);
      }
    }
    return out;
  }

  @Test
  void reasoningItemShiftsMessageItemToIndexOne() {
    List<JsonNode> events = run(
      TokenMessage.reasoningDelta("thinking"),
      TokenMessage.contentDelta("Hello"),
      fin()
    );

    // The reasoning item is added live at output_index 0 and closed at 0.
    List<JsonNode> added = ofType(events, "response.output_item.added");
    assertThat(added).hasSize(2);
    assertThat(added.get(0).at("/item/type").asText()).isEqualTo("reasoning");
    assertThat(added.get(0).path("output_index").asInt()).isZero();
    assertThat(added.get(1).at("/item/type").asText()).isEqualTo("message");
    assertThat(added.get(1).path("output_index").asInt()).isEqualTo(1);

    List<JsonNode> done = ofType(events, "response.output_item.done");
    assertThat(done).hasSize(2);
    assertThat(done.get(0).at("/item/type").asText()).isEqualTo("reasoning");
    assertThat(done.get(0).path("output_index").asInt()).isZero();
    assertThat(done.get(1).at("/item/type").asText()).isEqualTo("message");
    assertThat(done.get(1).path("output_index").asInt()).isEqualTo(1);

    // Text delta events carry the shifted index too.
    for (JsonNode delta : ofType(events, "response.output_text.delta")) {
      assertThat(delta.path("output_index").asInt()).isEqualTo(1);
    }

    // The final response.completed embeds output in the same order.
    JsonNode completed = ofType(events, "response.completed").get(0);
    JsonNode output = completed.at("/response/output");
    assertThat(output.size()).isEqualTo(2);
    assertThat(output.get(0).path("type").asText()).isEqualTo("reasoning");
    assertThat(output.get(0).at("/summary/0/text").asText()).isEqualTo("thinking");
    assertThat(output.get(1).path("type").asText()).isEqualTo("message");
    assertThat(output.get(1).at("/content/0/text").asText()).isEqualTo("Hello");
  }

  @Test
  void withoutReasoningMessageItemStaysAtIndexZero() {
    List<JsonNode> events = run(TokenMessage.contentDelta("Hello"), fin());

    List<JsonNode> added = ofType(events, "response.output_item.added");
    assertThat(added).hasSize(1);
    assertThat(added.get(0).at("/item/type").asText()).isEqualTo("message");
    assertThat(added.get(0).path("output_index").asInt()).isZero();

    JsonNode completed = ofType(events, "response.completed").get(0);
    JsonNode output = completed.at("/response/output");
    assertThat(output.size()).isEqualTo(1);
    assertThat(output.get(0).path("type").asText()).isEqualTo("message");
  }

  @Test
  void reasoningShiftsFunctionCallItemsAfterNarration() {
    List<JsonNode> events = run(
      TokenMessage.reasoningDelta("plan"),
      TokenMessage.contentDelta("Checking the weather now."),
      TokenMessage.toolDelta("{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}"),
      TokenMessage.builder()
        .isFinal(true)
        .finishReason("tool_calls")
        .promptTokens(3)
        .completionTokens(7)
        .build()
    );

    List<JsonNode> added = ofType(events, "response.output_item.added");
    // reasoning (0) -> narration message (1) -> function_call (2)
    assertThat(added).hasSize(3);
    assertThat(added.get(0).at("/item/type").asText()).isEqualTo("reasoning");
    assertThat(added.get(0).path("output_index").asInt()).isZero();
    assertThat(added.get(1).at("/item/type").asText()).isEqualTo("message");
    assertThat(added.get(1).path("output_index").asInt()).isEqualTo(1);
    assertThat(added.get(2).at("/item/type").asText()).isEqualTo("function_call");
    assertThat(added.get(2).path("output_index").asInt()).isEqualTo(2);

    JsonNode argsDone = ofType(events, "response.function_call_arguments.done").get(0);
    assertThat(argsDone.path("output_index").asInt()).isEqualTo(2);
    assertThat(argsDone.path("name").asText()).isEqualTo("get_weather");
  }

  @Test
  void sequenceNumbersAreStrictlyIncreasingAcrossAllEvents() {
    List<JsonNode> events = run(
      TokenMessage.reasoningDelta("a"),
      TokenMessage.reasoningDelta("b"),
      TokenMessage.contentDelta("Hello"),
      TokenMessage.contentDelta(" world"),
      fin()
    );

    long previous = -1;
    for (JsonNode e : events) {
      assertThat(e.has("sequence_number")).as("event %s carries sequence_number", e).isTrue();
      long seq = e.path("sequence_number").asLong();
      assertThat(seq).isGreaterThan(previous);
      previous = seq;
    }
  }
}
