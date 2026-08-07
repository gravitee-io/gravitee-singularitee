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

import org.junit.jupiter.api.Test;

/**
 * Completion-budget resolution for a vLLM request.
 *
 * <p>This exists because of a genuinely nasty default: vLLM's
 * {@code SamplingParams.max_tokens} is <strong>16</strong>. A client that does
 * not name a limit therefore gets 16 tokens, and on a thinking model all 16 are
 * consumed inside the reasoning block — so the reply is empty with
 * {@code finish_reason=length}, which reads as the model being broken rather
 * than a default being applied. llama.cpp treats unset as "the rest of the
 * context window", so the same workspace behaved completely differently on the
 * two backends.
 *
 * <p>The resolution is mirrored here rather than reaching into the adapter,
 * which would need a live CPython engine.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class MaxTokensDefaultTest {

  private static final int DEFAULT_MAX_TOKENS = 4096;
  private static final int APPROX_CHARS_PER_TOKEN = 4;

  /** Mirrors {@code EngineAdapter.resolveMaxTokens}. */
  private static int resolve(Integer requested, int contextWindow, String prompt) {
    int available;
    if (contextWindow <= 0) {
      available = DEFAULT_MAX_TOKENS;
    } else {
      int promptEstimate = prompt == null ? 0 : prompt.length() / APPROX_CHARS_PER_TOKEN;
      int left = contextWindow - promptEstimate;
      available = left > 0 ? left : DEFAULT_MAX_TOKENS;
    }
    if (requested == null || requested <= 0) {
      return available;
    }
    return Math.min(requested, available);
  }

  @Test
  void unset_means_the_rest_of_the_context_window_not_sixteen() {
    int budget = resolve(null, 40960, "Hi can you help ?");

    // The whole point: anything but vLLM's 16.
    assertThat(budget).isGreaterThan(16);
    assertThat(budget).isCloseTo(40960, org.assertj.core.data.Offset.offset(16));
  }

  @Test
  void an_explicit_limit_is_honoured() {
    assertThat(resolve(250, 40960, "hello")).isEqualTo(250);
  }

  @Test
  void an_explicit_limit_is_clamped_to_the_window() {
    // Asking for more than the model can hold is a client error we absorb
    // rather than pass through to a vLLM rejection.
    assertThat(resolve(999_999, 4096, "hello")).isLessThanOrEqualTo(4096);
  }

  @Test
  void a_long_prompt_leaves_less_room() {
    String longPrompt = "x".repeat(8000); // ~2000 tokens
    int budget = resolve(null, 4096, longPrompt);

    assertThat(budget).isLessThan(4096);
    assertThat(budget).isGreaterThan(16);
  }

  @Test
  void an_unknown_window_still_beats_vllms_default() {
    // maxModelLen() can fail; falling back to 16 would reintroduce the bug.
    assertThat(resolve(null, 0, "hello")).isEqualTo(DEFAULT_MAX_TOKENS);
  }

  @Test
  void a_prompt_that_fills_the_window_does_not_yield_a_negative_budget() {
    // Negative or zero would be passed to vLLM verbatim and rejected; the
    // fallback keeps the request serviceable and lets vLLM enforce the truth.
    assertThat(resolve(null, 100, "x".repeat(4000))).isEqualTo(DEFAULT_MAX_TOKENS);
  }

  @Test
  void zero_and_negative_requests_are_treated_as_unset() {
    assertThat(resolve(0, 40960, "hi")).isGreaterThan(16);
    assertThat(resolve(-1, 40960, "hi")).isGreaterThan(16);
  }
}
