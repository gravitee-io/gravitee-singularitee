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
package io.gravitee.singularitee.adapter.gliner;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.ClassifyResult;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlinerChunkingTest {

  @Test
  void estimateTokens_is_roughly_chars_over_3_5() {
    assertThat(GlinerChunking.estimateTokens("")).isZero();
    assertThat(GlinerChunking.estimateTokens("abcd")).isEqualTo(2); // ceil(4/3.5)
    assertThat(GlinerChunking.estimateTokens("abcde")).isEqualTo(2); // ceil(5/3.5)
    assertThat(GlinerChunking.estimateTokens("abcdefgh")).isEqualTo(3); // ceil(8/3.5)
  }

  @Test
  void estimatedTokenEndOffsets_count_tracks_length() {
    assertThat(GlinerChunking.estimatedTokenEndOffsets("")).isEmpty();
    // ceil(10/3.5) = 3 boundaries, evenly spaced, last == length
    assertThat(GlinerChunking.estimatedTokenEndOffsets("abcdefghij")).containsExactly(4, 7, 10);
  }

  @Test
  void textBudget_subtracts_label_prompt_from_cap_defaults_to_512_and_floors() {
    int noLabels = GlinerChunking.textBudget(0, List.of()); // 0 -> default 512
    assertThat(noLabels).isLessThan(512).isGreaterThan(480); // only special-token overhead

    int withLabels = GlinerChunking.textBudget(
      512,
      List.of("a".repeat(40), "b".repeat(40), "c".repeat(40))
    );
    assertThat(withLabels).isLessThan(noLabels); // labels eat into the window

    int floored = GlinerChunking.textBudget(64, List.of("x".repeat(1000)));
    assertThat(floored).isGreaterThanOrEqualTo(16); // never collapses to zero
  }

  @Test
  void splitter_breaks_long_input_into_budget_sized_disjoint_chunks() {
    String text = "word ".repeat(200).trim(); // no semantic separators -> token-window fallback
    List<RecursiveTextSplitter.Chunk> chunks = GlinerChunking.splitter(50).split(text);

    assertThat(chunks).hasSizeGreaterThan(1);
    int prevEnd = 0;
    for (RecursiveTextSplitter.Chunk chunk : chunks) {
      assertThat(GlinerChunking.estimateTokens(chunk.text())).isLessThanOrEqualTo(50);
      assertThat(chunk.start()).isGreaterThanOrEqualTo(prevEnd); // disjoint + ordered
      prevEnd = chunk.end();
    }
  }

  @Test
  void nerResponse_aggregates_max_per_type_and_sorts_by_score() {
    List<ClassifyResult> spans = new ArrayList<>(
      List.of(
        new ClassifyResult("EMAIL", 0.80f, "a@b.com", 10, 17),
        new ClassifyResult("EMAIL", 0.95f, "c@d.com", 40, 47),
        new ClassifyResult("PERSON", 0.60f, "Bob", 0, 3)
      )
    );

    ClassifyResponse r = GlinerChunking.nerResponse(spans);

    assertThat(r.topLabel()).isEqualTo("EMAIL");
    assertThat(r.topScore()).isEqualTo(0.95f);
    assertThat(r.allScores()).containsEntry("EMAIL", 0.95f).containsEntry("PERSON", 0.60f);
    assertThat(r.results().getFirst().score()).isEqualTo(0.95f); // sorted strongest-first
  }

  @Test
  void classifierResponse_picks_top_label_and_keeps_per_chunk_rows() {
    Map<String, Float> max = new LinkedHashMap<>();
    max.put("benign", 0.30f);
    max.put("malicious", 0.91f);
    // per-chunk rows: 2 chunks, each carrying its [start,end] span
    List<ClassifyResult> rows = List.of(
      new ClassifyResult("benign", 0.30f, null, 0, 64),
      new ClassifyResult("malicious", 0.91f, null, 64, 128)
    );

    ClassifyResponse r = GlinerChunking.classifierResponse(max, rows);

    assertThat(r.topLabel()).isEqualTo("malicious"); // max per label across chunks
    assertThat(r.topScore()).isEqualTo(0.91f);
    assertThat(r.allScores()).containsEntry("benign", 0.30f).containsEntry("malicious", 0.91f);
    // per-chunk rows preserved, each tagged with the chunk span it came from
    assertThat(r.results()).hasSize(2);
    assertThat(r.results()).extracting(ClassifyResult::start).containsExactly(0, 64);
    assertThat(r.results()).extracting(ClassifyResult::end).containsExactly(64, 128);
  }
}
