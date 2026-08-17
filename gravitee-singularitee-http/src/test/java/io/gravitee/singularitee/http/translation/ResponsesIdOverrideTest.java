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

/** Propagation of the stored-conversation {@code responseIdOverride} across Responses paths. */
class ResponsesIdOverrideTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static TokenMessage fin() {
    return TokenMessage.builder().isFinal(true).finishReason("stop").build();
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

  /** Every event embedding a response object must carry the same response id. */
  private static void assertResponseIds(List<JsonNode> events, String expectedId) {
    boolean seen = false;
    for (JsonNode e : events) {
      JsonNode response = e.path("response");
      if (response.isObject()) {
        seen = true;
        assertThat(response.path("id").asText()).isEqualTo(expectedId);
      }
    }
    assertThat(seen).isTrue();
  }

  @Test
  void liveStreamUsesOverrideOnEveryResponseEvent() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("hi"), fin()),
        "test-model",
        null,
        "resp_override_live"
      )
        .toList()
        .blockingGet()
    );

    assertResponseIds(events, "resp_override_live");
  }

  @Test
  void liveStreamDerivesIdFromEpochWhenOverrideIsNull() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("hi"), fin()),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    JsonNode created = events.get(0).path("response");
    long createdAt = created.path("created_at").asLong();
    assertResponseIds(events, "resp-" + createdAt);
  }

  @Test
  void bufferedStreamUsesOverrideOnEveryResponseEvent() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesBufferedStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("hi"), fin()),
        "test-model",
        null,
        null,
        "resp_override_buffered"
      )
        .toList()
        .blockingGet()
    );

    assertResponseIds(events, "resp_override_buffered");
  }

  @Test
  void bufferedStreamDerivesIdFromEpochWhenOverrideIsNull() {
    List<JsonNode> events = parse(
      InferenceResponseFormatter.responsesBufferedStreamEvents(
        Flowable.fromArray(TokenMessage.contentDelta("hi"), fin()),
        "test-model",
        null,
        null
      )
        .toList()
        .blockingGet()
    );

    long createdAt = events.get(0).at("/response/created_at").asLong();
    assertResponseIds(events, "resp-" + createdAt);
  }

  @Test
  void nonStreamingResponseUsesOverride() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(TokenMessage.contentDelta("hi"));
    accumulator.add(fin());

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null,
      "resp_override_sync"
    );

    assertThat(response.path("id").asText()).isEqualTo("resp_override_sync");
  }

  @Test
  void nonStreamingResponseDerivesIdFromEpochWhenOverrideIsNull() {
    SequenceAccumulator accumulator = new SequenceAccumulator();
    accumulator.add(TokenMessage.contentDelta("hi"));
    accumulator.add(fin());

    ObjectNode response = InferenceResponseFormatter.buildResponsesResponse(
      "test-model",
      accumulator,
      null
    );

    assertThat(response.path("id").asText()).isEqualTo("resp-" + accumulator.created());
  }
}
