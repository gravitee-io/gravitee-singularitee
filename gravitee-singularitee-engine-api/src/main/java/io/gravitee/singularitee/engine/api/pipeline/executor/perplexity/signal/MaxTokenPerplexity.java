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

/** Perplexity of the single least-confident token: the peak of hesitation the mean averages away. */
public final class MaxTokenPerplexity implements ConfidenceSignal {

  public String field() {
    return "max_token_perplexity";
  }

  public double compute(TokenConfidence c) {
    return c.chosen().isEmpty() ? 0.0 : Math.exp(-ConfidenceSignal.min(c.chosen()));
  }
}
