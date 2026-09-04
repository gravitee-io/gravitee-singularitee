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

/** Fraction of tokens whose top1-top2 margin was below the low-margin threshold (contested picks). */
public final class LowMarginFraction implements ConfidenceSignal {

  private static final double LOW_MARGIN = 0.5;

  public String field() {
    return "low_margin_fraction";
  }

  public double compute(TokenConfidence c) {
    if (c.margins().isEmpty()) return 0.0;
    long n = c
      .margins()
      .stream()
      .filter(m -> m < LOW_MARGIN)
      .count();
    return (double) n / c.margins().size();
  }
}
