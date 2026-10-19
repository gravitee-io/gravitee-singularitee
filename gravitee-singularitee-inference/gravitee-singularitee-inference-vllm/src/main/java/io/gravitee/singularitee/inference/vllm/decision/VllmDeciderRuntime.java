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
package io.gravitee.singularitee.inference.vllm.decision;

import io.gravitee.vllm.decision.DecisionException;
import io.gravitee.vllm.decision.VllmDecider;
import java.util.Map;

/**
 * {@link VllmDecisionModel.Runtime} over a loaded {@link VllmDecider}: passes the body through
 * and turns a refusal of the whole request into the engine contract's exception.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class VllmDeciderRuntime implements VllmDecisionModel.Runtime {

  private final VllmDecider decider;

  VllmDeciderRuntime(VllmDecider decider) {
    this.decider = decider;
  }

  @Override
  public Map<String, Object> decide(Map<String, Object> body) {
    try {
      return decider.decide(body);
    } catch (DecisionException e) {
      throw VllmDecisionModel.refused(e.code(), e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    decider.close();
  }
}
