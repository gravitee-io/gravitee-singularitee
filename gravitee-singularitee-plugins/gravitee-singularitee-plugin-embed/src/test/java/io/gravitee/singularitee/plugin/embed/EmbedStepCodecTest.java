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
package io.gravitee.singularitee.plugin.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbedStepCodecTest {

  private final EmbedStepCodec codec = new EmbedStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_block() {
    var cfg = codec.parse(
      "vec",
      Map.of("model_id", "embedder", "input_field", "prompt", "output_field", "vec.out"),
      ctx
    );
    assertThat(cfg.modelId()).isEqualTo("embedder");
    assertThat(cfg.inputField()).isEqualTo("prompt");
    assertThat(cfg.outputField()).isEqualTo("vec.out");
  }

  @Test
  void missing_keys_default_to_blank() {
    var cfg = codec.parse("vec", Map.of("model_id", "embedder"), ctx);
    assertThat(cfg.inputField()).isEmpty();
    assertThat(cfg.outputField()).isEmpty();
  }

  @Test
  void invalid_shape_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("vec", Map.of("model_id", Map.of("nested", 1)), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("vec");
  }
}
