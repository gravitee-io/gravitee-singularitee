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
package io.gravitee.singularitee.inference.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsufficientVramExceptionTest {

  @Test
  @DisplayName("exception message contains model ID and human-readable estimate")
  void message_contains_model_id_and_estimate() {
    MemoryEstimate estimate = new MemoryEstimate(8.0, 6.0, 22.0, false, "Too big.", true);
    InsufficientVramException exception = new InsufficientVramException("Qwen/Qwen3-32B", estimate);

    assertThat(exception.getMessage()).contains("Qwen/Qwen3-32B");
    assertThat(exception.getMessage()).contains("Insufficient VRAM");
    assertThat(exception.getMessage()).contains("22.00");
    assertThat(exception.getMessage()).contains("6.00");
    assertThat(exception.getMessage()).contains("does NOT fit");
  }

  @Test
  @DisplayName("estimate() accessor returns the original estimate")
  void estimate_accessor() {
    MemoryEstimate estimate = new MemoryEstimate(24.0, 20.0, 16.5, false, "Tight fit", false);
    InsufficientVramException exception = new InsufficientVramException(
      "meta-llama/Llama-3.1-70B",
      estimate
    );

    assertThat(exception.estimate()).isSameAs(estimate);
    assertThat(exception.estimate().requiredGb()).isEqualTo(16.5);
    assertThat(exception.estimate().usableGb()).isEqualTo(20.0);
    assertThat(exception.estimate().totalGb()).isEqualTo(24.0);
  }

  @Test
  @DisplayName("exception is a RuntimeException")
  void is_runtime_exception() {
    MemoryEstimate estimate = MemoryEstimate.unknown();
    InsufficientVramException exception = new InsufficientVramException("test-model", estimate);

    assertThat(exception).isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("can be thrown and caught with standard try/catch")
  void throwable_behavior() {
    MemoryEstimate estimate = new MemoryEstimate(8.0, 6.0, 32.0, false, "Way too big.", true);

    assertThatThrownBy(() -> {
      throw new InsufficientVramException("huge-model", estimate);
    })
      .isInstanceOf(InsufficientVramException.class)
      .hasMessageContaining("huge-model")
      .hasMessageContaining("Way too big.");
  }
}
