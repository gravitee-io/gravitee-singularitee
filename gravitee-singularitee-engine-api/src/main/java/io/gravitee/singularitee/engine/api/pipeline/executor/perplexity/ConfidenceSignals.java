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

import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.AnswerMaxTokenPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.AnswerMeanLogprob;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.AnswerPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.LogprobSlope;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.LogprobStdev;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.LowMarginFraction;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MaxEntropy;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MaxTokenPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MeanEntropy;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MeanLogprob;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MeanPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MinMargin;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.PerplexityQuantile;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.TailPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.UncertainTokenFraction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of confidence signals the infer step publishes from every generation, and the one
 * place that decides the published set. Each is free (a byproduct of the single generation); a
 * downstream consumer later picks the one(s) it calibrates. Adding a feature is one
 * class plus one line here.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ConfidenceSignals {

  private static final List<ConfidenceSignal> ALL = List.of(
    new MeanPerplexity(),
    new MeanLogprob(),
    new AnswerPerplexity(),
    new AnswerMeanLogprob(),
    new MaxTokenPerplexity(),
    new AnswerMaxTokenPerplexity(),
    new UncertainTokenFraction(),
    new PerplexityQuantile("p95_perplexity", 0.95),
    new PerplexityQuantile("p90_perplexity", 0.90),
    new LogprobStdev(),
    new TailPerplexity(),
    new LogprobSlope(),
    new MinMargin(),
    new LowMarginFraction(),
    new MeanEntropy(),
    new MaxEntropy()
  );

  private ConfidenceSignals() {}

  /** Every published signal, in publication order. */
  public static List<ConfidenceSignal> all() {
    return ALL;
  }

  /** Compute every signal over the sample, keyed by field, in publication order. */
  public static Map<String, Double> computeAll(TokenConfidence sample) {
    Map<String, Double> out = new LinkedHashMap<>();
    for (ConfidenceSignal signal : ALL) {
      out.put(signal.field(), signal.compute(sample));
    }
    return out;
  }
}
