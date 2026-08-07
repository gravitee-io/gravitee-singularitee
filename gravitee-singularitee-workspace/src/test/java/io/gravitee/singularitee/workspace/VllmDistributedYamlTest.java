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

import io.gravitee.singularitee.workspace.config.VllmConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace mapping of the vLLM multi-GPU topology.
 *
 * <p>These settings used to be reachable only through JVM system properties
 * read inside the inference library, which meant a workspace could not describe
 * the topology a model needs — a problem for any checkpoint too large for one
 * card. They are now ordinary config, and "unset" has to stay genuinely unset
 * so the server can layer its own defaults underneath.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmDistributedYamlTest {

  private static VllmConfig load(Path tmp, String... vllmEntries) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    var yaml = new StringBuilder(
      """
      workspace:
        name: test
        models:
          - id: llm
            name: Qwen/Qwen3-30B-A3B
            type: vllm
            vllm:
              dtype: auto
      """
    );
    for (String entry : vllmEntries) {
      // 8 spaces: nested under `vllm:`, matching `dtype` above. Anything
      // shallower lands on the model and is silently dropped as an unknown key.
      yaml.append("        ").append(entry).append('\n');
    }
    Files.writeString(workspace, yaml.toString());
    var result = YamlWorkspaceLoader.load(workspace);
    assertThat(result.models()).hasSize(1);
    return result.models().get(0).vllmConfig();
  }

  @Test
  void the_topology_reaches_the_proto(@TempDir Path tmp) throws IOException {
    var config = load(
      tmp,
      "tensor_parallel_size: 4",
      "pipeline_parallel_size: 2",
      "distributed_executor_backend: ray"
    );

    assertThat(config.tensorParallelSize()).isEqualTo(4);
    assertThat(config.pipelineParallelSize()).isEqualTo(2);
    assertThat(config.distributedExecutorBackend()).isEqualTo("ray");
  }

  @Test
  void tensor_parallelism_alone_is_enough(@TempDir Path tmp) throws IOException {
    // The common case: shard across the GPUs of one box, no pipeline stages
    // and no explicit executor.
    var config = load(tmp, "tensor_parallel_size: 8");

    assertThat(config.tensorParallelSize()).isEqualTo(8);
    assertThat(config.pipelineParallelSize()).isZero();
    assertThat(config.distributedExecutorBackend()).isEmpty();
  }

  @Test
  void unset_stays_unset(@TempDir Path tmp) throws IOException {
    // Zero and empty are what the factory tests to decide whether the
    // server-wide default applies — if the loader invented a 1 here, a
    // deployment-level GRAVITEE_AI_VLLM_TENSORPARALLELSIZE could never win.
    var config = load(tmp);

    assertThat(config.tensorParallelSize()).isZero();
    assertThat(config.pipelineParallelSize()).isZero();
    assertThat(config.distributedExecutorBackend()).isEmpty();
  }
}
