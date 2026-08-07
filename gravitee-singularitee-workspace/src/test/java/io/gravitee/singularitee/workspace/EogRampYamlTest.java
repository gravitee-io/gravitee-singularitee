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

import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace mapping of the budget-aware EOG ramp. Absence must stay absent all the way to the
 * proto: the engine treats a non-positive start as "disabled", and that is what keeps a workspace
 * written before this feature bit-identical at inference time.
 */
class EogRampYamlTest {

  /** Assembled line by line: the YAML indentation is the point of the test, not incidental. */
  private static LlamaCppConfig load(Path tmp, String... llamaCppEntries) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    var yaml = new StringBuilder(
      """
      workspace:
        name: test
        models:
          - id: llm
            name: test/model
            type: llama_cpp
            llama_cpp:
              path: model.gguf
      """
    );
    for (String entry : llamaCppEntries) {
      yaml.append("        ").append(entry).append('\n');
    }
    Files.writeString(workspace, yaml.toString());
    var result = YamlWorkspaceLoader.load(workspace);
    assertThat(result.models()).hasSize(1);
    return result.models().get(0).llamaCppConfig();
  }

  @Test
  void both_knobs_reach_the_proto(@TempDir Path tmp) throws IOException {
    var config = load(tmp, "eog_ramp_start: 0.75", "eog_ramp_max_bias: 24.0");
    assertThat(config.eogRampStart()).isEqualTo(0.75f);
    assertThat(config.eogRampMaxBias()).isEqualTo(24.0f);
  }

  @Test
  void the_ramp_is_off_when_unset(@TempDir Path tmp) throws IOException {
    var config = load(tmp);
    assertThat(config.eogRampStart()).isZero();
    assertThat(config.eogRampMaxBias()).isZero();
  }

  @Test
  void the_start_alone_is_enough_to_enable_it(@TempDir Path tmp) throws IOException {
    // maxBias unset falls back to the engine default rather than disabling the ramp.
    var config = load(tmp, "eog_ramp_start: 0.9");
    assertThat(config.eogRampStart()).isEqualTo(0.9f);
    assertThat(config.eogRampMaxBias()).isZero();
  }
}
