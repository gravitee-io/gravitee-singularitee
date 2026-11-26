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
package io.gravitee.singularitee.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifyResponseTest {

  private static final List<ClassifyLabel> TOPICS = List.of(
    new ClassifyLabel("billing", ""),
    new ClassifyLabel("shipping", "")
  );

  private static Map<String, Float> scores(Object... pairs) {
    Map<String, Float> scores = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      scores.put((String) pairs[i], (Float) pairs[i + 1]);
    }
    return scores;
  }

  @Test
  void a_response_within_the_labels_is_kept_as_it_is() {
    var response = new ClassifyResponse(
      "billing",
      0.9f,
      scores("billing", 0.9f, "shipping", 0.1f),
      List.of(new ClassifyResult("billing", 0.9f))
    );

    assertThat(response.within(TOPICS)).isSameAs(response);
    assertThat(response.within(List.of())).isSameAs(response);
  }

  @Test
  void a_label_outside_the_labels_is_dropped_and_the_best_remaining_one_wins() {
    var usage = new ClassifyUsage(3, 0);
    var response = new ClassifyResponse(
      "ALPHA",
      0.99f,
      scores("ALPHA", 0.99f, "shipping", 0.2f, "billing", 0.6f),
      List.of(
        new ClassifyResult("ALPHA", 0.99f, "ALPHA", 0, 5),
        new ClassifyResult("billing", 0.6f)
      ),
      usage
    );

    var kept = response.within(TOPICS);

    assertThat(kept.topLabel()).isEqualTo("billing");
    assertThat(kept.topScore()).isEqualTo(0.6f);
    assertThat(kept.allScores()).containsOnlyKeys("shipping", "billing");
    assertThat(kept.results()).extracting(ClassifyResult::label).containsExactly("billing");
    assertThat(kept.usage()).isSameAs(usage);
  }

  @Test
  void spans_alone_decide_the_top_label_when_no_score_remains() {
    var response = new ClassifyResponse(
      "EMAIL",
      0.97f,
      Map.of(),
      List.of(
        new ClassifyResult("EMAIL", 0.97f, "a@b.c", 0, 5),
        new ClassifyResult("billing", 0.7f, "invoice", 10, 17)
      )
    );

    var kept = response.within(TOPICS);

    assertThat(kept.topLabel()).isEqualTo("billing");
    assertThat(kept.topScore()).isEqualTo(0.7f);
  }

  @Test
  void nothing_within_the_labels_leaves_no_top_label() {
    var response = new ClassifyResponse("ALPHA", 0.99f, scores("ALPHA", 0.99f), List.of());

    var kept = response.within(TOPICS);

    assertThat(kept.topLabel()).isNull();
    assertThat(kept.topScore()).isZero();
    assertThat(kept.allScores()).isEmpty();
  }
}
