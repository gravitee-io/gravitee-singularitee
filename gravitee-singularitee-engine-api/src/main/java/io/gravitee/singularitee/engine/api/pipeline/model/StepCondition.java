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

/**
 * A condition over a pipeline-context field, shared by the break and loop steps.
 *
 * @param kind       what is compared
 * @param inputField the context field evaluated
 * @param matchValue the reference for the string kinds (may be null)
 * @param threshold  the reference for the score kinds
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record StepCondition(
  ConditionKind kind,
  String inputField,
  String matchValue,
  float threshold
) {
  public StepCondition {
    if (kind == null) {
      throw new IllegalArgumentException("Condition kind is required");
    }
    inputField = inputField == null ? "" : inputField;
  }
}
