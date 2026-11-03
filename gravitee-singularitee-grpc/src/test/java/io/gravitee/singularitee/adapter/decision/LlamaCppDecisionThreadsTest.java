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
package io.gravitee.singularitee.adapter.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.llama.cpp.decision.DecisionModelConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppDecisionConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The thread counts a {@code llama_cpp_decision} block hands its backend: an unset count stays
 * {@code null} so each decider keeps llama.cpp's default, a set one passes through.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class LlamaCppDecisionThreadsTest {

  private static DecisionModelConfig backend(int nThreads, int nThreadsBatch) {
    var cfg = new LlamaCppDecisionConfig(
      0,
      0,
      0,
      1,
      0,
      nThreads,
      nThreadsBatch,
      null,
      false,
      null,
      false,
      null,
      null,
      null,
      null,
      null
    );
    return LlamaCppDecisionFactory.backendConfig(cfg, null, Path.of("m.gguf"), null);
  }

  @Test
  void set_counts_pass_through() {
    DecisionModelConfig cfg = backend(6, 8);

    assertThat(cfg.nThreads()).isEqualTo(6);
    assertThat(cfg.nThreadsBatch()).isEqualTo(8);
  }

  @Test
  void each_count_is_read_on_its_own() {
    DecisionModelConfig cfg = backend(0, 8);

    assertThat(cfg.nThreads()).isNull();
    assertThat(cfg.nThreadsBatch()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, -1 })
  void zero_or_negative_keeps_the_default(int count) {
    DecisionModelConfig cfg = backend(count, count);

    assertThat(cfg.nThreads()).isNull();
    assertThat(cfg.nThreadsBatch()).isNull();
  }
}
