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
package io.gravitee.singularitee.plugin.infer;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool-injection side of {@link PromptAssembler}: filtering by a tool-select
 * shortlist and rewriting definitions with condensed descriptions.
 */
class ToolInjectionTest {

  private static final InferStepConfig CFG = InferStepConfig.builder().build();

  private static ToolDefinition tool(String name, String description) {
    return ToolDefinition.newBuilder().setName(name).setDescription(description).build();
  }

  private static ToolDefinition toolWithTemplate(String name, String desc, String template) {
    return ToolDefinition.newBuilder()
      .setName(name)
      .setDescription(desc)
      .setTemplate(template)
      .build();
  }

  private static List<ToolDefinition> tools(int n) {
    List<ToolDefinition> tools = new ArrayList<>();
    for (int i = 0; i < n; i++) tools.add(tool("tool" + i, "Does thing " + i + "."));
    return tools;
  }

  private static PipelineContext pctx(String prompt, List<ToolDefinition> tools) {
    return new PipelineContext(
      prompt,
      List.of(new ChatTurn(ChatRole.USER, prompt)),
      null,
      tools,
      null
    );
  }

  @Test
  void injectable_tools_filters_by_shortlist_and_empty_list_injects_none() {
    var pctx = pctx("hi", tools(3));

    // No shortlist -> all tools (behavior identical when the key is absent)
    assertThat(PromptAssembler.injectableTools(pctx, CFG)).hasSize(3);

    // Shortlist -> only named tools
    pctx.setSelectedTools(List.of("tool1"));
    assertThat(PromptAssembler.injectableTools(pctx, CFG))
      .extracting(ToolDefinition::getName)
      .containsExactly("tool1");

    // Empty shortlist -> no tools injected
    var pctx2 = pctx("hi", tools(3));
    pctx2.setSelectedTools(List.of());
    assertThat(PromptAssembler.injectableTools(pctx2, CFG)).isEmpty();
  }

  @Test
  void condensed_description_rewrites_definition_and_nested_template_json() {
    var tool = toolWithTemplate(
      "read",
      "Long original description. More detail.",
      "{\"type\":\"function\",\"function\":{\"name\":\"read\"," +
        "\"description\":\"Long original description. More detail.\"," +
        "\"parameters\":{\"type\":\"object\"}}}"
    );

    var out = PromptAssembler.withCondensedDescription(tool, Map.of("read", "Short."));

    assertThat(out.getDescription()).isEqualTo("Short.");
    assertThat(out.getTemplate())
      .contains("\"description\":\"Short.\"")
      .contains("\"name\":\"read\"")
      .contains("\"parameters\"")
      .doesNotContain("Long original");
    assertThat(tool.getDescription()).startsWith("Long original");
  }

  @Test
  void condensed_description_rewrites_flat_template_json() {
    var tool = toolWithTemplate(
      "read",
      "Long original description.",
      "{\"name\":\"read\",\"description\":\"Long original description.\"}"
    );

    var out = PromptAssembler.withCondensedDescription(tool, Map.of("read", "Short."));

    assertThat(out.getDescription()).isEqualTo("Short.");
    assertThat(out.getTemplate()).contains("\"description\":\"Short.\"");
  }

  @Test
  void tool_without_condensed_entry_is_returned_unchanged() {
    var tool = toolWithTemplate("write", "Original.", "{\"name\":\"write\"}");

    var out = PromptAssembler.withCondensedDescription(tool, Map.of("read", "Short."));

    assertThat(out).isSameAs(tool);
  }

  @Test
  void unparseable_template_keeps_original_template_but_rewrites_description() {
    var tool = toolWithTemplate("read", "Original.", "not json {{{");

    var out = PromptAssembler.withCondensedDescription(tool, Map.of("read", "Short."));

    assertThat(out.getDescription()).isEqualTo("Short.");
    assertThat(out.getTemplate()).isEqualTo("not json {{{");
  }

  @Test
  void injectable_tools_applies_condensed_descriptions_and_ignores_absent_map() {
    var toolList = List.of(
      toolWithTemplate(
        "tool0",
        "Original zero.",
        "{\"type\":\"function\",\"function\":{\"name\":\"tool0\",\"description\":\"Original zero.\"}}"
      ),
      toolWithTemplate("tool1", "Original one.", "{\"name\":\"tool1\"}")
    );

    // No condensed map -> tools pass through unchanged
    var plain = pctx("hi", toolList);
    plain.setSelectedTools(List.of("tool0"));
    assertThat(PromptAssembler.injectableTools(plain, CFG).get(0)).isSameAs(toolList.get(0));

    // Condensed map -> selected tool rewritten (description + template)
    var pctx = pctx("hi", toolList);
    pctx.setSelectedTools(List.of("tool0"));
    pctx.setCondensedToolDescriptions(Map.of("tool0", "Zero."));
    var injected = PromptAssembler.injectableTools(pctx, CFG);
    assertThat(injected).hasSize(1);
    assertThat(injected.get(0).getDescription()).isEqualTo("Zero.");
    assertThat(injected.get(0).getTemplate()).contains("\"description\":\"Zero.\"");
  }
}
