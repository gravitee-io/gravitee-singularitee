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

import io.gravitee.singularitee.protocol.InferStepConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace mapping of the per-pipeline chat-template override on infer steps:
 * {@code chat_template:} referencing a workspace {@code templates:} entry by id
 * (inline {@code content:} or {@code file:} resolved relative to the workspace
 * YAML's parent directory), inline Jinja source, and absence.
 */
class ChatTemplateOverrideYamlTest {

  private static final String TEMPLATE =
    "{% for message in messages %}<|{{ message.role }}|>{{ message.content }}{% endfor %}";

  private static InferStepConfig loadInferStep(Path workspace) throws IOException {
    var result = YamlWorkspaceLoader.load(workspace);
    assertThat(result.pipelines()).hasSize(1);
    var pipeline = result.pipelines().get(0);
    assertThat(pipeline.getStepsList()).hasSize(1);
    return pipeline.getSteps(0).getInferConfig();
  }

  @Test
  void template_id_reference_resolves_to_registered_content(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        templates:
          - id: glm-compact
            content: '%s'
        pipelines:
          - id: p
            entry: generate
            steps:
              - id: generate
                type: infer
                config:
                  model_id: llm
                  chat_template: glm-compact
      """.formatted(TEMPLATE)
    );

    var cfg = loadInferStep(workspace);

    assertThat(cfg.hasChatTemplate()).isTrue();
    assertThat(cfg.getChatTemplate()).isEqualTo(TEMPLATE);
  }

  @Test
  void template_id_reference_resolves_file_backed_template(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("templates"));
    Files.writeString(tmp.resolve("templates/glm-4-9b-compact.jinja"), TEMPLATE);
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        templates:
          - id: glm-compact
            file: templates/glm-4-9b-compact.jinja
        pipelines:
          - id: p
            entry: glm_fast
            steps:
              - id: glm_fast
                type: infer
                config:
                  model_id: llm
                  chat_template: glm-compact
      """
    );

    var cfg = loadInferStep(workspace);

    assertThat(cfg.hasChatTemplate()).isTrue();
    assertThat(cfg.getChatTemplate()).isEqualTo(TEMPLATE);
  }

  @Test
  void unregistered_value_is_treated_as_inline_jinja_source(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        pipelines:
          - id: p
            entry: generate
            steps:
              - id: generate
                type: infer
                config:
                  model_id: llm
                  chat_template: '%s'
      """.formatted(TEMPLATE)
    );

    var cfg = loadInferStep(workspace);

    assertThat(cfg.hasChatTemplate()).isTrue();
    assertThat(cfg.getChatTemplate()).isEqualTo(TEMPLATE);
  }

  @Test
  void absent_override_leaves_config_unset(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        pipelines:
          - id: p
            entry: generate
            steps:
              - id: generate
                type: infer
                config:
                  model_id: llm
      """
    );

    var cfg = loadInferStep(workspace);

    assertThat(cfg.hasChatTemplate()).isFalse();
  }

  @Test
  void template_file_may_not_escape_its_base_directory(@TempDir Path tmp) throws IOException {
    // template_file turns a YAML string into a file read whose content is rendered
    // into a prompt. A relative path must stay under its base, or '../' would
    // exfiltrate arbitrary readable files through the model's reply.
    Files.createDirectories(tmp.resolve("templates"));
    Files.writeString(tmp.resolve("secret.txt"), "TOP SECRET");
    Path workspace = tmp.resolve("templates/workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: escape
        models: []
        pipelines:
          - id: p
            entry: gen
            steps:
              - id: gen
                type: infer
                role: output
                config:
                  model_id: llm
                  prompt:
                    template_file: ../secret.txt
      """
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(workspace))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("resolves outside");
  }

  @Test
  void template_file_resolves_relative_to_the_workspace_directory(@TempDir Path tmp)
    throws IOException {
    Path sub = tmp.resolve("templates");
    Files.createDirectories(sub);
    Files.writeString(sub.resolve("t.jinja"), TEMPLATE);
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        templates:
          - id: glm-compact
            file: templates/t.jinja
        pipelines:
          - id: p
            entry: generate
            steps:
              - id: generate
                type: infer
                config:
                  model_id: llm
                  chat_template: glm-compact
      """
    );

    var cfg = loadInferStep(workspace);

    assertThat(cfg.hasChatTemplate()).isTrue();
    assertThat(cfg.getChatTemplate()).isEqualTo(TEMPLATE);
  }
}
