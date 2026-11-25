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
package io.gravitee.singularitee.standalone.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReleaseChangesTest {

  private static final String RUNNING = """
    workspace:
      name: demo
      models:
        - id: llm
          name: org/llm
          type: llama_cpp
          llama_cpp:
            path: llm.gguf
            n_ctx: 4096
      remote:
        default:
          host: 127.0.0.1
          port: 9090
      templates:
        - id: greeting
          content: "Hello {{ name }}"
      pipelines:
        - id: chat
          entry: s
          steps:
            - id: s
              type: echo
    """;

  @Test
  void a_pipeline_change_is_pipelines_or_templates_only() {
    String candidate = RUNNING.replace("type: echo", "type: echo\n          role: output");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isTrue();
  }

  @Test
  void a_template_change_is_pipelines_or_templates_only() {
    String candidate = RUNNING.replace("Hello", "Bye");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isTrue();
  }

  @Test
  void key_order_does_not_count_as_a_change() {
    String candidate = """
      workspace:
        remote:
          default:
            port: 9090
            host: 127.0.0.1
        models:
          - type: llama_cpp
            llama_cpp:
              n_ctx: 4096
              path: llm.gguf
            name: org/llm
            id: llm
        name: demo
      """;

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isTrue();
  }

  @Test
  void a_model_change_needs_a_restart() {
    String candidate = RUNNING.replace("n_ctx: 4096", "n_ctx: 8192");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isFalse();
  }

  @Test
  void a_remote_endpoint_change_needs_a_restart() {
    String candidate = RUNNING.replace("port: 9090", "port: 9091");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isFalse();
  }

  @Test
  void any_other_workspace_key_change_needs_a_restart() {
    String candidate = RUNNING.replace("name: demo", "name: renamed");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, candidate)).isFalse();
  }

  @Test
  void a_release_that_does_not_parse_needs_a_restart() {
    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(RUNNING, "workspace: [")).isFalse();
  }

  @Test
  void an_unknown_running_release_needs_a_restart() {
    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(null, RUNNING)).isFalse();
  }

  @Test
  void a_changed_template_that_a_model_reads_needs_a_restart() {
    String running = """
      workspace:
        models:
          - id: judge
            type: llama_cpp_decision
            llama_cpp_decision:
              prompt:
                template: judge_prompt
        templates:
          - id: judge_prompt
            content: "Is {{ input }} fine?"
      """;
    String candidate = running.replace("fine?", "safe?");

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(running, candidate)).isFalse();
  }

  @Test
  void includes_need_a_restart() {
    String running = RUNNING + "  includes:\n    pipelines:\n      - \"*.yaml\"\n";

    assertThat(ReleaseChanges.pipelinesOrTemplatesOnly(running, running)).isFalse();
  }
}
