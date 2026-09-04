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

/** Least-squares slope of logprob against token position: negative = losing confidence toward the end. */
public final class LogprobSlope implements ConfidenceSignal {

  public String field() {
    return "logprob_slope";
  }

  public double compute(TokenConfidence c) {
    var xs = c.chosen();
    int n = xs.size();
    if (n < 2) return 0.0;
    double mx = (n - 1) / 2.0;
    double my = ConfidenceSignal.mean(xs);
    double num = 0.0,
      den = 0.0;
    for (int i = 0; i < n; i++) {
      num += (i - mx) * (xs.get(i) - my);
      den += (i - mx) * (i - mx);
    }
    return den == 0.0 ? 0.0 : num / den;
  }
}
