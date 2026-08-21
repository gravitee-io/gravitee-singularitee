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
 * Sampling parameters common to every text-generation backend; engines extend it with their own
 * fields. Every accessor returns {@code null} when the client left the parameter unset.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface GenerationRequest {
  /** Rendered prompt text, or {@code null} when the request carries messages instead. */
  String prompt();

  /** Maximum tokens to generate, or {@code null} for the engine default. */
  Integer maxTokens();

  /** Sampling temperature (0.0 to 2.0), or {@code null} for the engine default. */
  Float temperature();

  /** Nucleus sampling threshold (0.0 to 1.0), or {@code null} for the engine default. */
  Float topP();

  /** Presence penalty (-2.0 to 2.0), or {@code null} for the engine default. */
  Float presencePenalty();

  /** Frequency penalty (-2.0 to 2.0), or {@code null} for the engine default. */
  Float frequencyPenalty();

  /** Stop strings matched on decoded text by the batch engine, or {@code null} for none. */
  List<String> stop();

  /** Sampling seed for reproducibility, or {@code null} for a random one. */
  Integer seed();
}
