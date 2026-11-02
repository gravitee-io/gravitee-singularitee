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
import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppDecisionConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code n_threads} and {@code n_threads_batch}: named values pass through, unset stays zero. */
class LlamaThreadsYamlTest {

  private static ModelLoadRequest load(Path tmp, String type, String block) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
        workspace:
          name: test
          models:
            - id: model
              name: org/repo
              type: %s
        """.formatted(type) +
        block.indent(6)
    );
    return YamlWorkspaceLoader.load(workspace, null, TestStepPlugins.codecs()).models().getFirst();
  }

  @Test
  void llama_cpp_reads_both_counts(@TempDir Path tmp) throws IOException {
    LlamaCppConfig config = load(
      tmp,
      "llama_cpp",
      "llama_cpp:\n  path: m.gguf\n  n_threads: 2\n  n_threads_batch: 8\n"
    ).llamaCppConfig();

    assertThat(config.nThreads()).isEqualTo(2);
    assertThat(config.nThreadsBatch()).isEqualTo(8);
  }

  @Test
  void llama_cpp_leaves_unset_counts_at_zero(@TempDir Path tmp) throws IOException {
    LlamaCppConfig config = load(tmp, "llama_cpp", "llama_cpp:\n  path: m.gguf\n").llamaCppConfig();

    assertThat(config.nThreads()).isZero();
    assertThat(config.nThreadsBatch()).isZero();
  }

  @Test
  void llama_cpp_decision_reads_each_count_on_its_own(@TempDir Path tmp) throws IOException {
    LlamaCppDecisionConfig config = load(
      tmp,
      "llama_cpp_decision",
      "llama_cpp_decision:\n  path: m.gguf\n  n_threads_batch: 8\n"
    ).llamaCppDecision();

    assertThat(config.nThreads()).isZero();
    assertThat(config.nThreadsBatch()).isEqualTo(8);
    assertThat(config.withTemplate("x").nThreadsBatch()).isEqualTo(8);
  }

  @Test
  void llama_cpp_embedding_reads_both_counts(@TempDir Path tmp) throws IOException {
    var request = load(
      tmp,
      "llama_cpp_embedding",
      "llama_cpp_embedding:\n  llama_cpp:\n    path: m.gguf\n    n_threads: 2\n    n_threads_batch: 8\n"
    );

    assertThat(request.llamaCppEmbedding().llamaCppConfig().nThreads()).isEqualTo(2);
    assertThat(request.llamaCppEmbedding().llamaCppConfig().nThreadsBatch()).isEqualTo(8);
  }

  @Test
  void llama_cpp_reranker_reads_both_counts(@TempDir Path tmp) throws IOException {
    var request = load(
      tmp,
      "llama_cpp_reranker",
      "llama_cpp_reranker:\n  llama_cpp:\n    path: m.gguf\n    n_threads: 2\n    n_threads_batch: 8\n"
    );

    assertThat(request.llamaCppReranker().llamaCppConfig().nThreads()).isEqualTo(2);
    assertThat(request.llamaCppReranker().llamaCppConfig().nThreadsBatch()).isEqualTo(8);
  }
}
