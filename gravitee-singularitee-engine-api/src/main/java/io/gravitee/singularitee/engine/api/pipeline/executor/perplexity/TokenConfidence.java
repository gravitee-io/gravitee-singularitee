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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The accumulated per-token confidence sample of a single generation: the raw material every {@link
 * ConfidenceSignal} reads. It holds only data, no feature arithmetic.
 *
 * <p>Each token contributes its chosen-token log-probability, and, when the request asked for
 * alternatives (top-k depth &gt;= 2), a per-token margin (top-1 minus top-2, how decisively the model
 * picked) and entropy (spread over the renormalised top-k). The caller classifies each token as
 * answer or reasoning content, since that depends on the streaming tag machine, not on the numbers.
 *
 * <p>Not thread-safe: fed from a single stream thread, like the writer that owns it. Everything here
 * is a byproduct of the one generation the model already ran, so building the sample is essentially
 * free.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TokenConfidence {

  private final List<Double> chosen = new ArrayList<>();
  private final List<Double> answer = new ArrayList<>();
  private final List<Double> margins = new ArrayList<>();
  private final List<Double> entropies = new ArrayList<>();

  /**
   * Record one token.
   *
   * @param chosenLogprob natural-log probability of the emitted token
   * @param isAnswer      whether this token is answer content (vs reasoning); classified by the caller
   * @param topLogprobs   the position's top-k candidate logprobs, descending; length &lt; 2 skips the
   *                      margin/entropy contribution for this token
   */
  public void record(double chosenLogprob, boolean isAnswer, double[] topLogprobs) {
    chosen.add(chosenLogprob);
    if (isAnswer) {
      answer.add(chosenLogprob);
    }
    if (topLogprobs != null && topLogprobs.length >= 2) {
      margins.add(topLogprobs[0] - topLogprobs[1]);
      entropies.add(entropy(topLogprobs));
    }
  }

  /** Chosen-token logprobs over the whole generation, in order. */
  public List<Double> chosen() {
    return Collections.unmodifiableList(chosen);
  }

  /** Chosen-token logprobs of the answer tokens alone (reasoning excluded), in order. */
  public List<Double> answer() {
    return Collections.unmodifiableList(answer);
  }

  /** Per-token top-1 vs top-2 margins (nats), for positions that carried alternatives. */
  public List<Double> margins() {
    return Collections.unmodifiableList(margins);
  }

  /** Per-token entropies over the top-k candidates, for positions that carried alternatives. */
  public List<Double> entropies() {
    return Collections.unmodifiableList(entropies);
  }

  /** Number of tokens whose chosen log-probability was captured (0 = none requested). */
  public int size() {
    return chosen.size();
  }

  private static double entropy(double[] logprobs) {
    double sumP = 0.0;
    for (double lp : logprobs) sumP += Math.exp(lp);
    if (sumP <= 0) return 0.0;
    double h = 0.0;
    for (double lp : logprobs) {
      double p = Math.exp(lp) / sumP;
      if (p > 0) h -= p * Math.log(p);
    }
    return h;
  }
}
