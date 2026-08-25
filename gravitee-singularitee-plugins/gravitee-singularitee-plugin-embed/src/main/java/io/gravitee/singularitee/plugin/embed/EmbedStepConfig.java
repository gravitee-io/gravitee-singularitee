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
package io.gravitee.singularitee.plugin.embed;

import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;

/**
 * Runtime config of an {@code embed} step.
 *
 * @param modelId     id of the embedding model
 * @param inputField  context key to embed; blank = the prompt
 * @param outputField context key receiving the vector as a JSON array string; blank = {@code <stepId>.embedding}
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record EmbedStepConfig(String modelId, String inputField, String outputField) implements
  ModelBoundConfig {
  public EmbedStepConfig {
    modelId = modelId == null ? "" : modelId;
    inputField = inputField == null ? "" : inputField;
    outputField = outputField == null ? "" : outputField;
  }
}
