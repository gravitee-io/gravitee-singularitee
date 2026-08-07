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

import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether a dialect's markers survive detokenization.
 *
 * <p>vLLM deletes special tokens by default, so a Harmony workspace whose tags
 * are {@code <|channel|>analysis<|message|>} would have the FSM searching for
 * markers the text no longer contains — the reasoning then leaks into the answer
 * with its markers dissolved into bare words ("analysisWe need to..."). Getting
 * it wrong the other way is not free either: preserving special tokens for a
 * dialect that does not use them surfaces the model's terminal tokens
 * ({@code <|return|>}, {@code <|endoftext|>}) in the reply.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class SpecialTokenMarkersTest {

  @Test
  void harmony_channel_markers_need_special_tokens() {
    TagConfig reasoning = new TagConfig(
      "<|channel|>analysis<|message|>",
      "<|end|><|start|>assistant<|channel|>final<|message|>"
    );

    assertThat(EngineAdapter.needsSpecialTokens(reasoning)).isTrue();
  }

  @Test
  void plain_text_markers_do_not() {
    // Qwen's <think> is ordinary text and survives detokenization either way;
    // asking to keep special tokens would only expose its stop tokens.
    assertThat(EngineAdapter.needsSpecialTokens(new TagConfig("<think>", "</think>"))).isFalse();
  }

  @Test
  void an_alternative_opening_marker_is_enough() {
    // Harmony opens the tool channel two ways, and the second one lives in
    // openAlternatives — checking only the primary marker would miss it.
    TagConfig tools = new TagConfig(
      "plain-open",
      "plain-close",
      List.of("<|end|><|start|>assistant<|channel|>commentary to=functions.")
    );

    assertThat(EngineAdapter.needsSpecialTokens(tools)).isTrue();
  }

  @Test
  void a_special_close_marker_is_enough() {
    assertThat(EngineAdapter.needsSpecialTokens(new TagConfig("<tool>", "<|call|>"))).isTrue();
  }

  @Test
  void unconfigured_or_absent_tags_change_nothing() {
    assertThat(EngineAdapter.needsSpecialTokens(null)).isFalse();
    assertThat(EngineAdapter.needsSpecialTokens(new TagConfig("", ""))).isFalse();
  }
}
