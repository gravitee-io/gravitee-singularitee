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
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import io.gravitee.singularitee.workspace.config.VllmConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace mapping of a vLLM model's default LoRA adapter: the base comes from one repository, the adapter
 * from another ({@code lora_repo}), or from the model's own.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmLoraYamlTest {

  private static VllmConfig load(Path tmp, String... vllmEntries) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    var yaml = new StringBuilder(
      """
      workspace:
        name: test
        models:
          - id: llm
            name: Qwen/Qwen3-14B
            type: vllm
            vllm:
              enable_lora: true
      """
    );
    for (String entry : vllmEntries) {
      yaml.append("        ").append(entry).append('\n');
    }
    Files.writeString(workspace, yaml.toString());
    var result = YamlWorkspaceLoader.load(workspace, null, TestStepPlugins.codecs());
    return result.models().get(0).vllmConfig();
  }

  @Test
  void the_adapter_keys_reach_the_config(@TempDir Path tmp) throws IOException {
    var config = load(
      tmp,
      "max_lora_rank: 16",
      "lora_repo: gravitee-io/Qwen3-14B-HITLead-loRa",
      "lora_path: adapter",
      "lora_name: sft16"
    );

    assertThat(config.hasDefaultLora()).isTrue();
    assertThat(config.loraRepo()).isEqualTo("gravitee-io/Qwen3-14B-HITLead-loRa");
    assertThat(config.loraPath()).isEqualTo("adapter");
    assertThat(config.loraName()).isEqualTo("sft16");
    assertThat(config.maxLoraRank()).isEqualTo(16);
  }

  @Test
  void without_lora_path_there_is_no_default_adapter(@TempDir Path tmp) throws IOException {
    var config = load(tmp, "max_lora_rank: 16");

    assertThat(config.hasDefaultLora()).isFalse();
    assertThat(config.loraRepo()).isEmpty();
    assertThat(config.loraName()).isEmpty();
  }
}
