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
package io.gravitee.singularitee.inference.decision;

import io.gravitee.singularitee.inference.api.decision.QuestionKind;

/**
 * A prompt ready for the forward pass, in the shape its family's backend reads.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface BackendPrompt {
  /** The tokens evaluated. */
  int[] tokens();

  /** How many scores the backend returns for this prompt. */
  int nOutputs();

  /**
   * Scored on label tokens at the last position (openjev, lev, nimble, custom) or on option end
   * markers (kev).
   */
  record Text(int[] tokens, int nOutputs) implements BackendPrompt {}

  /** Scored at one mask marker per option, in the column of {@code kind}. */
  record Laya(int[] tokens, QuestionKind kind, int nOutputs) implements BackendPrompt {}

  /** Every question of the request, one {@link DecisionOrder} per token. */
  record Clef(int[] tokens, DecisionOrder[] order, int nOutputs) implements BackendPrompt {}
}
