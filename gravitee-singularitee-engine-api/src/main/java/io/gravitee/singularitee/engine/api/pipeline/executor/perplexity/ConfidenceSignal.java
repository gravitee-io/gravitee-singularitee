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
package io.gravitee.singularitee.engine.api.pipeline.executor.perplexity;

import java.util.List;

/**
 * One confidence feature computed from a {@link TokenConfidence} sample: a single summary of the
 * generation's per-token log-probabilities that a downstream consumer may calibrate into a
 * reliability estimate. Each implementation is one such summary, named by the context/span field it
 * publishes under, so features are added by writing a class and listing it, not by growing a method
 * bag.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface ConfidenceSignal {
  /** The field suffix this signal publishes under (e.g. {@code min_margin} -> {@code <step>.min_margin}). */
  String field();

  /** Compute the signal from the sample; conventionally 0 when the sample lacks the needed data. */
  double compute(TokenConfidence sample);

  /** Perplexity of a set of logprobs: {@code exp(-mean)}. 0 for an empty set. */
  static double perplexity(List<Double> logprobs) {
    return logprobs.isEmpty() ? 0.0 : Math.exp(-mean(logprobs));
  }

  /** Arithmetic mean, or 0 for an empty list. */
  static double mean(List<Double> values) {
    if (values.isEmpty()) return 0.0;
    double s = 0.0;
    for (double v : values) s += v;
    return s / values.size();
  }

  /** Minimum, or {@link Double#POSITIVE_INFINITY} for an empty list. */
  static double min(List<Double> values) {
    double m = Double.POSITIVE_INFINITY;
    for (double v : values) if (v < m) m = v;
    return m;
  }
}
