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

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Output from a {@link ClassifierEngine} inference call.
 *
 * <p>The {@link #topLabel()} convenience method returns the highest-scoring label.
 * The full score distribution across all labels is available via {@link #allScores()}.
 *
 * @param topLabel   the label with the highest score
 * @param topScore   the score of the top label (0.0 to 1.0)
 * @param allScores  full map of label to score for all classes
 * @param results    ordered list of individual {@link ClassifyResult} entries (may include
 *                   token-level results for NER / token-classification models)
 * @param usage      tokens the engine read and wrote; {@code null} when it does not report them
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ClassifyResponse(
  String topLabel,
  float topScore,
  Map<String, Float> allScores,
  List<ClassifyResult> results,
  ClassifyUsage usage
) {
  /** A response without token usage. */
  public ClassifyResponse(
    String topLabel,
    float topScore,
    Map<String, Float> allScores,
    List<ClassifyResult> results
  ) {
    this(topLabel, topScore, allScores, results, null);
  }

  /** The same response with {@code usage}. */
  public ClassifyResponse withUsage(ClassifyUsage usage) {
    return new ClassifyResponse(topLabel, topScore, allScores, results, usage);
  }

  /**
   * The response kept to {@code labels}: the scores and results of any other label are dropped, and
   * the top label is the best remaining one, null when none remains. A model answering a request
   * that brought its labels may still name others (a composite or a remote with a fixed-label
   * member), and a step must never act on them. Empty {@code labels} keep the response as it is.
   */
  public ClassifyResponse within(List<ClassifyLabel> labels) {
    if (labels == null || labels.isEmpty()) {
      return this;
    }
    Set<String> names = labels.stream().map(ClassifyLabel::name).collect(Collectors.toSet());
    Map<String, Float> scores = allScores == null ? Map.of() : allScores;
    List<ClassifyResult> rows = results == null ? List.of() : results;
    boolean inside =
      (topLabel == null || names.contains(topLabel)) &&
      names.containsAll(scores.keySet()) &&
      rows.stream().allMatch(r -> names.contains(r.label()));
    if (inside) {
      return this;
    }
    Map<String, Float> keptScores = new LinkedHashMap<>();
    scores.forEach((label, score) -> {
      if (names.contains(label)) {
        keptScores.put(label, score);
      }
    });
    List<ClassifyResult> keptRows = rows
      .stream()
      .filter(r -> names.contains(r.label()))
      .toList();
    String top = null;
    float best = 0f;
    if (topLabel != null && names.contains(topLabel)) {
      top = topLabel;
      best = topScore;
    } else {
      for (var entry : keptScores.entrySet()) {
        if (top == null || entry.getValue() > best) {
          top = entry.getKey();
          best = entry.getValue();
        }
      }
      for (ClassifyResult row : keptRows) {
        if (top == null || (keptScores.isEmpty() && row.score() > best)) {
          top = row.label();
          best = row.score();
        }
      }
    }
    return new ClassifyResponse(top, best, keptScores, keptRows, usage);
  }
}
