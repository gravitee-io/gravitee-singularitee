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
package io.gravitee.singularitee.engine.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.ClassifyResult;
import io.gravitee.singularitee.engine.classifier.RegexClassifierEngine.PatternEntry;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RegexClassifierEngineTest {

  private static final String SSN = "\\b\\d{3}-\\d{2}-\\d{4}\\b";
  private static final String EMAIL = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b";

  @Nested
  class Match {

    @Test
    void returns_result_with_entity_type_and_span() {
      ClassifyResponse resp;
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")))) {
        resp = engine.rxClassify(new ClassifyRequest("My SSN is 123-45-6789.")).blockingGet();
      }

      assertThat(resp.topLabel()).isEqualTo("SSN");
      assertThat(resp.topScore()).isEqualTo(1.0f);
      assertThat(resp.allScores()).containsEntry("SSN", 1.0f);
      assertThat(resp.results()).hasSize(1);
      ClassifyResult r = resp.results().getFirst();
      assertThat(r.label()).isEqualTo("SSN");
      assertThat(r.token()).isEqualTo("123-45-6789");
      assertThat(r.start()).isEqualTo(10);
      assertThat(r.end()).isEqualTo(21);
    }

    @Test
    void finds_multiple_matches_from_different_patterns() {
      ClassifyResponse resp;
      try (
        var engine = new RegexClassifierEngine(
          List.of(new PatternEntry(SSN, "SSN"), new PatternEntry(EMAIL, "EMAIL"))
        )
      ) {
        resp = engine
          .rxClassify(new ClassifyRequest("Email a@b.com or SSN 123-45-6789 please"))
          .blockingGet();
      }

      assertThat(resp.results()).hasSize(2);
      assertThat(resp.allScores()).containsEntry("EMAIL", 1.0f).containsEntry("SSN", 1.0f);
      // topLabel is the first match in text order
      assertThat(resp.topLabel()).isEqualTo("EMAIL");
    }

    @Test
    void finds_multiple_occurrences_of_the_same_pattern() {
      ClassifyResponse resp;
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")))) {
        resp = engine
          .rxClassify(new ClassifyRequest("SSNs: 111-22-3333 and 444-55-6666"))
          .blockingGet();
      }
      assertThat(resp.results()).hasSize(2);
      assertThat(resp.results().get(0).token()).isEqualTo("111-22-3333");
      assertThat(resp.results().get(1).token()).isEqualTo("444-55-6666");
    }
  }

  @Nested
  class NoMatch {

    @Test
    void returns_empty_response_when_nothing_matches() {
      ClassifyResponse resp;
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")))) {
        resp = engine.rxClassify(new ClassifyRequest("hello world")).blockingGet();
      }
      assertThat(resp.topLabel()).isNull();
      assertThat(resp.topScore()).isEqualTo(0.0f);
      assertThat(resp.allScores()).isEmpty();
      assertThat(resp.results()).isEmpty();
    }

    @Test
    void returns_empty_response_for_null_text() {
      var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")));
      var resp = engine.rxClassify(new ClassifyRequest(null)).blockingGet();
      assertThat(resp.results()).isEmpty();
    }

    @Test
    void returns_empty_response_for_empty_text() {
      var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")));
      var resp = engine.rxClassify(new ClassifyRequest("")).blockingGet();
      assertThat(resp.results()).isEmpty();
    }
  }

  @Nested
  class Chunking {

    @Test
    void splits_huge_input_and_maps_spans_back_to_original_offsets() {
      // A small budget forces the splitter to break the input into many chunks.
      // Matches found in later chunks must carry offsets into the original text.
      String filler = "clean sentence. ".repeat(50); // ~800 chars, no match
      String text = filler + "SSN 123-45-6789 here.";
      int expectedStart = text.indexOf("123-45-6789");

      ClassifyResponse resp;
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")), 64)) {
        resp = engine.rxClassify(new ClassifyRequest(text)).blockingGet();
      }

      assertThat(resp.results()).hasSize(1);
      ClassifyResult r = resp.results().getFirst();
      assertThat(r.token()).isEqualTo("123-45-6789");
      assertThat(r.start()).isEqualTo(expectedStart);
      assertThat(r.end()).isEqualTo(expectedStart + "123-45-6789".length());
      // The span points at the match in the ORIGINAL text.
      assertThat(text.substring(r.start(), r.end())).isEqualTo("123-45-6789");
    }

    @Test
    void presplit_path_does_not_re_split_and_matches_the_whole_input() {
      // rxClassifyPresplit must match the full text in one pass even when it far
      // exceeds the token budget (the caller has already chunked it).
      String text = "a ".repeat(200) + "SSN 123-45-6789";
      int expectedStart = text.indexOf("123-45-6789");
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")), 8)) {
        var resp = engine.rxClassifyPresplit(new ClassifyRequest(text)).blockingGet();
        assertThat(resp.results()).hasSize(1);
        assertThat(resp.results().getFirst().start()).isEqualTo(expectedStart);
        assertThat(resp.results().getFirst().token()).isEqualTo("123-45-6789");
      }
    }

    @Test
    void chunking_preserves_document_order_of_matches() {
      String text = "first 111-22-3333. " + "padding sentence. ".repeat(20) + "then 444-55-6666.";
      ClassifyResponse resp;
      try (var engine = new RegexClassifierEngine(List.of(new PatternEntry(SSN, "SSN")), 48)) {
        resp = engine.rxClassify(new ClassifyRequest(text)).blockingGet();
      }
      assertThat(resp.results()).hasSize(2);
      assertThat(resp.results().get(0).token()).isEqualTo("111-22-3333");
      assertThat(resp.results().get(1).token()).isEqualTo("444-55-6666");
      assertThat(resp.results().get(0).start()).isLessThan(resp.results().get(1).start());
    }
  }

  @Nested
  class Construction {

    @Test
    void accepts_empty_pattern_list_and_always_returns_empty() {
      var engine = new RegexClassifierEngine(List.of());
      var resp = engine.rxClassify(new ClassifyRequest("123-45-6789")).blockingGet();
      assertThat(resp.results()).isEmpty();
    }

    @Test
    void skips_blank_pattern_entries() {
      var engine = new RegexClassifierEngine(
        java.util.Arrays.asList(new PatternEntry("", "BLANK"), new PatternEntry(SSN, "SSN"))
      );
      var resp = engine.rxClassify(new ClassifyRequest("123-45-6789")).blockingGet();
      assertThat(resp.results()).hasSize(1);
      assertThat(resp.results().get(0).label()).isEqualTo("SSN");
    }

    @Test
    void throws_on_null_pattern_list() {
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
        new RegexClassifierEngine(null)
      );
    }

    @Test
    void pattern_entry_requires_non_null_fields() {
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
        new PatternEntry(null, "X")
      );
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
        new PatternEntry("a", null)
      );
    }
  }
}
