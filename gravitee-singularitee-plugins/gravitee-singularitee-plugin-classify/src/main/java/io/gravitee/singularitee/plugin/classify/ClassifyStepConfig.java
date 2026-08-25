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
package io.gravitee.singularitee.plugin.classify;

import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;

/**
 * Runtime config of a {@code classify} step.
 *
 * @param modelId     id of the classifier model
 * @param inputField  context key to classify; blank = the prompt
 * @param outputField context key receiving the top label; the score lands in {@code <outputField>.score}
 * @param threshold   minimum score for downstream conditions; zero when unset
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ClassifyStepConfig(
  String modelId,
  String inputField,
  String outputField,
  float threshold
) implements ModelBoundConfig {
  public ClassifyStepConfig {
    modelId = modelId == null ? "" : modelId;
    inputField = inputField == null ? "" : inputField;
    outputField = outputField == null ? "" : outputField;
    threshold = threshold > 0 ? threshold : 0;
  }
}
