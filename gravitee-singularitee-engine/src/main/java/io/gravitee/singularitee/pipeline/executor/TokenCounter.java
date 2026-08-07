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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.singularitee.inference.api.text.EstimatedTokens;

/**
 * Counts (or estimates) the number of model tokens in a piece of text.
 *
 * <p>Used by {@link ChatWindowTrimmer} to express context-window budgets in
 * tokens. Engines with a real tokenizer (llama.cpp) supply exact counts;
 * everything else falls back to the {@link #estimator()} heuristic
 * (~{@value EstimatedTokens#CHARS_PER_TOKEN} characters per token).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface TokenCounter {
  /**
   * Returns the token count of {@code text} (0 for {@code null}/empty).
   *
   * @param text the text to count
   * @return the number of tokens
   */
  int count(String text);

  /**
   * Tokenizer-free estimator: ~{@value EstimatedTokens#CHARS_PER_TOKEN}
   * characters per token (see {@link EstimatedTokens}).
   */
  static TokenCounter estimator() {
    return EstimatedTokens::estimateTokens;
  }
}
