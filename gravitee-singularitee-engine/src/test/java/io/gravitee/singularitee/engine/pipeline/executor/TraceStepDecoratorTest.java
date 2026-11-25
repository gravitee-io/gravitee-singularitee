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
package io.gravitee.singularitee.engine.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepInvocation;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.core.Future;
import io.vertx.core.streams.WriteStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceStepDecoratorTest {

  private final TraceStepDecorator decorator = new TraceStepDecorator();
  private final List<InferResponse> events = new ArrayList<>();

  private static PipelineContext pctx(boolean traced) {
    var pctx = new PipelineContext(
      "hello",
      List.of(new ChatTurn(ChatRole.USER, "hello")),
      null,
      List.of(),
      null
    );
    if (traced) pctx.enableTrace();
    return pctx;
  }

  @SuppressWarnings("unchecked")
  private StepContext ctx(PipelineContext pctx) {
    WriteStream<InferResponse> stream = mock(WriteStream.class);
    when(stream.write(any())).thenAnswer(inv -> {
      events.add(inv.getArgument(0));
      return Future.succeededFuture();
    });
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.response()).thenReturn(stream);
    return ctx;
  }

  private static StepModel step(String id, String type, Object config) {
    return new StepModel(id, type, StepRole.STEP_ROLE_INTERNAL, config);
  }

  @Test
  void emits_nothing_when_trace_is_off() {
    var pctx = pctx(false);
    StepInvocation next = (s, c) -> Maybe.just("next");

    decorator.around(step("g", "guard", null), ctx(pctx), next).blockingGet();

    assertThat(events).isEmpty();
  }

  @Test
  void emits_the_written_keys_and_the_next_step() {
    var pctx = pctx(true);
    pctx.set("prompt.kept", "same");
    StepInvocation next = (s, c) -> {
      pctx.set("g.label", "PII");
      pctx.set("prompt.kept", "same");
      return Maybe.just("next");
    };
    ModelBoundConfig config = () -> "pii";

    var result = decorator.around(step("g", "guard", config), ctx(pctx), next).blockingGet();

    assertThat(result).isEqualTo("next");
    assertThat(events).hasSize(1);
    var event = events.getFirst();
    assertThat(event.getEventType()).isEqualTo(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE);
    var trace = event.getStepTrace();
    assertThat(trace.getStepId()).isEqualTo("g");
    assertThat(trace.getStepType()).isEqualTo("guard");
    assertThat(trace.getNextStep()).isEqualTo("next");
    assertThat(trace.getHalted()).isFalse();
    assertThat(trace.getModelId()).isEqualTo("pii");
    assertThat(trace.getOutputsMap()).containsExactly(java.util.Map.entry("g.label", "PII"));
    assertThat(trace.getDurationMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void reports_a_halt_with_its_finish_reason() {
    var pctx = pctx(true);
    StepInvocation next = (s, c) -> {
      pctx.set("g.output", "blocked");
      pctx.signalHalt("g.output", FinishReason.FINISH_REASON_GUARD_BLOCKED);
      return Maybe.empty();
    };

    decorator.around(step("g", "guard", null), ctx(pctx), next).blockingGet();

    var trace = events.getFirst().getStepTrace();
    assertThat(trace.getHalted()).isTrue();
    assertThat(trace.getFinishReason()).isEqualTo(FinishReason.FINISH_REASON_GUARD_BLOCKED);
    assertThat(trace.getNextStep()).isEmpty();
  }

  @Test
  void cuts_long_values() {
    var pctx = pctx(true);
    StepInvocation next = (s, c) -> {
      pctx.set("g.output", "x".repeat(10_000));
      return Maybe.just("next");
    };

    decorator.around(step("g", "guard", null), ctx(pctx), next).blockingGet();

    var value = events.getFirst().getStepTrace().getOutputsMap().get("g.output");
    assertThat(value)
      .hasSize(4096 + "...[truncated]".length())
      .endsWith("...[truncated]")
      .startsWith("xxxx");
  }

  @Test
  void reports_an_error() {
    var pctx = pctx(true);
    StepInvocation next = (s, c) -> Maybe.error(new IllegalStateException("model down"));

    assertThatThrownBy(() ->
      decorator.around(step("g", "guard", null), ctx(pctx), next).blockingGet()
    ).hasMessageContaining("model down");

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getStepTrace().getError()).isEqualTo("model down");
  }
}
