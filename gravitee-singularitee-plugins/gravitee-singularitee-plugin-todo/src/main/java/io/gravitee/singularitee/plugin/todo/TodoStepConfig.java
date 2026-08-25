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
package io.gravitee.singularitee.plugin.todo;

import io.gravitee.singularitee.engine.api.pipeline.model.BranchingConfig;
import java.util.List;

/**
 * Runtime config of a {@code todo} step.
 *
 * @param handledStepId step to branch to after consuming a todo call (usually the infer
 *                      step); blank means the plain {@code next_step} edge
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record TodoStepConfig(String handledStepId) implements BranchingConfig {
  public TodoStepConfig {
    handledStepId = handledStepId == null ? "" : handledStepId;
  }

  @Override
  public List<String> branchTargets() {
    return handledStepId.isBlank() ? List.of() : List.of(handledStepId);
  }
}
