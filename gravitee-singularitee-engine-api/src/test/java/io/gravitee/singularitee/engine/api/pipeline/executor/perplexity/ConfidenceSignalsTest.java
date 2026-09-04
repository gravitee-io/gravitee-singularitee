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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.AnswerPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.LowMarginFraction;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MaxEntropy;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MaxTokenPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MeanEntropy;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MeanPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.MinMargin;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.PerplexityQuantile;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.TailPerplexity;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal.UncertainTokenFraction;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the confidence-signal arithmetic over a hand-built sample, independent of the
 * streaming tag machine that feeds it (TokenCaptureStreamTest covers the classification end to end).
 */
class ConfidenceSignalsTest {

  private static final double[] NO_TOP = new double[0];

  private static TokenConfidence sample(double... answerLogprobs) {
    var c = new TokenConfidence();
    for (double lp : answerLogprobs) c.record(lp, true, NO_TOP);
    return c;
  }

  @Test
  void mean_perplexity_over_chosen_logprobs() {
    var c = sample(-0.1, -0.3, -0.2);
    assertThat(c.size()).isEqualTo(3);
    assertThat(new MeanPerplexity().compute(c)).isEqualTo(Math.exp(0.2), within(1e-9));
  }

  @Test
  void peak_and_uncertain_fraction_survive_a_low_mean() {
    var c = new TokenConfidence();
    for (int i = 0; i < 19; i++) c.record(-0.05, true, NO_TOP);
    c.record(-3.0, true, NO_TOP); // one very uncertain token
    c.record(-0.05, true, NO_TOP);
    assertThat(new MeanPerplexity().compute(c)).isLessThan(1.5); // mean stays confident
    assertThat(new MaxTokenPerplexity().compute(c)).isEqualTo(Math.exp(3.0), within(1e-6)); // peak not
    assertThat(new UncertainTokenFraction().compute(c)).isEqualTo(1.0 / 21.0, within(1e-9));
  }

  @Test
  void answer_scope_excludes_reasoning_tokens() {
    var c = new TokenConfidence();
    c.record(-0.05, false, NO_TOP); // reasoning
    c.record(-0.05, false, NO_TOP); // reasoning
    c.record(-1.2, true, NO_TOP); // answer
    c.record(-1.2, true, NO_TOP); // answer
    assertThat(c.size()).isEqualTo(4);
    assertThat(c.answer()).hasSize(2);
    assertThat(new AnswerPerplexity().compute(c)).isEqualTo(Math.exp(1.2), within(1e-9));
    assertThat(new MeanPerplexity().compute(c)).isLessThan(new AnswerPerplexity().compute(c));
  }

  @Test
  void margin_and_entropy_from_top_k() {
    var c = new TokenConfidence();
    c.record(-0.1, true, new double[] { -0.1, -3.0, -4.0 }); // decisive: wide margin 2.9
    c.record(-0.7, true, new double[] { -0.7, -0.8, -3.0 }); // contested: tiny margin 0.1
    assertThat(new MinMargin().compute(c)).isEqualTo(0.1, within(1e-5));
    assertThat(new LowMarginFraction().compute(c)).isEqualTo(0.5, within(1e-9)); // one of two below 0.5
    assertThat(new MaxEntropy().compute(c)).isGreaterThan(new MeanEntropy().compute(c));
    assertThat(new MeanEntropy().compute(c)).isGreaterThan(0.0);
  }

  @Test
  void top_k_features_zero_without_alternatives() {
    var c = new TokenConfidence();
    c.record(-0.1, true, NO_TOP);
    c.record(-0.2, true, new double[] { -0.2 }); // only one candidate: still no margin
    assertThat(new MinMargin().compute(c)).isEqualTo(0.0);
    assertThat(new LowMarginFraction().compute(c)).isEqualTo(0.0);
    assertThat(new MeanEntropy().compute(c)).isEqualTo(0.0);
    // chosen-token features still work
    assertThat(new PerplexityQuantile("p95", 0.95).compute(c)).isGreaterThan(0.0);
    assertThat(new TailPerplexity().compute(c)).isGreaterThan(0.0);
  }

  @Test
  void empty_sample_is_zero_not_nan() {
    var c = new TokenConfidence();
    assertThat(new MeanPerplexity().compute(c)).isEqualTo(0.0);
    assertThat(new MaxTokenPerplexity().compute(c)).isEqualTo(0.0);
    assertThat(new AnswerPerplexity().compute(c)).isEqualTo(0.0);
    assertThat(new MinMargin().compute(c)).isEqualTo(0.0);
  }

  @Test
  void registry_publishes_every_signal_once() {
    var c = sample(-0.1, -0.2, -0.3);
    var all = ConfidenceSignals.computeAll(c);
    // each signal keyed by a distinct field, none missing
    assertThat(all).containsKeys("perplexity", "min_margin", "max_entropy", "p95_perplexity");
    assertThat(all).hasSize(ConfidenceSignals.all().size());
  }
}
