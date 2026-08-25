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
package io.gravitee.singularitee.plugin.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopStepCodecTest {

  private final LoopStepCodec codec = new LoopStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_full_block() {
    var cfg = codec.parse(
      "gate",
      Map.of(
        "loopback_step",
        "generate",
        "next_step",
        "done",
        "fallback_step",
        "fallback",
        "max_iterations",
        3,
        "condition",
        Map.of("type", "equals", "input_field", "verify.output", "match_value", "YES"),
        "loopback_message",
        Map.of("content", "Try again: {{ verify.output }}"),
        "retry_sampling_params",
        Map.of("temperature", 0.2, "max_tokens", 64)
      ),
      ctx
    );

    assertThat(cfg.targetStepId()).isEqualTo("generate");
    assertThat(cfg.nextStepId()).isEqualTo("done");
    assertThat(cfg.fallbackStepId()).isEqualTo("fallback");
    assertThat(cfg.maxIterations()).isEqualTo(3);
    assertThat(cfg.condition().kind()).isEqualTo(ConditionKind.EQUALS);
    assertThat(cfg.condition().inputField()).isEqualTo("verify.output");
    assertThat(cfg.condition().matchValue()).isEqualTo("YES");
    assertThat(cfg.loopbackMessage().role()).isEqualTo("user");
    assertThat(cfg.loopbackMessage().content()).isEqualTo("Try again: {{ verify.output }}");
    assertThat(cfg.retrySamplingParams().getTemperature()).isEqualTo(0.2f);
    assertThat(cfg.retrySamplingParams().getMaxTokens()).isEqualTo(64);
    assertThat(cfg.branchTargets()).containsExactly("generate", "done", "fallback");
  }

  @Test
  void optional_blocks_default() {
    var cfg = codec.parse("gate", Map.of("loopback_step", "generate", "max_iterations", 2), ctx);

    assertThat(cfg.condition()).isNull();
    assertThat(cfg.loopbackMessage()).isNull();
    assertThat(cfg.retrySamplingParams()).isNull();
    assertThat(cfg.nextStepId()).isEmpty();
    assertThat(cfg.branchTargets()).containsExactly("generate");
  }

  @Test
  void boolean_match_value_maps_to_yes_no() {
    var cfg = codec.parse(
      "gate",
      Map.of(
        "loopback_step",
        "g",
        "max_iterations",
        1,
        "condition",
        Map.of("type", "equals", "input_field", "v", "match_value", false)
      ),
      ctx
    );
    assertThat(cfg.condition().matchValue()).isEqualTo("NO");
  }

  @Test
  void non_positive_max_iterations_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("gate", Map.of("loopback_step", "generate"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gate")
      .hasMessageContaining("max_iterations");
  }

  @Test
  void unknown_condition_type_fails_with_step_id() {
    assertThatThrownBy(() ->
      codec.parse(
        "gate",
        Map.of("max_iterations", 1, "condition", Map.of("type", "nope", "input_field", "v")),
        ctx
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gate")
      .hasMessageContaining("nope");
  }
}
