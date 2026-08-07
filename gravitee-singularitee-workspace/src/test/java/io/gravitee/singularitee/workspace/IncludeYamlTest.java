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

class IncludeYamlTest {

  // ---------------------------------------------------------------------------
  // Include-merge tests
  // ---------------------------------------------------------------------------

  @Test
  void loads_and_merges_include_files(@TempDir Path tmp) throws IOException {
    // Loader resolves models/ and pipelines/ relative to workspace dir — create subdirs
    Path modelsDir = Files.createDirectories(tmp.resolve("models"));
    Path pipelinesDir = Files.createDirectories(tmp.resolve("pipelines"));

    Path mainYaml = tmp.resolve("main.yaml");
    Files.writeString(
      mainYaml,
      """
      workspace:
        name: test-workspace
        includes:
          models:
            - includes.yaml
          pipelines:
            - includes.yaml
        models:
          - id: main-model
            name: main/Test-Model
            type: llama_cpp
            llama_cpp:
              path: main.gguf
              n_ctx: 4096
      """
    );

    String includeContent = """
      workspace:
        models:
          - id: included-model
            name: included/Test-Model
            type: llama_cpp
            llama_cpp:
              path: included.gguf
              n_ctx: 2048
        pipelines:
          - id: test-pipeline
            name: Test Pipeline
            entry: test_step
            steps:
              - id: test_step
                type: infer
                config:
                  model_id: included-model
                  output_field: test.output
                  prompt:
                    messages:
                      - role: user
                        content: "{{prompt}}"
      """;

    Files.writeString(modelsDir.resolve("includes.yaml"), includeContent);
    Files.writeString(pipelinesDir.resolve("includes.yaml"), includeContent);

    var result = YamlWorkspaceLoader.load(mainYaml);

    assertThat(result.models()).hasSize(2);
    var modelIds = result.models().stream().map(ModelLoadRequest::modelId).toList();
    assertThat(modelIds).containsExactlyInAnyOrder("main-model", "included-model");
    assertThat(result.pipelines()).hasSize(1);
    assertThat(result.pipelines().get(0).getPipelineId()).isEqualTo("test-pipeline");
    assertThat(result.pipelines().get(0).getSteps(0).getInferConfig().getModelId()).isEqualTo(
      "included-model"
    );
  }

  @Test
  void handles_missing_include_files_gracefully(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("models"));

    Path mainYaml = tmp.resolve("main.yaml");
    Files.writeString(
      mainYaml,
      """
      workspace:
        name: test-missing-include
        includes:
          models:
            - nonexistent.yaml
        models:
          - id: main-only-model
            name: main/Only-Model
            type: llama_cpp
            llama_cpp:
              path: main.gguf
              n_ctx: 4096
      """
    );

