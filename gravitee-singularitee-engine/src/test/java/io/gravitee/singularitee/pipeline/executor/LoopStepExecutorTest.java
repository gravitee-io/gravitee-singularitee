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
package io.gravitee.singularitee.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.BreakCondition;
import io.gravitee.singularitee.protocol.LoopStepConfig;
import io.gravitee.singularitee.protocol.SamplingParams;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoopStepExecutor}'s retry-edge behaviour: the
 * {@code retry_sampling_params} override must be installed only when branching
 * back, and cleared on both exits (condition met, max-iterations fallback) so
 * it never leaks past the loop.
 */
class LoopStepExecutorTest {

  private static final SamplingParams RETRY_SP = SamplingParams.newBuilder()
    .setTemperature(0.2f)
    .build();

  private final LoopStepExecutor executor = new LoopStepExecutor(new JinjaRenderer());

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
    return ctx;
  }

  private static LoopStepConfig config(int maxIterations) {
    return LoopStepConfig.newBuilder()
      .setTargetStepId("generate")
      .setNextStepId("done")
      .setFallbackStepId("fallback")
      .setCondition(BreakCondition.BREAK_CONDITION_EQUALS)
      .setInputField("generate.tool_parse_failed")
      .setMatchValue("false")
      .setMaxIterations(maxIterations)
      .setRetrySamplingParams(RETRY_SP)
      .build();
  }

  @Test
  void retry_edge_installs_sampling_override() {
    var pctx = pctx();
    pctx.set("generate.tool_parse_failed", "true"); // condition not met → loop back

    String next = executor.execute("gate", config(3), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("generate");
    assertThat(pctx.retrySamplingParams()).isNotNull();
    assertThat(pctx.retrySamplingParams().getTemperature()).isEqualTo(0.2f);
  }

  @Test
  void condition_met_exit_clears_override() {
    var pctx = pctx();
    pctx.setRetrySamplingParams(RETRY_SP); // installed by a previous iteration
    pctx.set("generate.tool_parse_failed", "false"); // condition met → exit

    String next = executor.execute("gate", config(3), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("done");
    assertThat(pctx.retrySamplingParams()).isNull();
  }

  @Test
  void max_iterations_fallback_clears_override() {
    var pctx = pctx();
    pctx.set("generate.tool_parse_failed", "true"); // never satisfied

    var ctx = stepContext(pctx);
    var cfg = config(1);
    String next = executor.execute("gate", cfg, ctx).blockingGet(); // iteration 1 == ceiling

    assertThat(next).isEqualTo("fallback");
    assertThat(pctx.retrySamplingParams()).isNull();
  }
}
