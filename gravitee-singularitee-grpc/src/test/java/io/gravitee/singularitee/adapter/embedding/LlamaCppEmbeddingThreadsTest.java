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
package io.gravitee.singularitee.adapter.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The thread counts the nested {@code llama_cpp:} block of a {@code llama_cpp_embedding} model hands
 * the engine. 0 means "keep llama.cpp's default", so an unset count must arrive as 0.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class LlamaCppEmbeddingThreadsTest {

  private static ModelConfig engine(LlamaCppConfig cfg) {
    return LlamaCppEmbeddingFactory.modelConfig(cfg, null, Path.of("m.gguf"));
  }

  @Test
  void set_counts_pass_through() {
    ModelConfig cfg = engine(
      LlamaCppConfig.newBuilder().setNThreads(6).setNThreadsBatch(8).build()
    );

    assertThat(cfg.nThreads()).isEqualTo(6);
    assertThat(cfg.nThreadsBatch()).isEqualTo(8);
  }

  @Test
  void unset_counts_stay_zero() {
    ModelConfig cfg = engine(LlamaCppConfig.getDefaultInstance());

    assertThat(cfg.nThreads()).isZero();
    assertThat(cfg.nThreadsBatch()).isZero();
  }
}
