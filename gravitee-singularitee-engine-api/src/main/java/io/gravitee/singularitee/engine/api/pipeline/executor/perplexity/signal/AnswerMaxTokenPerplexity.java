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

/** Peak token perplexity over the answer tokens alone; falls back to the full-generation peak. */
public final class AnswerMaxTokenPerplexity implements ConfidenceSignal {

  public String field() {
    return "answer_max_token_perplexity";
  }

  public double compute(TokenConfidence c) {
    var scope = c.answer().isEmpty() ? c.chosen() : c.answer();
    return scope.isEmpty() ? 0.0 : Math.exp(-ConfidenceSignal.min(scope));
  }
}
