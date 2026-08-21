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
package io.gravitee.singularitee.inference.vllm;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VllmMemoryEstimator}.
 *
 * <p>The estimator calls {@code GpuMemoryQuery.query()}, which needs a live
 * CPython and GPU runtime. These tests cover input validation (invalid
 * parameters yield {@code unknown()}), the pure-logic path that never touches
 * CPython. The estimation math itself is validated by integration tests on a
 * real GPU.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmMemoryEstimatorTest {

  /** Parameter count of an 8B checkpoint. */
  private static final long TOTAL_PARAMS_8B = 8_030_261_248L;

  /** Parameter count of Qwen3-0.6B. */
  private static final long TOTAL_PARAMS_QWEN3_06B = 751_632_384L;

  @Nested
  @DisplayName("Returns unknown() for invalid inputs")
  class InvalidInputs {

    @Test
    @DisplayName("totalParams <= 0 returns unknown()")
    void zero_total_params_returns_unknown() {
      MemoryEstimate result = VllmMemoryEstimator.estimate(0, 2, 32, 8, 128, 4096, 8, 0.9, false);
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("negative totalParams returns unknown()")
    void negative_total_params_returns_unknown() {
      MemoryEstimate result = VllmMemoryEstimator.estimate(-1, 2, 32, 8, 128, 4096, 8, 0.9, false);
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("bitsPerParam <= 0 returns unknown()")
    void zero_bytes_per_param_returns_unknown() {
      MemoryEstimate result = VllmMemoryEstimator.estimate(
        TOTAL_PARAMS_8B,
        0,
        32,
        8,
        128,
        4096,
        8,
        0.9,
        false
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("negative bitsPerParam returns unknown()")
    void negative_bytes_per_param_returns_unknown() {
      MemoryEstimate result = VllmMemoryEstimator.estimate(
        TOTAL_PARAMS_8B,
        -1,
        32,
        8,
        128,
        4096,
        8,
        0.9,
        false
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("both invalid returns unknown()")
    void both_invalid_returns_unknown() {
      MemoryEstimate result = VllmMemoryEstimator.estimate(0, 0, 0, 0, 0, 0, 8, 0.9, false);
      assertThat(result.isUnknown()).isTrue();
    }
  }
}
