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

import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.StepTrace;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatTraceFormatterTest {

  private static StepTrace guard() {
    return StepTrace.newBuilder()
      .setStepId("pii_guard")
      .setStepType("guard")
      .setStartedAtMs(0)
      .setDurationMs(18)
      .setNextStep("generate")
      .setModelId("pii")
      .putOutputs("pii_guard.output", "My email is ************")
      .build();
  }

  private static StepTrace blocked() {
    return StepTrace.newBuilder()
      .setStepId("toxicity")
      .setStepType("guard")
      .setDurationMs(9)
      .setHalted(true)
      .setFinishReason(FinishReason.FINISH_REASON_GUARD_BLOCKED)
      .build();
  }

  private static TokenMessage done() {
    return TokenMessage.builder()
      .isFinal(true)
      .finishReason("stop")
      .promptTokens(12)
      .completionTokens(4)
      .build();
  }

  @Test
  void a_traced_run_adds_gravitee_trace() {
    var acc = new SequenceAccumulator();
    acc.add(TokenMessage.stepTraced(guard()));
    acc.add(TokenMessage.contentDelta("Hello"));
    acc.add(TokenMessage.stepTraced(blocked()));
    acc.add(done());

    var json = ChatCompletionsFormatter.buildChatResponse("pipeline:guarded", acc, Map.of());

    var steps = json.path("gravitee_trace").path("steps");
    assertThat(steps).hasSize(2);
    var first = steps.get(0);
    assertThat(first.path("step_id").asText()).isEqualTo("pii_guard");
    assertThat(first.path("step_type").asText()).isEqualTo("guard");
    assertThat(first.path("duration_ms").asLong()).isEqualTo(18);
    assertThat(first.path("next_step").asText()).isEqualTo("generate");
    assertThat(first.path("model_id").asText()).isEqualTo("pii");
    assertThat(first.path("halted").asBoolean()).isFalse();
    assertThat(first.path("outputs").path("pii_guard.output").asText()).isEqualTo(
      "My email is ************"
    );
    assertThat(first.has("finish_reason")).isFalse();
    assertThat(first.has("error")).isFalse();
    var second = steps.get(1);
    assertThat(second.path("halted").asBoolean()).isTrue();
    assertThat(second.path("finish_reason").asText()).isEqualTo("guard_blocked");
    assertThat(second.has("next_step")).isFalse();
    assertThat(json.path("usage").path("prompt_tokens").asInt()).isEqualTo(12);
    assertThat(json.path("choices").get(0).path("message").path("content").asText()).isEqualTo(
      "Hello"
    );
  }

  @Test
  void an_untraced_run_has_no_gravitee_trace() {
    var acc = new SequenceAccumulator();
    acc.add(TokenMessage.contentDelta("Hello"));
    acc.add(done());

    var json = ChatCompletionsFormatter.buildChatResponse("llm", acc, Map.of());

    assertThat(json.has("gravitee_trace")).isFalse();
  }

  @Test
  void streamed_chunks_never_carry_trace_events() {
    var tokens = Flowable.just(
      TokenMessage.stepTraced(guard()),
      TokenMessage.contentDelta("Hi"),
      done()
    );

    var events = ChatCompletionsFormatter.chatStreamEvents(
      tokens,
      "pipeline:guarded",
      false,
      t -> {}
    )
      .toList()
      .blockingGet();

    assertThat(events).noneMatch(
      e -> e.data().contains("pii_guard") || e.data().contains("gravitee_trace")
    );
    assertThat(events).anyMatch(e -> e.data().contains("\"Hi\""));
  }
}
