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
package io.gravitee.singularitee.inference.api.decision;

import java.util.List;

/**
 * The answer to one question. The Java type carries the question type.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface Answer {
  /** The id of the question this answers. */
  String questionId();

  /** The scores of each variant before temperature; empty unless requested. */
  List<float[]> rawScores();

  /** The probability of one option. */
  record OptionProbability(String key, double probability) {}

  /**
   * @param choice        the most probable option
   * @param probabilities every option, in option order, summing to 1
   * @param confidence    0 when every option is equally likely, 1 when one is certain
   */
  record Choice(
    String questionId,
    String choice,
    List<OptionProbability> probabilities,
    double confidence,
    List<float[]> rawScores
  ) implements Answer {}

  /**
   * @param score         the expected level index, weighted by probability
   * @param mode          the most probable level index
   * @param probabilities every level, keys {@code "0".."n-1"}, lowest first
   * @param confidence    0 when spread like a uniform distribution, 1 when one level is certain
   */
  record Score(
    String questionId,
    double score,
    int mode,
    List<OptionProbability> probabilities,
    double confidence,
    List<float[]> rawScores
  ) implements Answer {}

  /** @param probabilityTrue the probability that the answer is yes */
  record Noul(String questionId, double probabilityTrue, List<float[]> rawScores) implements
    Answer {}
}
