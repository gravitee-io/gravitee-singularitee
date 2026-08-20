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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code task:} and {@code visible:} on models and pipelines. */
class PublicationYamlTest {

  private static final String YAML = """
    workspace:
      name: test
      models:
        - id: llm
          name: Qwen/Qwen3-0.6B-GGUF
          type: llama_cpp
          visible: false
          llama_cpp:
            path: Qwen3-0.6B-Q8_0.gguf
        - id: pii
          type: regex
          task: token-classification
          modalities: [text]
          regex:
            patterns:
              - pattern: '\\bsecret\\b'
                entity_type: SECRET
      pipelines:
        - id: agent
          entry: generate
          steps:
            - id: generate
              type: infer
              role: output
              config:
                model_id: llm
                output_field: generate.output
        - id: internal
          entry: generate
          visible: false
          task: text-generation
          modalities: [text, image]
          steps:
            - id: generate
              type: infer
              role: output
              config:
                model_id: llm
                output_field: generate.output
    """;

  @Test
  void reads_task_and_visibility_off_models(@TempDir Path tmp) throws IOException {
    var result = load(tmp);

    var llm = result.models().get(0);
    assertThat(llm.modelId()).isEqualTo("llm");
    assertThat(llm.visible()).isFalse();
    assertThat(llm.task()).isEmpty();

    var pii = result.clientLocalModels().get(0).definition();
    assertThat(pii.id()).isEqualTo("pii");
    assertThat(pii.task()).isEqualTo("token-classification");
    assertThat(pii.isVisible()).isTrue();
    assertThat(pii.modalities()).containsExactly("text");
  }

  @Test
  void reads_declared_modalities_off_models_and_pipelines(@TempDir Path tmp) throws IOException {
    var result = load(tmp);

    // Undeclared on the llama.cpp model: the engine is asked at registration instead.
    assertThat(result.models().get(0).modalities()).isEmpty();

    assertThat(result.pipelines().get(0).getInputModalitiesList()).isEmpty();
    assertThat(result.pipelines().get(1).getInputModalitiesList()).containsExactly("text", "image");
  }

  @Test
  void reads_task_and_visibility_off_pipelines(@TempDir Path tmp) throws IOException {
    var pipelines = load(tmp).pipelines();

    var agent = pipelines.get(0);
    assertThat(agent.getPipelineId()).isEqualTo("agent");
    assertThat(agent.getHidden()).isFalse();
    // Undeclared: left blank here, derived at registration from the output model.
    assertThat(agent.getTask()).isEmpty();

    var internal = pipelines.get(1);
    assertThat(internal.getHidden()).isTrue();
    assertThat(internal.getTask()).isEqualTo("text-generation");
  }

  @Test
  void publishes_everything_by_default(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      YAML.replace("      visible: false\n", "").replace("          visible: false\n", "")
    );

    var result = YamlWorkspaceLoader.load(workspace);

    assertThat(result.models().get(0).visible()).isTrue();
    assertThat(result.pipelines()).allSatisfy(p -> assertThat(p.getHidden()).isFalse());
  }

  @Test
  void rejects_a_task_slug_the_catalogue_does_not_publish(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      YAML.replace("task: token-classification", "task: token_classification")
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(workspace))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pii")
      .hasMessageContaining("token_classification");
  }

  @Test
  void rejects_a_modality_nothing_checks_for(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      YAML.replace("modalities: [text, image]", "modalities: [text, vision]")
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(workspace))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("internal")
      .hasMessageContaining("vision");
  }

  private static YamlWorkspaceLoader.WorkspaceRequests load(Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(workspace, YAML);
    return YamlWorkspaceLoader.load(workspace);
  }
}
