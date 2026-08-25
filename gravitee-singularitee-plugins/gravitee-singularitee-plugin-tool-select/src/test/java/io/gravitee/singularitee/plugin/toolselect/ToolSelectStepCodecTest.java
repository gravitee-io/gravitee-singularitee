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
package io.gravitee.singularitee.plugin.toolselect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSelectStepCodecTest {

  private final ToolSelectStepCodec codec = new ToolSelectStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_the_full_yaml_shape() {
    var cfg = codec.parse(
      "select",
      Map.of(
        "model_id",
        "triage",
        "input_field",
        "custom.field",
        "batch_size",
        6,
        "threshold",
        0.45,
        "label_template",
        "{{ tool.name }}",
        "always_include",
        List.of("read", "write"),
        "trim_descriptions",
        true,
        "description_template",
        "{{ tool.description }}"
      ),
      ctx
    );

    assertThat(cfg.modelId()).isEqualTo("triage");
    assertThat(cfg.inputField()).isEqualTo("custom.field");
    assertThat(cfg.batchSize()).isEqualTo(6);
    assertThat(cfg.threshold()).isEqualTo(0.45f);
    assertThat(cfg.labelTemplate()).isEqualTo("{{ tool.name }}");
    assertThat(cfg.alwaysInclude()).containsExactly("read", "write");
    assertThat(cfg.trimDescriptions()).isTrue();
    assertThat(cfg.descriptionTemplate()).isEqualTo("{{ tool.description }}");
  }

  @Test
  void empty_block_yields_defaults() {
    var cfg = codec.parse("select", Map.of(), ctx);

    assertThat(cfg.modelId()).isEmpty();
    assertThat(cfg.inputField()).isEmpty();
    assertThat(cfg.batchSize()).isZero();
    assertThat(cfg.threshold()).isZero();
    assertThat(cfg.labelTemplate()).isEmpty();
    assertThat(cfg.alwaysInclude()).isEmpty();
    assertThat(cfg.trimDescriptions()).isFalse();
    assertThat(cfg.descriptionTemplate()).isEmpty();
  }

  @Test
  void negative_numbers_fall_back_to_the_default() {
    var cfg = codec.parse("select", Map.of("batch_size", -1, "threshold", -0.5), ctx);
    assertThat(cfg.batchSize()).isZero();
    assertThat(cfg.threshold()).isZero();
  }

  @Test
  void unknown_keys_are_ignored() {
    var cfg = codec.parse("select", Map.of("model_id", "triage", "bogus", 1), ctx);
    assertThat(cfg.modelId()).isEqualTo("triage");
  }

  @Test
  void invalid_shape_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("select", Map.of("always_include", "not-a-list"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("select");
  }
}
