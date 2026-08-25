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
package io.gravitee.singularitee.plugin.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoStepCodecTest {

  private final TodoStepCodec codec = new TodoStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_handled_step_and_exposes_it_as_a_branch_target() {
    var cfg = codec.parse("track", Map.of("handled_step", "work", "unknown_key", 1), ctx);

    assertThat(cfg.handledStepId()).isEqualTo("work");
    assertThat(cfg.branchTargets()).containsExactly("work");
  }

  @Test
  void empty_block_means_the_next_step_edge() {
    var cfg = codec.parse("track", Map.of(), ctx);

    assertThat(cfg.handledStepId()).isEmpty();
    assertThat(cfg.branchTargets()).isEmpty();
  }

  @Test
  void invalid_block_names_the_step() {
    assertThatThrownBy(() -> codec.parse("track", Map.of("handled_step", Map.of("a", 1)), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("track");
  }

  @Test
  void plugin_declares_the_step_type_and_codec() {
    var plugin = new TodoStepPlugin();
    assertThat(plugin.type()).isEqualTo(StepTypes.TODO);
    assertThat(plugin.codec()).isInstanceOf(TodoStepCodec.class);
  }
}
