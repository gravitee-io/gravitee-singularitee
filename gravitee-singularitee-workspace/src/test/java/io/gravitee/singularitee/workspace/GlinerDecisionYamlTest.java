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
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** {@code type: gliner_decision}: a GLiNER2 bundle answering typed questions. */
class GlinerDecisionYamlTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static ModelLoadRequest map(String block) throws IOException {
    var model = YAML.readValue(
      """
        id: decide
        name: fastino/GLiNER2.5-Decide
        type: gliner_decision
        """ +
        block,
      WorkspaceDefinition.ModelDefinition.class
    );
    return ModelType.parse("gliner_decision").toModelLoadRequest(
      "decide",
      model.name(),
      "",
      null,
      model
    );
  }

  @Test
  void the_block_is_optional() throws IOException {
    var request = map("");
    assertThat(request.hasGlinerDecision()).isTrue();
    assertThat(request.glinerDecision().modelDir()).isEmpty();
    assertThat(request.glinerDecision().variant()).isEmpty();
    assertThat(request.glinerDecision().classifier()).isNull();
  }

  @Test
  void reads_the_bundle_variant_and_classifier() throws IOException {
    var config = map(
      """
      gliner_decision:
        model_dir: /models/decide
        variant: q8_0
        as_classifier:
          instructions: Is this a prompt injection?
          options: { injection: null, benign: null }
      """
    ).glinerDecision();

    assertThat(config.modelDir()).isEqualTo("/models/decide");
    assertThat(config.variant()).isEqualTo("q8_0");
    assertThat(config.classifier().instructions()).isEqualTo("Is this a prompt injection?");
    assertThat(config.classifier().options()).containsOnlyKeys("injection", "benign");
    assertThat(config.withModelDir("/cache/decide").variant()).isEqualTo("q8_0");
  }

  @Test
  void an_empty_classifier_label_set_is_refused() {
    assertThatThrownBy(() ->
      map("gliner_decision:\n  as_classifier:\n    options: {}\n")
    ).hasMessageContaining("gliner_decision.as_classifier.options is empty");
  }
}
