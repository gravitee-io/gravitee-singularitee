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
import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code type: vllm_decision}: runtime models, and custom models with their own keys. */
class VllmDecisionYamlTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static ModelLoadRequest load(Path tmp, String block) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
        workspace:
          name: test
          templates:
            - id: judge-prompt
              content: "{{ state }} Answer: ("
          models:
            - id: judge
              name: Qwen/Qwen3-0.6B
              type: vllm_decision
              vllm_decision:
        """ +
        block.indent(8)
    );
    return YamlWorkspaceLoader.load(workspace, null, TestStepPlugins.codecs()).models().getFirst();
  }

  @Test
  void a_runtime_model_keeps_its_runtime_options(@TempDir Path tmp) throws IOException {
    var config = load(tmp, "family: decision2\ndevice: cpu\nthreads: 4\n").vllmDecision();

    assertThat(config.custom()).isFalse();
    assertThat(config.family()).isEqualTo("decision2");
    assertThat(config.device()).isEqualTo("cpu");
    assertThat(config.threads()).isEqualTo(4);
    assertThat(config.vllm()).isNull();
  }

  @Test
  void a_custom_model_reads_codes_prompt_and_engine(@TempDir Path tmp) throws IOException {
    var config = load(
      tmp,
      """
      family: custom
      codes: [A, B, C]
      debias: reverse
      prompt:
        template: chat
        prefill: "Answer ("
      temperatures:
        choice: 0.8
      vllm:
        max_model_len: 4096
        gpu_memory_utilization: 0.3
      """
    ).vllmDecision();

    assertThat(config.custom()).isTrue();
    assertThat(config.codes()).containsExactly("A", "B", "C");
    assertThat(config.reverse()).isTrue();
    assertThat(config.promptSource()).isEqualTo("chat");
    assertThat(config.prefill()).isEqualTo("Answer (");
    assertThat(config.temperatures()).containsEntry("choice", 0.8f);
    assertThat(config.vllm().maxModelLen()).isEqualTo(4096);
    assertThat(config.vllm().gpuMemoryUtilization()).isEqualTo(0.3);
  }

  @Test
  void a_custom_model_defaults_to_caller_prompts_and_resolves_template_ids(@TempDir Path tmp)
    throws IOException {
    assertThat(load(tmp, "family: custom\n").vllmDecision().promptSource()).isEqualTo("none");

    var templated = load(tmp, "family: custom\nprompt:\n  template: judge-prompt\n").vllmDecision();
    assertThat(templated.promptSource()).isEqualTo("template");
    assertThat(templated.promptTemplate()).isEqualTo("{{ state }} Answer: (");
  }

  /** Maps one model entry the way the loader does, so a refusal surfaces as its exception. */
  private static ModelLoadRequest map(String block) throws IOException {
    var model = YAML.readValue(
      """
        id: judge
        name: Qwen/Qwen3-0.6B
        type: vllm_decision
        vllm_decision:
        """ +
        block.indent(2),
      WorkspaceDefinition.ModelDefinition.class
    );
    return ModelType.VLLM_DECISION.toModelLoadRequest("judge", model.name(), "", null, model);
  }

  @Test
  void the_loader_skips_a_refused_model(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        models:
          - id: judge
            name: Qwen/Qwen3-0.6B
            type: vllm_decision
            vllm_decision:
              family: clef
      """
    );
    assertThat(
      YamlWorkspaceLoader.load(workspace, null, TestStepPlugins.codecs()).models()
    ).isEmpty();
  }

  @Test
  void keys_of_one_kind_are_refused_on_the_other() {
    assertThatThrownBy(() -> map("family: decision2\ncodes: [A, B]\n")).hasMessageContaining(
      "vllm_decision.codes is for family: custom"
    );
    assertThatThrownBy(() -> map("vllm:\n  max_model_len: 4096\n")).hasMessageContaining(
      "vllm_decision.vllm is for family: custom"
    );
    assertThatThrownBy(() -> map("family: custom\ndevice: cpu\n")).hasMessageContaining(
      "vllm_decision.device is for the vllm-sr runtime"
    );
    assertThatThrownBy(() ->
      map("family: custom\nprompt:\n  template: model\n")
    ).hasMessageContaining("no decision template");
    assertThatThrownBy(() -> map("family: clef\n")).hasMessageContaining(
      "decision1, decision2 or custom"
    );
    assertThatThrownBy(() -> map("family: custom\ndebias: shuffle\n")).hasMessageContaining(
      "vllm_decision.debias must be none or reverse"
    );
  }

  @Test
  void a_classifier_may_take_every_label_from_the_request() throws IOException {
    var zeroShot = map("as_classifier: {}\n").vllmDecision().classifier();
    assertThat(zeroShot).isNotNull();
    assertThat(zeroShot.instructions()).isNull();
    assertThat(zeroShot.options()).isNull();

    assertThatThrownBy(() -> map("as_classifier:\n  options: {}\n")).hasMessageContaining(
      "as_classifier.options is empty"
    );
  }

  @Test
  void an_unknown_template_id_skips_the_model(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        models:
          - id: judge
            name: Qwen/Qwen3-0.6B
            type: vllm_decision
            vllm_decision:
              family: custom
              prompt:
                template: nope
      """
    );
    assertThat(
      YamlWorkspaceLoader.load(workspace, null, TestStepPlugins.codecs()).models()
    ).isEmpty();
  }
}
