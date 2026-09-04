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

/** Max per-token entropy over the top-k candidates: the single most-spread step. */
public final class MaxEntropy implements ConfidenceSignal {

  public String field() {
    return "max_entropy";
  }

  public double compute(TokenConfidence c) {
    double m = 0.0;
    for (double e : c.entropies()) if (e > m) m = e;
    return m;
  }
}
