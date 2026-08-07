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
package io.gravitee.singularitee.inference.llama.cpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.llama.cpp.LlamaException;
import org.junit.jupiter.api.Test;

/**
 * Per-sequence to total context conversion.
 *
 * <p>Three components have to agree on what a configured {@code nCtx} means: the memory estimator
 * budgets VRAM for {@code nCtx * nSeqMax}, llama.cpp allocates {@code n_ctx} as one shared pool, and
 * admission divides it back by {@code nSeqMax}. These tests pin the conversion that keeps them in
 * step — the round-trip property is the one that matters: what a slot is given must equal what was
 * configured.
 *
 * <p>No model or native libraries required.
 *
 * @author GraviteeSource Team
 */
class ModelTotalContextTest {

  /** What {@code Model.perSequenceContext()} computes, mirrored here to assert the round-trip. */
  private static int perSequence(int total, int nSeqMax) {
    return nSeqMax > 1 ? total / nSeqMax : total;
  }

  @Test
  void a_slot_receives_exactly_the_configured_per_sequence_context() {
    for (int nSeqMax : new int[] { 1, 2, 4, 8, 16 }) {
      for (int perSeq : new int[] { 512, 4096, 8192, 32768 }) {
        int total = Model.totalContext(perSeq, nSeqMax);
        assertThat(perSequence(total, nSeqMax))
          .as("perSeq=%d nSeqMax=%d", perSeq, nSeqMax)
          .isEqualTo(perSeq);
      }
    }
  }

  @Test
  void the_default_pairing_no_longer_starves_a_slot() {
    // 4096 per sequence over 8 slots: 32768 total, and each slot keeps its full 4096 —
    // previously this allocated 4096 total and handed each request 512.
    int total = Model.totalContext(4096, 8);

    assertThat(total).isEqualTo(32768);
    assertThat(perSequence(total, 8)).isEqualTo(4096);
  }

  @Test
  void the_total_matches_what_the_memory_estimator_budgets() {
    // LlamaMemoryEstimator computes totalCtx = nCtx * max(1, nSeqMax); the allocation must agree,
    // or the VRAM pre-flight check is wrong by a factor of nSeqMax.
    assertThat(Model.totalContext(8192, 4)).isEqualTo(8192 * Math.max(1, 4));
    assertThat(Model.totalContext(8192, 0)).isEqualTo(8192 * Math.max(1, 0));
  }

  @Test
  void zero_defers_to_the_models_trained_context() {
    assertThat(Model.totalContext(0, 8)).isZero();
    assertThat(Model.totalContext(0, 0)).isZero();
  }

  @Test
  void unset_sequence_count_is_treated_as_one() {
    assertThat(Model.totalContext(4096, 0)).isEqualTo(4096);
    assertThat(Model.totalContext(4096, 1)).isEqualTo(4096);
  }

  @Test
  void overflow_fails_loudly_rather_than_wrapping_negative() {
    assertThatThrownBy(() -> Model.totalContext(Integer.MAX_VALUE, 2))
      .isInstanceOf(LlamaException.class)
      .hasMessageContaining("overflows");

    // Just under the boundary still works.
    assertThat(Model.totalContext(Integer.MAX_VALUE / 2, 2)).isPositive();
  }
}
