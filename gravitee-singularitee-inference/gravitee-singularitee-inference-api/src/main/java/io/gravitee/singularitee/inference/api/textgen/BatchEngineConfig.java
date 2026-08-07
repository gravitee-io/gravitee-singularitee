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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * Configuration for batch inference engine.
 * Defines the capacity and operational parameters.
 *
 * @param maxConcurrentSequences Maximum number of sequences that can be processed simultaneously
 * @param queueCapacity Maximum number of pending sequences that can wait
 * @param enableAutoStart Whether to automatically start pending sequences when slots become available
 * @param promptCache Whether to enable the cross-request KV prefix cache (effective only when the
 *                    adapter supports it — see {@code EngineAdapter.tokenizePrompt})
 * @param promptCacheMinTokens Minimum shared-prefix length (tokens) required to prefer a warm slot
 *                             over a cold one when no cache key matches
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record BatchEngineConfig(
  int maxConcurrentSequences,
  int queueCapacity,
  boolean enableAutoStart,
  boolean promptCache,
  int promptCacheMinTokens
) {
  /** Default minimum shared-prefix length for warm-slot selection. */
  public static final int DEFAULT_PROMPT_CACHE_MIN_TOKENS = 64;

  public BatchEngineConfig {
    if (maxConcurrentSequences <= 0) {
      throw new IllegalArgumentException("maxConcurrentSequences must be positive");
    }
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }
  }

  /** Compatibility constructor — prompt cache disabled. */
  public BatchEngineConfig(int maxConcurrentSequences, int queueCapacity, boolean enableAutoStart) {
    this(
      maxConcurrentSequences,
      queueCapacity,
      enableAutoStart,
      false,
      DEFAULT_PROMPT_CACHE_MIN_TOKENS
    );
  }

  /** Returns a copy with the prompt-cache settings replaced. */
  public BatchEngineConfig withPromptCache(boolean enabled, int minTokens) {
    return new BatchEngineConfig(
      maxConcurrentSequences,
      queueCapacity,
      enableAutoStart,
      enabled,
      minTokens > 0 ? minTokens : DEFAULT_PROMPT_CACHE_MIN_TOKENS
    );
  }

  public static BatchEngineConfig defaults() {
    return new BatchEngineConfig(8, 100, true);
  }

  public static BatchEngineConfig of(int maxConcurrentSequences) {
    return new BatchEngineConfig(maxConcurrentSequences, 100, true);
  }

  public static BatchEngineConfig of(int maxConcurrentSequences, int queueCapacity) {
    return new BatchEngineConfig(maxConcurrentSequences, queueCapacity, true);
  }
}
