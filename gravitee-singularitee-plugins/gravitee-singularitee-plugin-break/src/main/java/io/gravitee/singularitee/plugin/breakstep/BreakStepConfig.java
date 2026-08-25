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
package io.gravitee.singularitee.plugin.breakstep;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;

/**
 * Runtime config of a {@code break} step: the halt condition and the context key whose
 * value becomes the final response when the pipeline halts.
 *
 * @param outputField context key whose value becomes the response on break; may be blank
 * @param condition   the halt condition, or {@code null} when the step never halts
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record BreakStepConfig(String outputField, StepCondition condition) {
  public BreakStepConfig {
    outputField = outputField == null ? "" : outputField;
  }
}
