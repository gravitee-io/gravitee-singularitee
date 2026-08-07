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
 * Statistics about a generation request.
 * Used to validate requests before processing and track resource usage.
 *
 * @param promptTokens Number of tokens in the prompt
 * @param contextTokens Total context capacity
 * @param estimatedGenerationTokens Estimated tokens to generate (if available)
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

  /**
   * Checks if the prompt fits within the context window.
   * @return true if prompt tokens are less than context tokens
   */
  public boolean fitsInContext() {
    return promptTokens < contextTokens;
  }
}
