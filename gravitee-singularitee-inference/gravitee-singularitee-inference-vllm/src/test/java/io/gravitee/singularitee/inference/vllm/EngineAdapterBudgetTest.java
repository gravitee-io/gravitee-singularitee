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
import org.junit.jupiter.api.Test;

/**
 * The one part of the VRAM pre-flight that is not approximate: whether the
 * weights alone overflow {@code gpu_memory_utilization}'s budget.
 *
 * <p>Getting this wrong in either direction is costly. Too eager, and a model
 * that would have loaded is refused under {@code memory_check: warn}, whose
 * whole contract is to proceed on a tight estimate. Too shy, and the server
 * proceeds into vLLM's "No available memory for the cache blocks" — an error
 * that names neither the budget nor the setting behind it.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class EngineAdapterBudgetTest {

  /** The real numbers from Qwen3-4B-AWQ at gpu_memory_utilization 0.35 on an 8 GB card. */
  @Test
  void weights_that_do_not_fit_the_budget_are_certain_failure() {
    MemoryEstimate estimate = estimate(2.39, 6.98);

    assertThat(EngineAdapter.weightsExceedBudget(2.50, estimate)).isTrue();
  }

  @Test
  void weights_that_fit_leave_the_verdict_to_the_approximate_estimate() {
    // Same card at 0.85: the weights fit, the KV cache may not. That is exactly
    // the "tight estimate" case WARN exists for, so this must not fire.
    MemoryEstimate estimate = estimate(5.81, 6.98);

    assertThat(EngineAdapter.weightsExceedBudget(2.50, estimate)).isFalse();
  }

  @Test
  void weights_exactly_at_the_budget_are_not_treated_as_overflow() {
    assertThat(EngineAdapter.weightsExceedBudget(2.39, estimate(2.39, 6.98))).isFalse();
  }

  @Test
  void an_unknown_estimate_never_fires() {
    // unknown() zeroes every figure; comparing against it would refuse every
    // model on any machine where the GPU could not be queried.
    assertThat(EngineAdapter.weightsExceedBudget(2.50, MemoryEstimate.unknown())).isFalse();
  }

  @Test
  void an_unreadable_weight_size_never_fires() {
    // Shape introspection can leave totalParams at 0; that is "unknown", not
    // "weightless", and must not be read as fitting-or-failing either way.
    assertThat(EngineAdapter.weightsExceedBudget(0.0, estimate(2.39, 6.98))).isFalse();
  }

  private static MemoryEstimate estimate(double usableGb, double requiredGb) {
    return new MemoryEstimate(
      7.60,
      usableGb,
      requiredGb,
      false,
      "Try gpu_memory_utilization=0.95.",
      true
    );
  }
}
