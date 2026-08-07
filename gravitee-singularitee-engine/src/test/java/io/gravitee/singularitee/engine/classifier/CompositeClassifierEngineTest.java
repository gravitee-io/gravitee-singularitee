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

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.classifier.RegexClassifierEngine.PatternEntry;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CompositeClassifierEngineTest {

  @Nested
  class Delegation {

    @Test
    void aggregates_results_from_all_delegates() {
      var word = new RegexClassifierEngine(List.of(new PatternEntry("\\bkill\\b", "PROFANITY")));
      var regex = new RegexClassifierEngine(
        List.of(new PatternEntry("\\b\\d{3}-\\d{2}-\\d{4}\\b", "SSN"))
      );
      ClassifierEngine composite = new CompositeClassifierEngine(List.of(word, regex));

      var resp = composite
        .rxClassify(new ClassifyRequest("Do not kill, SSN 123-45-6789"))
        .blockingGet();

      // Both delegates fired — their results are merged
      assertThat(resp.results()).hasSize(2);
      assertThat(resp.allScores()).containsEntry("PROFANITY", 1.0f);
      assertThat(resp.allScores()).containsEntry("SSN", 1.0f);
      assertThat(resp.topLabel()).isNotNull();
      assertThat(resp.topScore()).isEqualTo(1.0f);
    }

    @Test
    void preserves_spans_from_each_delegate() {
      var word = new RegexClassifierEngine(List.of(new PatternEntry("\\bkill\\b", "PROFANITY")));
      var regex = new RegexClassifierEngine(
        List.of(new PatternEntry("\\b\\d{3}-\\d{2}-\\d{4}\\b", "SSN"))
      );
      var composite = new CompositeClassifierEngine(List.of(word, regex));

      var resp = composite.rxClassify(new ClassifyRequest("kill 123-45-6789")).blockingGet();

      assertThat(resp.results()).allSatisfy(r -> {
        assertThat(r.start()).isNotNull();
        assertThat(r.end()).isNotNull();
        assertThat(r.start()).isLessThan(r.end());
      });
    }

    @Test
    void top_label_is_first_non_null_in_delegate_order() {
      var first = new RegexClassifierEngine(List.of(new PatternEntry("alpha", "ALPHA")));
      var second = new RegexClassifierEngine(List.of(new PatternEntry("beta", "BETA")));
      var composite = new CompositeClassifierEngine(List.of(first, second));

      var resp = composite.rxClassify(new ClassifyRequest("alpha beta")).blockingGet();

      // First delegate emits "ALPHA" so that wins as the top label.
      assertThat(resp.topLabel()).isEqualTo("ALPHA");
    }

    @Test
    void falls_through_to_second_delegate_when_first_does_not_match() {
      var first = new RegexClassifierEngine(List.of(new PatternEntry("nothing-here", "NONE")));
      var second = new RegexClassifierEngine(List.of(new PatternEntry("beta", "BETA")));
      var composite = new CompositeClassifierEngine(List.of(first, second));

      var resp = composite.rxClassify(new ClassifyRequest("alpha beta")).blockingGet();

      assertThat(resp.topLabel()).isEqualTo("BETA");
      assertThat(resp.results()).hasSize(1);
    }
  }

  @Nested
  class NoMatch {

    @Test
    void returns_empty_response_when_no_delegate_matches() {
      var word = new RegexClassifierEngine(List.of(new PatternEntry("\\bkill\\b", "PROFANITY")));
      var regex = new RegexClassifierEngine(List.of(new PatternEntry("SSN", "SSN")));
      var composite = new CompositeClassifierEngine(List.of(word, regex));

      var resp = composite.rxClassify(new ClassifyRequest("clean text")).blockingGet();

      assertThat(resp.topLabel()).isNull();
      assertThat(resp.topScore()).isEqualTo(0.0f);
      assertThat(resp.allScores()).isEmpty();
      assertThat(resp.results()).isEmpty();
    }
  }

  @Nested
  class Composition {

    @Test
    void nested_composites_flatten_behaviour() {
      var alpha = new RegexClassifierEngine(List.of(new PatternEntry("alpha", "ALPHA")));
      var beta = new RegexClassifierEngine(List.of(new PatternEntry("beta", "BETA")));
      var inner = new CompositeClassifierEngine(List.of(alpha, beta));
      var outer = new CompositeClassifierEngine(
        List.of(inner, new RegexClassifierEngine(List.of(new PatternEntry("gamma", "GAMMA"))))
      );

      var resp = outer.rxClassify(new ClassifyRequest("alpha beta gamma")).blockingGet();

      // inner contributes 2 results, outer's additional delegate contributes 1
      assertThat(resp.results()).hasSize(3);
    }
  }

  @Nested
  class Chunking {

    @Test
    void splits_once_for_the_whole_composite_and_maps_spans_to_original_offsets() {
      // Two regex delegates over a huge input with a small shared budget. The
      // composite splits once; each delegate runs over the shared chunks and its
      // matches must carry offsets into the ORIGINAL text.
      var ssn = new RegexClassifierEngine(
        List.of(new PatternEntry("\\b\\d{3}-\\d{2}-\\d{4}\\b", "SSN"))
      );
      var word = new RegexClassifierEngine(List.of(new PatternEntry("\\bsecret\\b", "SECRET")));
      var composite = new CompositeClassifierEngine(List.of(ssn, word), 64);

      String filler = "clean sentence. ".repeat(60); // ~960 chars, no match
      String text = filler + "the secret is SSN 123-45-6789.";
      int ssnStart = text.indexOf("123-45-6789");
      int secretStart = text.indexOf("secret");

      var resp = composite.rxClassify(new ClassifyRequest(text)).blockingGet();

      // Both delegates fired across the shared chunks
      assertThat(resp.allScores()).containsEntry("SSN", 1.0f).containsEntry("SECRET", 1.0f);
      // Spans point at the matches in the original text
      assertThat(resp.results()).anySatisfy(r -> {
        assertThat(r.label()).isEqualTo("SSN");
        assertThat(r.start()).isEqualTo(ssnStart);
        assertThat(text.substring(r.start(), r.end())).isEqualTo("123-45-6789");
      });
      assertThat(resp.results()).anySatisfy(r -> {
        assertThat(r.label()).isEqualTo("SECRET");
        assertThat(r.start()).isEqualTo(secretStart);
        assertThat(text.substring(r.start(), r.end())).isEqualTo("secret");
      });
    }
  }

  @Nested
  class Construction {

    @Test
    void throws_on_null_delegates() {
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
        new CompositeClassifierEngine(null)
      );
    }

    @Test
    void throws_on_empty_delegates() {
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
        new CompositeClassifierEngine(List.of())
      );
    }
  }
}
