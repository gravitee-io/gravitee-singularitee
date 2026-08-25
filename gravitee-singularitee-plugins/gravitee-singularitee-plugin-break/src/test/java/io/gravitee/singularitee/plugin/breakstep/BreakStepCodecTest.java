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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BreakStepCodecTest {

  private final BreakStepCodec codec = new BreakStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_condition_block() {
    var cfg = codec.parse(
      "halt",
      Map.of(
        "output_field",
        "guard.output",
        "condition",
        Map.of("type", "label_equals", "input_field", "guard.label", "match_value", "toxic")
      ),
      ctx
    );

    assertThat(cfg.outputField()).isEqualTo("guard.output");
    assertThat(cfg.condition().kind()).isEqualTo(ConditionKind.LABEL_EQUALS);
    assertThat(cfg.condition().inputField()).isEqualTo("guard.label");
    assertThat(cfg.condition().matchValue()).isEqualTo("toxic");
  }

  @Test
  void boolean_match_value_maps_to_yes_no() {
    var cfg = codec.parse(
      "halt",
      Map.of("condition", Map.of("type", "equals", "input_field", "v", "match_value", true)),
      ctx
    );
    assertThat(cfg.condition().matchValue()).isEqualTo("YES");
  }

  @Test
  void score_condition_keeps_threshold() {
    var cfg = codec.parse(
      "halt",
      Map.of("condition", Map.of("type", "score_above", "input_field", "c", "threshold", 0.8)),
      ctx
    );
    assertThat(cfg.condition().kind()).isEqualTo(ConditionKind.SCORE_ABOVE);
    assertThat(cfg.condition().threshold()).isEqualTo(0.8f);
  }

  @Test
  void empty_block_has_no_condition() {
    var cfg = codec.parse("halt", Map.of(), ctx);
    assertThat(cfg.condition()).isNull();
    assertThat(cfg.outputField()).isEmpty();
  }

  @Test
  void unknown_condition_type_fails_with_step_id() {
    assertThatThrownBy(() ->
      codec.parse("halt", Map.of("condition", Map.of("type", "bogus", "input_field", "v")), ctx)
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("halt")
      .hasMessageContaining("bogus");
  }
}
