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
package io.gravitee.singularitee.plugin.breakstep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.junit.jupiter.api.Test;

class BreakStepExecutorTest {

  private final BreakStepExecutor executor = new BreakStepExecutor();

  private static PipelineContext pctx() {
    return new PipelineContext(
      "hello",
      List.of(new ChatTurn(ChatRole.USER, "hello")),
      null,
      List.of(),
      null
    );
  }

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.stepScribe()).thenReturn(SpanScribe.NOOP);
    when(ctx.rxNextStep("halt")).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private static BreakStepConfig config() {
    return new BreakStepConfig(
      "guard.output",
      new StepCondition(ConditionKind.EQUALS, "guard.label", "toxic", 0)
    );
  }

  @Test
  void condition_met_halts_pipeline() {
    var pctx = pctx();
    pctx.set("guard.label", "toxic");

    var next = executor.execute("halt", config(), stepContext(pctx)).blockingGet();

    assertThat(next).isNull();
    assertThat(pctx.isHalted()).isTrue();
  }

  @Test
  void condition_not_met_proceeds() {
    var pctx = pctx();
    pctx.set("guard.label", "clean");

    var next = executor.execute("halt", config(), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("next");
    assertThat(pctx.isHalted()).isFalse();
  }

  @Test
  void no_condition_never_halts() {
    var pctx = pctx();
    var next = executor
      .execute("halt", new BreakStepConfig(null, null), stepContext(pctx))
      .blockingGet();
    assertThat(next).isEqualTo("next");
  }
}
