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
package io.gravitee.singularitee.engine.api.registry;

/**
 * A pipeline declares a step type whose executor is absent because its license feature
 * is not licensed on this node (or its plugin is missing). Raised at registration so the
 * workspace load fails with the feature named: a safety-relevant step silently absent is
 * never acceptable.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class UnlicensedStepException extends IllegalStateException {

  private final String pipelineId;
  private final String stepType;
  private final String feature;

  public UnlicensedStepException(
    String pipelineId,
    String stepId,
    String stepType,
    String feature
  ) {
    super(
      "Pipeline '" +
        pipelineId +
        "' step '" +
        stepId +
        "' uses step type '" +
        stepType +
        "' which requires license feature '" +
        feature +
        "'; the feature is not licensed on this node (or its plugin is not installed)"
    );
    this.pipelineId = pipelineId;
    this.stepType = stepType;
    this.feature = feature;
  }

  public String pipelineId() {
    return pipelineId;
  }

  public String stepType() {
    return stepType;
  }

  public String feature() {
    return feature;
  }
}
