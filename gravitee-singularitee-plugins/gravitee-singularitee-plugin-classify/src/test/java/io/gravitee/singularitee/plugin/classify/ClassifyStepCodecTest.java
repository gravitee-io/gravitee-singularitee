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
package io.gravitee.singularitee.plugin.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifyStepCodecTest {

  private final ClassifyStepCodec codec = new ClassifyStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_block() {
    var cfg = codec.parse(
      "tox",
      Map.of(
        "model_id",
        "toxicity",
        "input_field",
        "prompt",
        "output_field",
        "tox.label",
        "threshold",
        0.7
      ),
      ctx
    );
    assertThat(cfg.modelId()).isEqualTo("toxicity");
    assertThat(cfg.inputField()).isEqualTo("prompt");
    assertThat(cfg.outputField()).isEqualTo("tox.label");
    assertThat(cfg.threshold()).isEqualTo(0.7f);
  }

  @Test
  void missing_keys_default() {
    var cfg = codec.parse("tox", Map.of("model_id", "toxicity"), ctx);
    assertThat(cfg.inputField()).isEmpty();
    assertThat(cfg.outputField()).isEmpty();
    assertThat(cfg.threshold()).isZero();
  }

  @Test
  void negative_threshold_is_dropped() {
    var cfg = codec.parse("tox", Map.of("model_id", "t", "threshold", -1), ctx);
    assertThat(cfg.threshold()).isZero();
  }

  @Test
  void invalid_shape_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("tox", Map.of("threshold", "not-a-number"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("tox");
  }
}
