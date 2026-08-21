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
 * Prompt measurements returned by {@code EngineAdapter.validateRequest} before a request is queued.
 *
 * @param promptTokens tokens in the prompt
 * @param contextTokens context window of the slot, in tokens
 * @param estimatedGenerationTokens tokens the request may generate, {@code 0} if unknown
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record PromptStats(int promptTokens, int contextTokens, int estimatedGenerationTokens) {
  public PromptStats {
    if (promptTokens < 0) {
      throw new IllegalArgumentException("promptTokens must be non-negative");
    }
    if (contextTokens <= 0) {
      throw new IllegalArgumentException("contextTokens must be positive");
    }
  }

  /** Whether the prompt leaves room for at least one generated token. */
  public boolean fitsInContext() {
    return promptTokens < contextTokens;
  }
}