    var result = YamlWorkspaceLoader.load(mainYaml);
    assertThat(result.models()).hasSize(1);
    assertThat(result.models().get(0).modelId()).isEqualTo("main-only-model");
  }

  @Test
  void merges_multiple_include_files(@TempDir Path tmp) throws IOException {
    Path modelsDir = Files.createDirectories(tmp.resolve("models"));
    Path pipelinesDir = Files.createDirectories(tmp.resolve("pipelines"));

    Path mainYaml = tmp.resolve("main.yaml");
    Files.writeString(
      mainYaml,
      """
      workspace:
        name: multi-include-test
        includes:
          models:
            - includes1.yaml
          pipelines:
            - includes2.yaml
        models: []
        pipelines: []
      """
    );

    Files.writeString(
      modelsDir.resolve("includes1.yaml"),
      """
      workspace:
        models:
          - id: model-from-include1
            name: include1/Model
            type: llama_cpp
            llama_cpp:
              path: include1.gguf
              n_ctx: 2048
      """
    );

    Files.writeString(
      pipelinesDir.resolve("includes2.yaml"),
      """
      workspace:
        pipelines:
          - id: pipeline-from-include2
            name: Include2 Pipeline
            entry: test_step
            steps:
              - id: test_step
                type: infer
                config:
                  model_id: model-from-include1
                  output_field: output
                  prompt:
                    template: "Test from include2"
      """
    );

    var result = YamlWorkspaceLoader.load(mainYaml);
    assertThat(result.models()).hasSize(1);
    assertThat(result.pipelines()).hasSize(1);
    assertThat(result.models().get(0).modelId()).isEqualTo("model-from-include1");
    assertThat(result.pipelines().get(0).getPipelineId()).isEqualTo("pipeline-from-include2");
  }

  // ---------------------------------------------------------------------------
  // Template registry tests
  // ---------------------------------------------------------------------------

  @Test
  void template_id_resolved_from_inline_template_in_same_file(@TempDir Path tmp)
    throws IOException {
    Path ws = tmp.resolve("ws.yaml");
    Files.writeString(
      ws,
      """
      workspace:
        name: tmpl-inline
        templates:
          - id: my-tmpl
            content: "Hello, {{ name }}!"
        models:
          - id: m
            name: test/m
            type: llama_cpp
            llama_cpp:
              path: m.gguf
              n_ctx: 2048
        pipelines:
          - id: p
            name: P
            entry: s
            steps:
              - id: s
                type: infer
                config:
                  model_id: m
                  output_field: s.output
                  prompt:
                    template_id: my-tmpl
      """
    );

    var result = YamlWorkspaceLoader.load(ws);
    assertThat(result.pipelines()).hasSize(1);
    assertThat(result.pipelines().get(0).getSteps(0).getInferConfig().getRawTemplate()).isEqualTo(
      "Hello, {{ name }}!"
    );
  }

  @Test
  void template_id_resolved_from_included_template_file(@TempDir Path tmp) throws IOException {
    Path templatesDir = Files.createDirectories(tmp.resolve("templates"));

    Files.writeString(
      templatesDir.resolve("templates.yaml"),
      """
      workspace:
        templates:
          - id: greet
            content: "Hi {{ user }}!"
      """
    );

    Path ws = tmp.resolve("ws.yaml");
    Files.writeString(
      ws,
      """
      workspace:
        name: tmpl-from-include
        includes:
          templates:
            - templates.yaml
        models:
          - id: m
            name: test/m
            type: llama_cpp
            llama_cpp:
              path: m.gguf
              n_ctx: 2048
        pipelines:
          - id: p
            name: P
            entry: s
            steps:
              - id: s
                type: infer
                config:
                  model_id: m
                  output_field: s.output
                  prompt:
                    template_id: greet
      """
    );

    var result = YamlWorkspaceLoader.load(ws);
    assertThat(result.pipelines().get(0).getSteps(0).getInferConfig().getRawTemplate()).isEqualTo(
      "Hi {{ user }}!"
    );
  }

  @Test
  void template_id_resolved_from_file_field(@TempDir Path tmp) throws IOException {
    // template content lives in a .jinja2 file, referenced via file: in the template def
    Path jinja = tmp.resolve("prompt.jinja2");
    Files.writeString(jinja, "Answer: {{ answer }}");

    Path ws = tmp.resolve("ws.yaml");
    Files.writeString(
      ws,
      """
      workspace:
        name: tmpl-from-file
        templates:
          - id: answer-tmpl
            file: prompt.jinja2
        models:
          - id: m
            name: test/m
            type: llama_cpp
            llama_cpp:
              path: m.gguf
              n_ctx: 2048
        pipelines:
          - id: p
            name: P
            entry: s
            steps:
              - id: s
                type: infer
                config:
                  model_id: m
                  output_field: s.output
                  prompt:
                    template_id: answer-tmpl
      """
    );

    var result = YamlWorkspaceLoader.load(ws);
    assertThat(result.pipelines().get(0).getSteps(0).getInferConfig().getRawTemplate()).isEqualTo(
      "Answer: {{ answer }}"
    );
  }

  @Test
  void unknown_template_id_throws(@TempDir Path tmp) throws IOException {
    Path ws = tmp.resolve("ws.yaml");
    Files.writeString(
      ws,
      """
      workspace:
        name: tmpl-missing
        models:
          - id: m
            name: test/m
            type: llama_cpp
            llama_cpp:
              path: m.gguf
              n_ctx: 2048
        pipelines:
          - id: p
            name: P
            entry: s
            steps:
              - id: s
                type: infer
                config:
                  model_id: m
                  output_field: s.output
                  prompt:
                    template_id: does-not-exist
      """
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(ws)).hasMessageContaining("does-not-exist");
  }
}
