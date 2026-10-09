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
package io.gravitee.singularitee.inference.decision;

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.Answer.OptionProbability;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw scores into an answer: a softmax at the question's temperature per variant, the
 * average of the variants, then the answer of the question's type with the published confidence
 * formulas.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class DecisionScoring {

  private DecisionScoring() {}

  /**
   * @param questionId  the question answered
   * @param kind        its type
   * @param optionKeys  the option keys in prompt order (variant 0)
   * @param scores      one array per variant; variant 1 lists the options in reverse order
   * @param temperature the softmax temperature
   * @param noulRating  whether a noul is read as a rating scale (lev) rather than true / false
   * @param includeRaw  whether to carry the raw scores
   */
  static Answer answer(
    String questionId,
    QuestionKind kind,
    List<String> optionKeys,
    List<float[]> scores,
    float temperature,
    boolean noulRating,
    boolean includeRaw
  ) {
    double[] probs = probabilities(questionId, scores, temperature);
    List<float[]> raw = includeRaw ? List.copyOf(scores) : List.of();

    return switch (kind) {
      case NOUL -> new Answer.Noul(questionId, noulProbability(probs, optionKeys, noulRating), raw);
      case CHOICE -> {
        int best = argmax(probs);
        yield new Answer.Choice(
          questionId,
          optionKeys.get(best),
          keyed(optionKeys, probs),
          choiceConfidence(probs),
          raw
        );
      }
      case SCORE -> {
        double expected = 0;
        for (int i = 0; i < probs.length; i++) expected += i * probs[i];
        yield new Answer.Score(
          questionId,
          expected,
          argmax(probs),
          keyed(optionKeys, probs),
          scoreConfidence(probs),
          raw
        );
      }
    };
  }

  private static double[] probabilities(
    String questionId,
    List<float[]> scores,
    float temperature
  ) {
    int n = scores.getFirst().length;
    double[] probs = new double[n];
    for (int v = 0; v < scores.size(); v++) {
      float[] s = scores.get(v);
      if (s.length != n) {
        throw new IllegalStateException("decision result does not match the number of options");
      }
      float max = Float.NEGATIVE_INFINITY;
      for (float x : s) {
        if (Float.isNaN(x)) {
          throw new DecisionException(
            DecisionException.Reason.EVALUATION_FAILED,
            "the model could not evaluate the decision (questions." + questionId + ")"
          );
        }
        max = Math.max(max, x);
      }
      double[] p = new double[n];
      double sum = 0;
      for (int i = 0; i < n; i++) {
        p[i] = Math.exp((double) (s[i] - max) / temperature);
        sum += p[i];
      }
      for (int i = 0; i < n; i++) {
        probs[v == 0 ? i : n - 1 - i] += p[i] / sum / scores.size();
      }
    }
    return probs;
  }

  private static double noulProbability(double[] probs, List<String> optionKeys, boolean rating) {
    if (rating) {
      double expected = 0;
      for (int i = 0; i < probs.length; i++) expected += (probs[i] * i) / (probs.length - 1);
      return expected;
    }
    return probs[optionKeys.indexOf("true")];
  }

  private static List<OptionProbability> keyed(List<String> keys, double[] probs) {
    var out = new ArrayList<OptionProbability>(probs.length);
    for (int i = 0; i < probs.length; i++) out.add(new OptionProbability(keys.get(i), probs[i]));
    return out;
  }

  private static int argmax(double[] probs) {
    int best = 0;
    for (int i = 1; i < probs.length; i++) if (probs[i] > probs[best]) best = i;
    return best;
  }

  /** How far the top probability is above uniform, scaled to 0..1. */
  static double choiceConfidence(double[] probs) {
    if (probs.length < 2) return 1.0;
    double uniform = 1.0 / probs.length;
    double max = probs[argmax(probs)];
    return Math.max(0.0, (max - uniform) / (1.0 - uniform));
  }

  /**
   * One minus the mean distance to the mode, relative to that of a uniform distribution around
   * the centre of the scale.
   */
  static double scoreConfidence(double[] probs) {
    int n = probs.length;
    if (n < 2) return 1.0;
    int mode = argmax(probs);
    double dist = 0;
    double distUniform = 0;
    for (int i = 0; i < n; i++) {
      dist += probs[i] * Math.abs(i - mode);
      distUniform += Math.abs(i - (n - 1) / 2.0) / n;
    }
    return Math.max(0.0, 1.0 - dist / distUniform);
  }
}
