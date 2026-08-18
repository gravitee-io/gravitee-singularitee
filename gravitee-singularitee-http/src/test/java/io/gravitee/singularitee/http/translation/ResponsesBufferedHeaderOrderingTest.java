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
import io.gravitee.singularitee.protocol.ResponseProgress;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Buffered Responses stream lifecycle: the {@code response.created} / {@code response.in_progress}
 * header pair must precede every other event — including live progress and reasoning side-channel
 * events — and the final output items must carry the full wire shape (ids, status, annotations).
 */
class ResponsesBufferedHeaderOrderingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage fin(String finishReason) {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason(finishReason)
      .promptTokens(3)
      .completionTokens(7)
      .build();
  }

  private static List<JsonNode> run(TokenMessage... tokens) {
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

  private static void assertHeaderFirst(List<JsonNode> events) {
    assertThat(events.get(0).path("type").asText()).isEqualTo("response.created");
    assertThat(events.get(0).path("sequence_number").asLong()).isZero();
    assertThat(events.get(1).path("type").asText()).isEqualTo("response.in_progress");
    assertThat(events.get(1).path("sequence_number").asLong()).isEqualTo(1);
    long previous = -1;
    for (JsonNode e : events) {
      long seq = e.path("sequence_number").asLong();
      assertThat(seq).isGreaterThan(previous);
      previous = seq;
    }
  }

  @Test
  void progressFirstStreamStillOpensWithTheHeaderPair() {
    List<JsonNode> events = run(
      TokenMessage.progressUpdate(ResponseProgress.newBuilder().build()),
      TokenMessage.contentDelta("Hello"),
      fin("stop")
    );

    assertHeaderFirst(events);
    assertThat(events.get(2).path("type").asText()).isEqualTo("gravitee.progress");
  }

  @Test
  void liveReasoningFirstStreamStillOpensWithTheHeaderPair() {
    List<JsonNode> events = run(
      TokenMessage.reasoningDelta("thinking"),
      TokenMessage.contentDelta("Hello"),
      fin("stop")
    );

    assertHeaderFirst(events);
    assertThat(events.get(2).path("type").asText()).isEqualTo("response.output_item.added");
    assertThat(events.get(2).at("/item/type").asText()).isEqualTo("reasoning");
    // The header is emitted exactly once even though the final train follows.
    long created = events
      .stream()
      .filter(e -> "response.created".equals(e.path("type").asText()))
      .count();
    assertThat(created).isEqualTo(1);
    long inProgress = events
      .stream()
      .filter(e -> "response.in_progress".equals(e.path("type").asText()))
      .count();
    assertThat(inProgress).isEqualTo(1);
  }

  @Test
  void completedOutputMessageCarriesFullWireShape() {
    List<JsonNode> events = run(TokenMessage.contentDelta("Hello"), fin("stop"));

    assertHeaderFirst(events);
    JsonNode completed = events.get(events.size() - 1);
    assertThat(completed.path("type").asText()).isEqualTo("response.completed");
    JsonNode message = completed.at("/response/output/0");
    long createdAt = completed.at("/response/created_at").asLong();
    assertThat(message.path("id").asText()).isEqualTo("msg-" + createdAt);
    assertThat(message.path("type").asText()).isEqualTo("message");
    assertThat(message.path("status").asText()).isEqualTo("completed");
    assertThat(message.path("role").asText()).isEqualTo("assistant");
    JsonNode part = message.at("/content/0");
    assertThat(part.path("type").asText()).isEqualTo("output_text");
    assertThat(part.path("text").asText()).isEqualTo("Hello");
    assertThat(part.path("annotations").isArray()).isTrue();
    assertThat(part.path("annotations")).isEmpty();
  }

  @Test
  void completedOutputFunctionCallCarriesStatus() {
    List<JsonNode> events = run(
      TokenMessage.toolDelta("{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}"),
      fin("tool_calls")
    );

    JsonNode completed = events.get(events.size() - 1);
    JsonNode call = completed.at("/response/output/0");
    assertThat(call.path("type").asText()).isEqualTo("function_call");
    assertThat(call.path("status").asText()).isEqualTo("completed");
    assertThat(call.path("name").asText()).isEqualTo("get_weather");
    assertThat(call.has("id")).isTrue();
    assertThat(call.has("call_id")).isTrue();
  }

  @Test
  void nonStreamingOutputItemsCarryFullWireShape() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(TokenMessage.contentDelta("Hello"));
    accumulator.add(fin("stop"));

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    JsonNode message = response.at("/output/0");
    assertThat(message.path("id").asText()).isEqualTo("msg-" + accumulator.created());
    assertThat(message.path("type").asText()).isEqualTo("message");
    assertThat(message.path("status").asText()).isEqualTo("completed");
    assertThat(message.path("role").asText()).isEqualTo("assistant");
    JsonNode part = message.at("/content/0");
    assertThat(part.path("type").asText()).isEqualTo("output_text");
    assertThat(part.path("text").asText()).isEqualTo("Hello");
    assertThat(part.path("annotations").isArray()).isTrue();
  }

  @Test
  void nonStreamingFunctionCallCarriesStatus() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(
      TokenMessage.toolDelta("{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}")
    );
    accumulator.add(fin("tool_calls"));

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    JsonNode call = response.at("/output/0");
    assertThat(call.path("type").asText()).isEqualTo("function_call");
    assertThat(call.path("status").asText()).isEqualTo("completed");
    assertThat(call.path("name").asText()).isEqualTo("get_weather");
  }
}
