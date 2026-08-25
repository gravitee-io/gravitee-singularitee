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

import java.util.Map;

/**
 * Parses and validates one step type's YAML {@code config:} block into the typed config
 * object its executor consumes. Owned by the step's plugin: nothing about a step's config
 * shape is known to the platform, and validation failures surface at workspace load with
 * the step id in the message.
 *
 * @param <C> the typed config the executor of this step type receives
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface StepConfigCodec<C> {
  /**
   * @param stepId the step being parsed, for error messages
   * @param config the raw YAML block, possibly empty, never null
   * @param ctx    the workspace-level material a config may reference
   * @return the typed config
   * @throws IllegalArgumentException when the block is invalid
   */
  C parse(String stepId, Map<String, Object> config, StepCodecContext ctx);
}
