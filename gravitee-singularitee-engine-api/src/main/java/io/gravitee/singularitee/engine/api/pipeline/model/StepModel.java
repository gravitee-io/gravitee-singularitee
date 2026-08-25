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
package io.gravitee.singularitee.engine.api.pipeline.model;

import io.gravitee.singularitee.protocol.StepRole;

/**
 * One node of a pipeline DAG, server-internal. Step definitions never cross the wire:
 * the workspace loader builds this model from YAML and the engine executes it directly.
 *
 * <p>{@code type} is the step's canonical type string ({@code "infer"}, {@code "guard"},
 * {@code "route"}, ...): the key the executor registry dispatches on. {@code config}
 * is the step's typed configuration object, owned by the executor that declares the type;
 * the dispatcher hands it back to that executor through {@code StepExecutor.extractConfig}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepModel(String id, String type, StepRole role, Object config) {
  public StepModel {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Step id is required");
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Step '" + id + "' requires a type");
    }
    if (role == null) {
      role = StepRole.STEP_ROLE_UNSPECIFIED;
    }
  }
}
