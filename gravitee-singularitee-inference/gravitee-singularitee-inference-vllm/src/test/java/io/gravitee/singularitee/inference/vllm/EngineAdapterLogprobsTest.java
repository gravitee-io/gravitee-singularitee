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

import io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry;
import io.gravitee.vllm.engine.LogprobEntry;
import io.gravitee.vllm.iterator.VllmOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The logprobs of a streamed vLLM step, read as the position of its last token. */
class EngineAdapterLogprobsTest {

  private static VllmOutput step(
    List<Integer> tokenIds,
    List<Map<Integer, LogprobEntry>> logprobs
  ) {
    return new VllmOutput("seq-0", "", " cat", false, null, null, tokenIds, logprobs);
  }

  @Test
  void reads_the_sampled_token_and_the_top_candidates_by_rank() {
    var position = Map.of(
      7,
      new LogprobEntry(7, -0.1, 1, " cat"),
      9,
      new LogprobEntry(9, -2.5, 2, " dog"),
      4,
      new LogprobEntry(4, -3.0, 3, " cow")
    );

    var logprobs = EngineAdapter.positionLogprobs(step(List.of(7), List.of(position)), 2);

    assertThat(logprobs.chosen().tokenId()).isEqualTo(7);
    assertThat(logprobs.chosen().token()).isEqualTo(" cat");
    assertThat(logprobs.chosen().logprob()).isEqualTo(-0.1);
    assertThat(logprobs.chosen().bytes()).containsExactly(32, 99, 97, 116);
    assertThat(logprobs.top()).extracting(TokenLogprobEntry::tokenId).containsExactly(7, 9);
  }

  @Test
  void a_sampled_token_outside_the_top_ranks_is_listed_last() {
    var position = Map.of(
      1,
      new LogprobEntry(1, -0.2, 1, "a"),
      5,
      new LogprobEntry(5, -4.0, 6, "z")
    );

    var logprobs = EngineAdapter.positionLogprobs(step(List.of(5), List.of(position)), 1);

    assertThat(logprobs.chosen().tokenId()).isEqualTo(5);
    assertThat(logprobs.top()).extracting(TokenLogprobEntry::tokenId).containsExactly(1, 5);
  }

  @Test
  void a_step_of_several_tokens_reports_its_last_position() {
    var first = Map.of(3, new LogprobEntry(3, -0.5, 1, "he"));
    var last = Map.of(8, new LogprobEntry(8, -0.7, 1, "llo"));

    var logprobs = EngineAdapter.positionLogprobs(step(List.of(3, 8), List.of(first, last)), 1);

    assertThat(logprobs.chosen().tokenId()).isEqualTo(8);
  }

  @Test
  void a_step_without_logprobs_has_none() {
    assertThat(EngineAdapter.positionLogprobs(step(List.of(), null), 1)).isNull();
    assertThat(EngineAdapter.positionLogprobs(step(List.of(3), List.of()), 1)).isNull();
  }
}
