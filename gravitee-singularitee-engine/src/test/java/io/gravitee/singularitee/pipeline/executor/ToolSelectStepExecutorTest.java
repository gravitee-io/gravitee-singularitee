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
package io.gravitee.singularitee.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolSelectStepConfig;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolSelectStepExecutor}: batching math, threshold +
 * none_of_these selection, all-none empty shortlist, fail-open on classify
 * error, default label condensing and last-user-message input, plus the
 * {@link InferStepExecutor#injectableTools} filtering helper.
 */
class ToolSelectStepExecutorTest {

  private ToolSelectStepExecutor executor;
  private ClassifierEngine engine;
  private final List<ClassifyRequest> capturedRequests = new ArrayList<>();
  private final List<List<ClassifyLabel>> capturedLabels = new ArrayList<>();

  @BeforeEach
  void setUp() {
    executor = new ToolSelectStepExecutor(mock(StepExecutionContext.class), new JinjaRenderer());
    engine = mock(ClassifierEngine.class);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static ToolDefinition tool(String name, String description) {
    return ToolDefinition.newBuilder().setName(name).setDescription(description).build();
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

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private static ClassifyResponse response(Map<String, Float> scores) {
    return new ClassifyResponse("", 0f, scores, List.of());
  }

  /** Stubs the engine to score labels via the given function, capturing calls. */
  private void stubEngine(java.util.function.Function<String, Float> scoreByLabel) {
    when(engine.rxClassify(any(ClassifyRequest.class), anyList())).thenAnswer(inv -> {
      capturedRequests.add(inv.getArgument(0));
      List<ClassifyLabel> labels = inv.getArgument(1);
      capturedLabels.add(labels);
      Map<String, Float> scores = new HashMap<>();
      for (var l : labels) scores.put(l.name(), scoreByLabel.apply(l.name()));
      return Single.just(response(scores));
    });
  }

  private PipelineContext execute(ToolSelectStepConfig cfg, PipelineContext pctx) {
    executor
      .rxExecuteWithEngine("select-tools", cfg, engine, stepContext(pctx))
      .test()
      .assertValue("next");
    return pctx;
  }

  // ── Batching math ─────────────────────────────────────────────────────────

  @Test
  void nine_tools_yield_three_calls_of_at_most_five_labels_including_none() {
    stubEngine(name -> 0.9f);
    var cfg = ToolSelectStepConfig.newBuilder().setModelId("triage").setBatchSize(4).build();

    execute(cfg, pctx("do something", tools(9)));

    assertThat(capturedLabels).hasSize(3);
    for (var labels : capturedLabels) {
      assertThat(labels.size()).isLessThanOrEqualTo(5);
      assertThat(labels.get(labels.size() - 1).name()).isEqualTo(ToolSelectStepExecutor.NONE_LABEL);
    }
    assertThat(capturedLabels.get(0)).hasSize(5); // 4 tools + none
    assertThat(capturedLabels.get(2)).hasSize(2); // 1 tool + none
  }

  // ── Selection: threshold + none_of_these beats ────────────────────────────

  @Test
  void tool_selected_only_when_score_meets_threshold_and_beats_none() {
    // tool0: above threshold, beats none → selected
    // tool1: above threshold but none beats it → rejected
    // tool2: below threshold → rejected
    stubEngine(name ->
      switch (name) {
        case "tool0" -> 0.8f;
        case "tool1" -> 0.4f;
        case "tool2" -> 0.1f;
        default -> 0.5f; // none_of_these
      }
    );
    var cfg = ToolSelectStepConfig.newBuilder().setModelId("triage").setThreshold(0.3f).build();

    var pctx = execute(cfg, pctx("run tool zero", tools(3)));

    assertThat(pctx.selectedTools()).containsExactly("tool0");
  }

  @Test
  void all_none_yields_empty_shortlist_and_always_include_is_not_added() {
    stubEngine(name -> ToolSelectStepExecutor.NONE_LABEL.equals(name) ? 0.9f : 0.1f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .addAlwaysInclude("tool0")
      .build();

    var pctx = execute(cfg, pctx("hello, how are you?", tools(3)));

    assertThat(pctx.selectedTools()).isNotNull().isEmpty();
  }

  @Test
  void always_include_is_unioned_into_a_non_empty_shortlist() {
    stubEngine(name -> "tool1".equals(name) ? 0.9f : 0.0f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .addAlwaysInclude("tool2")
      .addAlwaysInclude("not-a-tool")
      .build();

    var pctx = execute(cfg, pctx("use tool one", tools(3)));

    assertThat(pctx.selectedTools()).containsExactlyInAnyOrder("tool1", "tool2");
  }

  // ── Fail-open ─────────────────────────────────────────────────────────────

  @Test
  void classify_error_fails_open_including_the_batch_tools() {
    // 6 tools, batch_size 4 → batch1 (tool0-3) fails, batch2 (tool4-5) all none.
    var calls = new int[] { 0 };
    when(engine.rxClassify(any(ClassifyRequest.class), anyList())).thenAnswer(inv -> {
      List<ClassifyLabel> labels = inv.getArgument(1);
      if (calls[0]++ == 0) return Single.error(new RuntimeException("boom"));
      Map<String, Float> scores = new HashMap<>();
      for (var l : labels)
        scores.put(l.name(), ToolSelectStepExecutor.NONE_LABEL.equals(l.name()) ? 0.9f : 0.1f);
      return Single.just(response(scores));
    });
    var cfg = ToolSelectStepConfig.newBuilder().setModelId("triage").setBatchSize(4).build();

    var pctx = execute(cfg, pctx("do something", tools(6)));

    assertThat(pctx.selectedTools()).containsExactly("tool0", "tool1", "tool2", "tool3");
  }

  // ── Label condensing ──────────────────────────────────────────────────────

  @Test
  void default_condensing_takes_first_sentence_capped_at_160_chars() {
    String longFirst = "A".repeat(200) + ". Second sentence.";
    assertThat(ToolSelectStepExecutor.defaultCondense(longFirst)).hasSize(160);

    String multiLine = "Reads a file from disk. Supports offsets.\nMore details here.";
    assertThat(ToolSelectStepExecutor.defaultCondense(multiLine)).isEqualTo(
      "Reads a file from disk."
    );

    String newlineFirst = "First line only\nsecond line";
    assertThat(ToolSelectStepExecutor.defaultCondense(newlineFirst)).isEqualTo("First line only");
  }

  @Test
  void label_template_renders_condensed_description() {
    stubEngine(name -> 0.9f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .setLabelTemplate("Tool {{ tool.name }}: {{ tool.description }}")
      .build();

    execute(cfg, pctx("go", List.of(tool("read", "Reads files."))));

    assertThat(capturedLabels.get(0).get(0).description()).isEqualTo("Tool read: Reads files.");
  }

  // ── Input resolution ──────────────────────────────────────────────────────

  @Test
  void input_is_last_user_message_truncated_to_1500_chars() {
    stubEngine(name -> 0.9f);
    String longMsg = "x".repeat(3000);
    var pctx = new PipelineContext(
      "prompt-fallback",
      List.of(
        new ChatTurn(ChatRole.USER, "earlier message"),
        new ChatTurn(ChatRole.ASSISTANT, "assistant reply"),
        new ChatTurn(ChatRole.USER, longMsg)
      ),
      null,
      tools(1),
      null
    );
    var cfg = ToolSelectStepConfig.newBuilder().setModelId("triage").build();

    execute(cfg, pctx);

    assertThat(capturedRequests.get(0).text()).hasSize(1500).isEqualTo(longMsg.substring(0, 1500));
  }

  @Test
  void input_field_overrides_last_user_message() {
    stubEngine(name -> 0.9f);
    var pctx = pctx("the prompt", tools(1));
    pctx.set("custom.field", "custom input");
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .setInputField("custom.field")
      .build();

    execute(cfg, pctx);

    assertThat(capturedRequests.get(0).text()).isEqualTo("custom input");
  }

  // ── InferStepExecutor filtering helper ────────────────────────────────────

  @Test
  void injectable_tools_filters_by_shortlist_and_empty_list_injects_none() {
    var pctx = pctx("hi", tools(3));

    // No shortlist → all tools (behavior identical when the key is absent)
    assertThat(
      PromptAssembler.injectableTools(
        pctx,
        io.gravitee.singularitee.protocol.InferStepConfig.getDefaultInstance()
      )
    ).hasSize(3);

    // Shortlist → only named tools
    pctx.setSelectedTools(List.of("tool1"));
    assertThat(
      PromptAssembler.injectableTools(
        pctx,
        io.gravitee.singularitee.protocol.InferStepConfig.getDefaultInstance()
      )
    )
      .extracting(ToolDefinition::getName)
      .containsExactly("tool1");

    // Empty shortlist → no tools injected
    var pctx2 = pctx("hi", tools(3));
    pctx2.setSelectedTools(List.of());
    assertThat(
      PromptAssembler.injectableTools(
        pctx2,
        io.gravitee.singularitee.protocol.InferStepConfig.getDefaultInstance()
      )
    ).isEmpty();
  }

  // ── Description trimming (trim_descriptions) ──────────────────────────────

  @Test
  void trim_descriptions_populates_condensed_map_for_selected_tools_only() {
    stubEngine(name -> "tool0".equals(name) || "tool1".equals(name) ? 0.9f : 0.0f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .setTrimDescriptions(true)
      .build();
    var toolList = List.of(
      tool("tool0", "Reads files from disk. Supports offsets and limits."),
      tool("tool1", "Writes files. Overwrites existing content."),
      tool("tool2", "Unrelated tool. Never selected here.")
    );

    var pctx = execute(cfg, pctx("read and write things", toolList));

    assertThat(pctx.selectedTools()).containsExactly("tool0", "tool1");
    assertThat(pctx.condensedToolDescriptions()).containsOnlyKeys("tool0", "tool1");
    assertThat(pctx.condensedToolDescriptions().get("tool0")).isEqualTo("Reads files from disk.");
    assertThat(pctx.condensedToolDescriptions().get("tool1")).isEqualTo("Writes files.");
  }

  @Test
  void trim_descriptions_off_leaves_condensed_map_null() {
    stubEngine(name -> 0.9f);
    var cfg = ToolSelectStepConfig.newBuilder().setModelId("triage").build();

    var pctx = execute(cfg, pctx("go", tools(2)));

    assertThat(pctx.condensedToolDescriptions()).isNull();
  }

  @Test
  void trim_descriptions_with_empty_shortlist_yields_empty_map() {
    stubEngine(name -> ToolSelectStepExecutor.NONE_LABEL.equals(name) ? 0.9f : 0.1f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .setTrimDescriptions(true)
      .build();

    var pctx = execute(cfg, pctx("just chatting", tools(3)));

    assertThat(pctx.selectedTools()).isEmpty();
    assertThat(pctx.condensedToolDescriptions()).isNotNull().isEmpty();
  }

  @Test
  void description_template_overrides_default_condenser() {
    stubEngine(name -> "read".equals(name) ? 0.9f : 0.1f);
    var cfg = ToolSelectStepConfig.newBuilder()
      .setModelId("triage")
      .setTrimDescriptions(true)
      .setDescriptionTemplate("{{ tool.name }} — {{ tool.description | upper }}")
      .build();

    var pctx = execute(cfg, pctx("go", List.of(tool("read", "Reads files."))));

    assertThat(pctx.condensedToolDescriptions().get("read")).isEqualTo("read — READS FILES.");
  }

  // ── InferStepExecutor description rewriting helper ────────────────────────

  private static ToolDefinition toolWithTemplate(String name, String desc, String template) {
    return ToolDefinition.newBuilder()
      .setName(name)
      .setDescription(desc)
      .setTemplate(template)
      .build();
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
    // original untouched
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

    // No condensed map → tools pass through unchanged
    var plain = pctx("hi", toolList);
    plain.setSelectedTools(List.of("tool0"));
    assertThat(
      PromptAssembler.injectableTools(
        plain,
        io.gravitee.singularitee.protocol.InferStepConfig.getDefaultInstance()
      ).get(0)
    ).isSameAs(toolList.get(0));

    // Condensed map → selected tool rewritten (description + template)
    var pctx = pctx("hi", toolList);
    pctx.setSelectedTools(List.of("tool0"));
    pctx.setCondensedToolDescriptions(Map.of("tool0", "Zero."));
    var injected = PromptAssembler.injectableTools(
      pctx,
      io.gravitee.singularitee.protocol.InferStepConfig.getDefaultInstance()
    );
    assertThat(injected).hasSize(1);
    assertThat(injected.get(0).getDescription()).isEqualTo("Zero.");
    assertThat(injected.get(0).getTemplate()).contains("\"description\":\"Zero.\"");
  }
}
