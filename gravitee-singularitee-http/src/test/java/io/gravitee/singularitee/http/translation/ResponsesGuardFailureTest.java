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
import org.junit.jupiter.api.Test;

/**
 * A guard-only failure (final token with a guardMessage and no generated content) must surface
 * as {@code response.failed} / a failed response object, never as a fake empty "completed".
 */
class ResponsesGuardFailureTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage guardFinal(String guardMessage) {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason("content_filter")
      .guardMessage(guardMessage)
      .build();
  }

  private static List<JsonNode> parse(List<ServerEvent> events) {
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

  @Test
  void liveStreamEmitsResponseFailedWithServerErrorCode() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.just(guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types).containsExactly(
      "response.created",
      "response.in_progress",
      "response.failed"
    );
    JsonNode failed = events.get(2).path("response");
    assertThat(failed.path("status").asText()).isEqualTo("failed");
    assertThat(failed.at("/error/code").asText()).isEqualTo("server_error");
    assertThat(failed.at("/error/message").asText()).isEqualTo("Blocked by guard");
    assertThat(failed.path("output").isArray()).isTrue();
    assertThat(failed.path("output")).isEmpty();
  }

  @Test
  void liveStreamMapsStoredResponseMissToPreviousResponseNotFound() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.just(guardFinal("No stored response with id resp_x")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    JsonNode failed = events.get(events.size() - 1);
    assertThat(failed.path("type").asText()).isEqualTo("response.failed");
    assertThat(failed.at("/response/error/code").asText()).isEqualTo("previous_response_not_found");
  }

  @Test
  void liveStreamWithPriorContentFailsAndClosesThePartialMessage() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("partial"), guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types)
      .doesNotContain("response.completed")
      .endsWith(
        "response.output_text.done",
        "response.content_part.done",
        "response.output_item.done",
        "response.failed"
      );

    JsonNode itemDone = events.get(types.indexOf("response.output_item.done"));
    assertThat(itemDone.at("/item/status").asText()).isEqualTo("incomplete");
    assertThat(itemDone.at("/item/content/0/text").asText()).isEqualTo("partial");

    JsonNode failed = events.get(events.size() - 1).path("response");
    assertThat(failed.path("status").asText()).isEqualTo("failed");
    assertThat(failed.at("/error/code").asText()).isEqualTo("server_error");
    assertThat(failed.at("/output/0/type").asText()).isEqualTo("message");
    assertThat(failed.at("/output/0/status").asText()).isEqualTo("incomplete");
    assertThat(failed.at("/output/0/content/0/text").asText()).isEqualTo("partial");
  }

  @Test
  void liveStreamWithPriorReasoningFailsAndClosesTheReasoningItem() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.fromArray(TokenMessage.reasoningDelta("thinking"), guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types)
      .doesNotContain("response.completed")
      .endsWith(
        "response.reasoning_summary_text.done",
        "response.reasoning_summary_part.done",
        "response.output_item.done",
        "response.failed"
      );

    JsonNode failed = events.get(events.size() - 1).path("response");
    assertThat(failed.path("status").asText()).isEqualTo("failed");
    assertThat(failed.at("/output/0/type").asText()).isEqualTo("reasoning");
    assertThat(failed.at("/output/0/summary/0/text").asText()).isEqualTo("thinking");
  }

  @Test
  void bufferedStreamEmitsResponseFailed() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesBufferedStreamEvents(
        Flowable.just(guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types).containsExactly(
      "response.created",
      "response.in_progress",
      "response.failed"
    );
    assertThat(events.get(2).at("/response/error/code").asText()).isEqualTo("server_error");
    assertThat(events.get(2).at("/response/error/message").asText()).isEqualTo("Blocked by guard");
    assertThat(events.get(2).at("/response/output")).isEmpty();
  }

  @Test
  void bufferedStreamWithBufferedContentFailsWithPartialOutputAttached() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesBufferedStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("partial"), guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types).containsExactly(
      "response.created",
      "response.in_progress",
      "response.failed"
    );
    JsonNode failed = events.get(2).path("response");
    assertThat(failed.path("status").asText()).isEqualTo("failed");
    assertThat(failed.at("/error/code").asText()).isEqualTo("server_error");
    assertThat(failed.at("/output/0/type").asText()).isEqualTo("message");
    assertThat(failed.at("/output/0/status").asText()).isEqualTo("incomplete");
    assertThat(failed.at("/output/0/content/0/text").asText()).isEqualTo("partial");
  }

  @Test
  void bufferedStreamWithLiveReasoningFailsAfterClosingTheReasoningItem() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesBufferedStreamEvents(
        Flowable.fromArray(TokenMessage.reasoningDelta("thinking"), guardFinal("Blocked by guard")),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    List<String> types = events
      .stream()
      .map(e -> e.path("type").asText())
      .toList();
    assertThat(types)
      .doesNotContain("response.completed")
      .endsWith(
        "response.reasoning_summary_text.done",
        "response.reasoning_summary_part.done",
        "response.output_item.done",
        "response.failed"
      );
    JsonNode failed = events.get(events.size() - 1).path("response");
    assertThat(failed.path("status").asText()).isEqualTo("failed");
    assertThat(failed.at("/output/0/type").asText()).isEqualTo("reasoning");
    assertThat(failed.at("/output/0/summary/0/text").asText()).isEqualTo("thinking");
  }

  @Test
  void nonStreamingResponseRendersFailedShape() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(guardFinal("No stored response with id resp_y"));

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    assertThat(response.path("status").asText()).isEqualTo("failed");
    assertThat(response.at("/error/code").asText()).isEqualTo("previous_response_not_found");
    assertThat(response.at("/error/message").asText()).isEqualTo(
      "No stored response with id resp_y"
    );
    assertThat(response.path("output").isArray()).isTrue();
    assertThat(response.path("output")).isEmpty();
    assertThat(response.has("usage")).isFalse();
  }

  @Test
  void nonStreamingResponseWithContentFailsWithPartialOutputAttached() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(TokenMessage.contentDelta("partial"));
    accumulator.add(guardFinal("Blocked by guard"));

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    assertThat(response.path("status").asText()).isEqualTo("failed");
    assertThat(response.at("/error/code").asText()).isEqualTo("server_error");
    assertThat(response.at("/error/message").asText()).isEqualTo("Blocked by guard");
    assertThat(response.at("/output/0/type").asText()).isEqualTo("message");
    assertThat(response.at("/output/0/status").asText()).isEqualTo("incomplete");
    assertThat(response.at("/output/0/content/0/text").asText()).isEqualTo("partial");
  }

  @Test
  void nonStreamingResponseWithReasoningFailsWithReasoningAttached() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(TokenMessage.reasoningDelta("thinking"));
    accumulator.add(guardFinal("Blocked by guard"));

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    assertThat(response.path("status").asText()).isEqualTo("failed");
    assertThat(response.at("/output/0/type").asText()).isEqualTo("reasoning");
    assertThat(response.at("/output/0/summary/0/text").asText()).isEqualTo("thinking");
  }
}
