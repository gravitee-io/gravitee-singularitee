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

import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Re-subscribe safety: the formatter Flowables are built with {@code Flowable.defer}, so a
 * second subscription over a cold source must replay the exact same event train instead of
 * corrupted per-subscription state (missing header chunks, stale accumulators).
 */
class StreamResubscribeSafetyTest {

  private static TokenMessage fin() {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason("stop")
      .promptTokens(3)
      .completionTokens(7)
      .build();
  }

  /** Blanks 10-digit epoch seconds so a run crossing a second boundary cannot flake. */
  private static List<String> normalized(List<ServerEvent> events) {
    return events
      .stream()
      .map(e -> e.data().replaceAll("\\d{10}", "<epoch>"))
      .toList();
  }

  @Test
  void chatStreamEventsProducesIdenticalEventsOnSecondSubscription() {
    Flowable<ServerEvent> stream = InferenceResponseFormatter.chatStreamEvents(
      Flowable.fromArray(
        TokenMessage.reasoningDelta("hmm"),
        TokenMessage.contentDelta("Hello"),
        TokenMessage.contentDelta(" world"),
        fin()
      ),
      "test-model",
      true,
      null
    );

    List<String> first = normalized(stream.toList().blockingGet());
    List<String> second = normalized(stream.toList().blockingGet());

    assertThat(first).isNotEmpty();
    assertThat(second).containsExactlyElementsOf(first);
    // The role-header chunk must be present on the second run too.
    assertThat(second.get(0)).contains("\"role\":\"assistant\"");
  }

  @Test
  void responsesStreamEventsProducesIdenticalEventsOnSecondSubscription() {
    Flowable<ServerEvent> stream = InferenceResponseFormatter.responsesStreamEvents(
      Flowable.fromArray(
        TokenMessage.reasoningDelta("think"),
        TokenMessage.contentDelta("Hello"),
        fin()
      ),
      "test-model",
      null,
      "resp_fixed"
    );

    List<String> first = normalized(stream.toList().blockingGet());
    List<String> second = normalized(stream.toList().blockingGet());

    assertThat(first).isNotEmpty();
    assertThat(second).containsExactlyElementsOf(first);
    // The header events must re-appear on the second run.
    assertThat(second.get(0)).contains("\"type\":\"response.created\"");
    assertThat(second.get(1)).contains("\"type\":\"response.in_progress\"");
  }

  @Test
  void responsesBufferedStreamEventsProducesIdenticalEventsOnSecondSubscription() {
    Flowable<ServerEvent> stream = InferenceResponseFormatter.responsesBufferedStreamEvents(
      Flowable.fromArray(
        TokenMessage.reasoningDelta("think"),
        TokenMessage.contentDelta("Hello"),
        fin()
      ),
      "test-model",
      null,
      null,
      "resp_fixed"
    );

    List<String> first = normalized(stream.toList().blockingGet());
    List<String> second = normalized(stream.toList().blockingGet());

    assertThat(first).isNotEmpty();
    assertThat(second).containsExactlyElementsOf(first);
    // A stale accumulator would double the content on re-subscription.
    long completedCount = second
      .stream()
      .filter(s -> s.contains("\"type\":\"response.completed\""))
      .count();
    assertThat(completedCount).isEqualTo(1);
    assertThat(second.get(second.size() - 1)).contains("\"text\":\"Hello\"");
  }
}
