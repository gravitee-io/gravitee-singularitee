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
 * Named tag sets: a workspace-level {@code tags:} list declares reusable
 * reasoning/tool tag blocks by id, and a step's {@code tags:} value may be a
 * bare string referencing one — resolved by the loader so the proto always
 * carries the expanded TagConfig. Inline mappings keep working unchanged.
 */
class NamedTagsYamlTest {

  private static InferStepConfig loadInferStep(Path workspace) throws IOException {
    var result = YamlWorkspaceLoader.load(workspace);
    assertThat(result.pipelines()).hasSize(1);
    var pipeline = result.pipelines().get(0);
    return pipeline.getSteps(0).getInferConfig();
  }

  @Test
  void bare_string_tags_resolve_to_the_named_workspace_entry(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        tags:
          - id: chatml
            reasoning_open: "<think>"
            reasoning_close: "</think>"
            tool_open:
              - "<tool_call>"
              - "<function_call>"
            tool_close: "</tool_call>"
            reasoning_repeatable: true
        pipelines:
          - id: p
            entry: generate
            steps:
              - id: generate
                type: infer
                config:
                  model_id: llm
                  tags: chatml
      """
    );

    var cfg = loadInferStep(workspace);
    assertThat(cfg.getReasoningTags().getOpenTag()).isEqualTo("<think>");
    assertThat(cfg.getReasoningTags().getCloseTag()).isEqualTo("</think>");
    assertThat(cfg.getReasoningTags().getRepeatable()).isTrue();
    assertThat(cfg.getToolCallTags().getOpenTag()).isEqualTo("<tool_call>");
    assertThat(cfg.getToolCallTags().getOpenTagAlternativesList()).containsExactly(
      "<function_call>"
    );
    assertThat(cfg.getToolCallTags().getCloseTag()).isEqualTo("</tool_call>");
  }

  @Test
  void inline_tags_mapping_still_works(@TempDir Path tmp) throws IOException {
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
                  tags:
                    reasoning_open: "<think>"
                    reasoning_close: "</think>"
      """
    );

    var cfg = loadInferStep(workspace);
    assertThat(cfg.getReasoningTags().getOpenTag()).isEqualTo("<think>");
  }

  @Test
  void unknown_tags_id_fails_loading_with_the_offending_id(@TempDir Path tmp) throws IOException {
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
                  tags: nope
      """
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(workspace))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("nope");
  }

  @Test
  void duplicate_workspace_tag_ids_are_rejected(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        tags:
          - id: dup
            reasoning_open: "<a>"
          - id: dup
            reasoning_open: "<b>"
        pipelines: []
      """
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.load(workspace))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("dup");
  }
}
