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
package io.gravitee.singularitee.plugin.guard;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import java.util.List;

/**
 * Runtime config of a {@code guard} step: the classifier to call, the triggers that fire
 * it and what to do when it fires.
 *
 * @param modelId              the classifier model id
 * @param inputField           context key to check; blank = the prompt
 * @param outputField          context key receiving the (possibly redacted) text; blank =
 *                             {@code <stepId>.redacted}
 * @param action               what happens when a trigger matches
 * @param triggers             conditions of which any one fires the guard
 * @param message              Jinja reject message; blank = none
 * @param redactWithEntityType replace spans with {@code [ENTITY_TYPE]} instead of stars
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record GuardStepConfig(
  String modelId,
  String inputField,
  String outputField,
  GuardAction action,
  List<GuardTrigger> triggers,
  String message,
  boolean redactWithEntityType
) implements ModelBoundConfig {
  public GuardStepConfig {
    modelId = modelId == null ? "" : modelId;
    inputField = inputField == null ? "" : inputField;
    outputField = outputField == null ? "" : outputField;
    action = action == null ? GuardAction.REJECT : action;
    triggers = triggers == null ? List.of() : List.copyOf(triggers);
    message = message == null ? "" : message;
  }

  /** One trigger: a classifier label and the minimum score that fires it. */
  public record GuardTrigger(String label, float score) {
    public GuardTrigger {
      label = label == null ? "" : label;
    }
  }
}
