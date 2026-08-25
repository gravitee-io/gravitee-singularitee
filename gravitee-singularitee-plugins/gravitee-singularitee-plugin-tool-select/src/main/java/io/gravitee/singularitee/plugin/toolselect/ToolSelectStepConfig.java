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
package io.gravitee.singularitee.plugin.toolselect;

import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import java.util.List;

/**
 * Runtime config of a {@code tool_select} step. Strings are never null (blank means
 * unset) and numeric zero means the executor default.
 *
 * @param modelId             the zero-shot classifier to call
 * @param inputField          context key to classify; blank for the last user message
 * @param batchSize           tools per classify call; zero for the default
 * @param threshold           minimum score to select a tool; zero for the default
 * @param labelTemplate       Jinja template condensing a tool into a label; blank for the built-in
 * @param alwaysInclude       tool names unioned into any non-empty shortlist
 * @param trimDescriptions    inject condensed descriptions for the selected tools
 * @param descriptionTemplate Jinja template producing the trimmed description; blank for the built-in
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ToolSelectStepConfig(
  String modelId,
  String inputField,
  int batchSize,
  float threshold,
  String labelTemplate,
  List<String> alwaysInclude,
  boolean trimDescriptions,
  String descriptionTemplate
) implements ModelBoundConfig {
  public ToolSelectStepConfig {
    modelId = modelId == null ? "" : modelId;
    inputField = inputField == null ? "" : inputField;
    labelTemplate = labelTemplate == null ? "" : labelTemplate;
    alwaysInclude = alwaysInclude == null ? List.of() : List.copyOf(alwaysInclude);
    descriptionTemplate = descriptionTemplate == null ? "" : descriptionTemplate;
  }

  /** A config naming only its model; every other component is unset. */
  public static ToolSelectStepConfig ofModel(String modelId) {
    return new ToolSelectStepConfig(modelId, null, 0, 0f, null, null, false, null);
  }
}
