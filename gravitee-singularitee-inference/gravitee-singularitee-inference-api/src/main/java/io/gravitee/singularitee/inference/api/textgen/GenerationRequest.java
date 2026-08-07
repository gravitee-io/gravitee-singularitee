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

import java.util.List;

/**
 * Interface for generation requests across different inference engines.
 * Provides common interface for generation parameters while allowing engine-specific extensions.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface GenerationRequest {
  /**
   * @return The prompt text or null if using messages
   */
  String prompt();

  /**
   * @return Maximum number of tokens to generate, or null for unlimited
   */
  Integer maxTokens();

  /**
   * @return Temperature for sampling (0.0 to 2.0), or null for default
   */
  Float temperature();

  /**
   * @return Top-p sampling parameter (0.0 to 1.0), or null for default
   */
  Float topP();

  /**
   * @return Presence penalty (-2.0 to 2.0), or null for default
   */
  Float presencePenalty();

  /**
   * @return Frequency penalty (-2.0 to 2.0), or null for default
   */
  Float frequencyPenalty();

  /**
   * @return List of stop sequences, or null for none
   */
  List<String> stop();

  /**
   * @return Random seed for reproducibility, or null for random
   */
  Integer seed();
}
