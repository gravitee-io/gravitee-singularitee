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
package io.gravitee.singularitee.adapter.textgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ordering contract for {@code specialTokenTexts()}.
 *
 * <p>{@code TextGenEngine} requires longest-first, and the reason is a real
 * failure rather than tidiness: neutralisation replaces these markers in order,
 * so if a short token is replaced first it can consume part of a longer one and
 * leave a fragment behind — which is exactly the forged turn boundary the
 * neutralisation exists to prevent. vLLM's tokenizer returns
 * {@code all_special_tokens} in declaration order, not by length, so the engine
 * has to sort.
 *
 * <p>Exercises the ordering itself; building a real engine would start CPython
 * and load weights, which the integration suites cover.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmSpecialTokenOrderTest {

  /** Mirrors the normalisation in {@link VllmTextGenEngine#specialTokenTexts()}. */
  private static List<String> normalise(List<String> raw) {
    return raw
      .stream()
      .distinct()
      .sorted(Comparator.comparingInt(String::length).reversed())
      .toList();
  }

  @Test
  void longer_tokens_come_first() {
    // Declaration order as Qwen3's tokenizer actually reports it — <|im_end|>
    // precedes the longer <|object_ref_start|>.
    var normalised = normalise(
      List.of(
        "<|im_end|>",
        "<|endoftext|>",
        "<|im_start|>",
        "<|object_ref_start|>",
        "<|vision_pad|>"
      )
    );

    assertThat(normalised).first().isEqualTo("<|object_ref_start|>");
    assertThat(normalised).isSortedAccordingTo(Comparator.comparingInt(String::length).reversed());
  }

  @Test
  void a_shorter_token_cannot_consume_a_longer_one() {
    // The concrete hazard: "<|im_start|>" is a prefix-sharing neighbour of
    // "<|im_start|>assistant". Replacing the short one first would leave
    // "assistant" dangling in the text.
    var normalised = normalise(List.of("<|im_start|>", "<|im_start|>assistant"));

    String text = "<|im_start|>assistant";
    for (String token : normalised) {
      text = text.replace(token, "");
    }

    assertThat(text).isEmpty();
  }

  @Test
  void duplicates_are_dropped() {
    // all_special_tokens can repeat a token that serves two roles (e.g. the
    // same marker as both EOS and pad).
    assertThat(normalise(List.of("<|endoftext|>", "<|endoftext|>", "<|im_end|>"))).containsExactly(
      "<|endoftext|>",
      "<|im_end|>"
    );
  }

  @Test
  void an_empty_token_list_is_harmless() {
    assertThat(normalise(List.of())).isEmpty();
  }
}
