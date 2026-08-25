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
package io.gravitee.singularitee.plugin.subpipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SubPipelineCallbacks;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SubPipelineStepCodecTest {

  private final SubPipelineStepCodec codec = new SubPipelineStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(null, null, null, null);

  @Test
  void parses_the_yaml_block_as_a_workspace_writes_it() {
    var cfg = codec.parse(
      "summarize",
      Map.of(
        "pipeline_id",
        "summary",
        "input_field",
        "generate.output",
        "output_field",
        "summarize.result",
        "server",
        "worker",
        "system_prompt",
        "Summarize.",
        "forward_messages",
        true,
        "unknown_key",
        "ignored"
      ),
      ctx
    );

    assertThat(cfg.pipelineId()).isEqualTo("summary");
    assertThat(cfg.inputField()).isEqualTo("generate.output");
    assertThat(cfg.outputField()).isEqualTo("summarize.result");
    assertThat(cfg.remoteId()).isEqualTo("worker");
    assertThat(cfg.systemPrompt()).isEqualTo("Summarize.");
    assertThat(cfg.forwardMessages()).isTrue();
  }

  @Test
  void defaults_are_blank_and_local() {
    var cfg = codec.parse("s", Map.of("pipeline_id", "p"), ctx);

    assertThat(cfg.inputField()).isEmpty();
    assertThat(cfg.outputField()).isEmpty();
    assertThat(cfg.remoteId()).isEmpty();
    assertThat(cfg.systemPrompt()).isEmpty();
    assertThat(cfg.forwardMessages()).isFalse();
  }

  @Test
  void missing_pipeline_id_names_the_step() {
    assertThatThrownBy(() -> codec.parse("summarize", Map.of("input_field", "x"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("summarize")
      .hasMessageContaining("pipeline_id");
  }

  @Test
  void plugin_declares_the_step_type_and_codec() {
    var plugin = new SubPipelineStepPlugin();
    assertThat(plugin.type()).isEqualTo(StepTypes.SUB_PIPELINE);
    assertThat(plugin.codec()).isInstanceOf(SubPipelineStepCodec.class);
  }
}
