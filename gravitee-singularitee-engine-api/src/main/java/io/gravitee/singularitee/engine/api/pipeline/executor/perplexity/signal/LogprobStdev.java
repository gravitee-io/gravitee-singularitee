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
package io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.signal;

import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.ConfidenceSignal;
import io.gravitee.singularitee.engine.api.pipeline.executor.perplexity.TokenConfidence;

/** Standard deviation of the chosen-token logprobs: how much the model's confidence swung. */
public final class LogprobStdev implements ConfidenceSignal {

  public String field() {
    return "logprob_stdev";
  }

  public double compute(TokenConfidence c) {
    var xs = c.chosen();
    if (xs.size() < 2) return 0.0;
    double mean = ConfidenceSignal.mean(xs);
    double v = 0.0;
    for (double lp : xs) v += (lp - mean) * (lp - mean);
    return Math.sqrt(v / xs.size());
  }
}
