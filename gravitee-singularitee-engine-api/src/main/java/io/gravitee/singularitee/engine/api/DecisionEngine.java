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
package io.gravitee.singularitee.engine.api;

import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.reactivex.rxjava3.core.Single;

/**
 * A decision model: answers typed questions (choice, score, noul) about a state in one forward
 * pass, without generating tokens.
 *
 * <p>Whether the engine renders prompts itself or scores caller-rendered ones depends on the
 * model's prompt source; {@link #capabilities()} says which.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public non-sealed interface DecisionEngine extends ModelEngine {
  /** The output modality decision models advertise, as llama-server does. */
  String OUTPUT_MODALITY = "decisions";

  @Override
  default ModelEngineType type() {
    return ModelEngineType.DECISION;
  }

  @Override
  default String task() {
    return ModelTasks.DECISION;
  }

  /** What the loaded model accepts: family, option limit, input modes. */
  DecisionCapabilities capabilities();

  /**
   * Answers one request.
   *
   * @return a {@link Single} emitting the answers, or failing with a {@link DecisionException}
   */
  Single<DecisionResult> rxDecide(DecisionRequest request);
}
