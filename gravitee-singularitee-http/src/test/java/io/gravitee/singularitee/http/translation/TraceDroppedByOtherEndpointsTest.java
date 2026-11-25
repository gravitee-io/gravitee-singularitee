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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.protocol.StepTrace;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Only a non-streamed chat answer carries the step trace; the other endpoints drop it. */
class TraceDroppedByOtherEndpointsTest {

  private static final String STEP = "traced_step_id";

  private static TokenMessage trace() {
    return TokenMessage.stepTraced(
      StepTrace.newBuilder()
        .setStepId(STEP)
        .setStepType("guard")
        .putOutputs(STEP + ".output", "traced-output")
        .build()
    );
  }

  private static List<TokenMessage> run() {
    return List.of(
      trace(),
      TokenMessage.contentDelta("Hello"),
      trace(),
      TokenMessage.builder()
        .isFinal(true)
        .finishReason("stop")
        .promptTokens(3)
        .completionTokens(1)
        .build()
    );
  }

  private static SequenceAccumulator accumulated() {
    var acc = new SequenceAccumulator();
    run().forEach(acc::add);
    return acc;
  }

  private static void assertNoTrace(List<ServerEvent> events) {
    assertThat(events).isNotEmpty();
    assertThat(events).noneMatch(
      e ->
        e.data().contains(STEP) || e.data().contains("traced-output") || e.data().contains("trace")
    );
  }

  private static void assertNoTrace(ObjectNode json) {
    assertThat(json.has("gravitee_trace")).isFalse();
    assertThat(json.toString()).doesNotContain(STEP).doesNotContain("traced-output");
  }

  @Test
  void the_legacy_completions_stream_drops_it() {
    var events = LegacyCompletionsFormatter.completionStreamEvents(
      Flowable.fromIterable(run()),
      "llm",
      true,
      t -> {}
    )
      .toList()
      .blockingGet();

    assertNoTrace(events);
    assertThat(events).anyMatch(e -> e.data().contains("\"Hello\""));
  }

  @Test
  void the_legacy_completions_answer_drops_it() {
    var json = LegacyCompletionsFormatter.buildCompletionResponse("llm", accumulated());

    assertNoTrace(json);
    assertThat(json.path("choices").get(0).path("text").asText()).isEqualTo("Hello");
  }

  @Test
  void the_responses_stream_drops_it() {
    var events = ResponsesFormatter.responsesStreamEvents(
      Flowable.fromIterable(run()),
      "llm",
      t -> {},
      null
    )
      .toList()
      .blockingGet();

    assertNoTrace(events);
    assertThat(events).anyMatch(e -> e.data().contains("response.completed"));
  }

  @Test
  void the_buffered_responses_stream_drops_it() {
    var events = ResponsesFormatter.responsesBufferedStreamEvents(
      Flowable.fromIterable(run()),
      "llm",
      t -> {},
      java.util.Map.of(),
      null
    )
      .toList()
      .blockingGet();

    assertNoTrace(events);
    assertThat(events).anyMatch(e -> e.data().contains("response.completed"));
  }

  @Test
  void the_responses_answer_drops_it() {
    var json = ResponsesFormatter.buildResponsesResponse(
      "llm",
      accumulated(),
      java.util.Map.of(),
      null
    );

    assertNoTrace(json);
    assertThat(json.path("status").asText()).isEqualTo("completed");
    assertThat(json.toString()).contains("Hello");
  }
}
