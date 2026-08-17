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
package io.gravitee.singularitee.inference.api.textgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InferencePerformance#minus(InferencePerformance)}: backends whose
 * perf counters accumulate over the context lifetime need the sequence-start snapshot
 * subtracted to yield per-request figures, clamped at zero because a backend may
 * reset its counters between reads.
 */
class InferencePerformanceTest {

  @Test
  void minus_subtracts_the_baseline_from_the_accumulating_counters() {
    var current = new InferencePerformance(1_000L, 50L, 300L, 900L, 40, 120, 7, 60L, 130);
    var baseline = new InferencePerformance(500L, 50L, 100L, 400L, 10, 20, 3, 15L, 25);

    var delta = current.minus(baseline);

    // accumulating counters are baselined
    assertThat(delta.promptEvalTimeMs()).isEqualTo(200L);
    assertThat(delta.evalTimeMs()).isEqualTo(500L);
    assertThat(delta.promptTokensEvaluated()).isEqualTo(30);
    assertThat(delta.tokensGenerated()).isEqualTo(100);
    assertThat(delta.samplingTimeMs()).isEqualTo(45L);
    assertThat(delta.sampleCount()).isEqualTo(105);

    // non-accumulating fields are carried over from the current snapshot untouched
    assertThat(delta.startTimeMs()).isEqualTo(1_000L);
    assertThat(delta.loadTimeMs()).isEqualTo(50L);
    assertThat(delta.tokensReused()).isEqualTo(7);
  }

  @Test
  void minus_clamps_to_zero_when_the_baseline_exceeds_current_values() {
    // A backend resetting its counters makes the baseline larger than the read.
    var current = new InferencePerformance(1_000L, 50L, 100L, 200L, 5, 10, 2, 8L, 12);
    var baseline = new InferencePerformance(500L, 50L, 999L, 999L, 500, 500, 9, 500L, 500);

    var delta = current.minus(baseline);

    assertThat(delta.promptEvalTimeMs()).isZero();
    assertThat(delta.evalTimeMs()).isZero();
    assertThat(delta.promptTokensEvaluated()).isZero();
    assertThat(delta.tokensGenerated()).isZero();
    assertThat(delta.samplingTimeMs()).isZero();
    assertThat(delta.sampleCount()).isZero();
    // clamping keeps the record constructible: no negative-time IllegalArgumentException
    assertThat(delta.tokensPerSecond()).isZero();
  }

  @Test
  void minus_null_baseline_returns_the_same_instance() {
    var current = new InferencePerformance(1_000L, 50L, 300L, 900L, 40, 120, 7, 60L, 130);

    assertThat(current.minus(null)).isSameAs(current);
  }

  @Test
  void minus_zero_baseline_is_the_identity_on_the_baselined_fields() {
    var current = new InferencePerformance(1_000L, 50L, 300L, 900L, 40, 120, 7, 60L, 130);
    var zero = new InferencePerformance(0L, 0L, 0L, 0L, 0, 0, 0, 0L, 0);

    assertThat(current.minus(zero)).isEqualTo(current);
  }
}
