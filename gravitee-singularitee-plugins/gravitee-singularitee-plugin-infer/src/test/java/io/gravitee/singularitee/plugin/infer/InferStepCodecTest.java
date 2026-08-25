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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferStepCodecTest {

  private final InferStepCodec codec = new InferStepCodec();

  private static StepCodecContext ctx(
    Map<String, String> templates,
    Path base,
    Map<String, Map<String, Object>> tags
  ) {
    return new StepCodecContext(templates, base, Map.of("tags", tags), null);
  }

  private static final StepCodecContext EMPTY = ctx(Map.of(), null, Map.of());

  private static Map<String, Object> harmonyTags() {
    var tags = new LinkedHashMap<String, Object>();
    tags.put("reasoning_open", List.of("<|channel|>analysis<|message|>", "<think>"));
    tags.put("reasoning_close", "<|end|>");
    tags.put("reasoning_repeatable", true);
    tags.put("tool_open", "<|start|>assistant<|channel|>commentary to=functions.");
    tags.put("tool_close", "<|call|>");
    return tags;
  }

  @Test
  void parses_the_full_yaml_shape() {
    var raw = new LinkedHashMap<String, Object>();
    raw.put("model_id", "llm");
    raw.put("output_field", "gen.output");
    raw.put(
      "prompt",
      Map.of(
        "messages",
        List.of(Map.of("role", "system", "content", "Be terse."), Map.of("content", "{{ prompt }}"))
      )
    );
    raw.put(
      "sampling",
      Map.of("max_tokens", 256, "temperature", 0.7, "top_p", 0.9, "stop", List.of("<|end|>"))
    );
    raw.put("tags", harmonyTags());
    raw.put("context", Map.of("enable_thinking", false, "n", 3));
    raw.put("inject_tools", false);
    raw.put("server_tools", false);
    raw.put("strip_thinking", true);
    raw.put("stream_thinking", true);
    raw.put("system", "Call the plan tool.");
    raw.put("trim_history", false);
    raw.put("tool_extraction_template", "harmony");
    raw.put("chat_template", "{{ messages }}");

    var cfg = codec.parse("gen", raw, EMPTY);

    assertThat(cfg.modelId()).isEqualTo("llm");
    assertThat(cfg.outputField()).isEqualTo("gen.output");
    assertThat(cfg.rawTemplate()).isEmpty();
    assertThat(cfg.messages()).hasSize(2);
    assertThat(cfg.messages().get(0).role()).isEqualTo("system");
    assertThat(cfg.messages().get(1).role()).isEqualTo("user");
    assertThat(cfg.messages().get(1).content()).isEqualTo("{{ prompt }}");
    assertThat(cfg.samplingParams().getMaxTokens()).isEqualTo(256);
    assertThat(cfg.samplingParams().getTemperature()).isEqualTo(0.7f);
    assertThat(cfg.samplingParams().getTopP()).isEqualTo(0.9f);
    assertThat(cfg.stop()).containsExactly("<|end|>");
    assertThat(cfg.reasoningTags().getOpenTag()).isEqualTo("<|channel|>analysis<|message|>");
    assertThat(cfg.reasoningTags().getOpenTagAlternativesList()).containsExactly("<think>");
    assertThat(cfg.reasoningTags().getCloseTag()).isEqualTo("<|end|>");
    assertThat(cfg.reasoningTags().hasRepeatable()).isTrue();
    assertThat(cfg.reasoningTags().getRepeatable()).isTrue();
    assertThat(cfg.toolCallTags().getOpenTag()).isEqualTo(
      "<|start|>assistant<|channel|>commentary to=functions."
    );
    assertThat(cfg.toolCallTags().getCloseTag()).isEqualTo("<|call|>");
    assertThat(cfg.toolCallTags().hasRepeatable()).isFalse();
    assertThat(cfg.context()).containsEntry("enable_thinking", false).containsEntry("n", 3);
    assertThat(cfg.injectTools()).isFalse();
    assertThat(cfg.exposeServerTools()).isFalse();
    assertThat(cfg.stripThinking()).isTrue();
    assertThat(cfg.streamThinking()).isTrue();
    assertThat(cfg.systemPrompt()).isEqualTo("Call the plan tool.");
    assertThat(cfg.trimHistory()).isFalse();
    assertThat(cfg.toolExtractionTemplate()).isEqualTo("harmony");
    assertThat(cfg.chatTemplate()).isEqualTo("{{ messages }}");
  }

  @Test
  void empty_block_leaves_optional_toggles_unset() {
    var cfg = codec.parse("gen", Map.of(), EMPTY);

    assertThat(cfg.modelId()).isEmpty();
    assertThat(cfg.messages()).isEmpty();
    assertThat(cfg.samplingParams().getMaxTokens()).isZero();
    assertThat(cfg.reasoningTags()).isNull();
    assertThat(cfg.toolCallTags()).isNull();
    assertThat(cfg.context()).isNull();
    assertThat(cfg.injectTools()).isNull();
    assertThat(cfg.trimHistory()).isNull();
    assertThat(cfg.chatTemplate()).isNull();
    assertThat(cfg.shouldInjectTools()).isTrue();
    assertThat(cfg.shouldTrimHistory()).isTrue();
    assertThat(cfg.shouldStripThinking()).isFalse();
  }

  @Test
  void bare_string_tags_resolve_through_the_workspace_section() {
    var cfg = codec.parse(
      "gen",
      Map.of("model_id", "llm", "tags", "harmony"),
      ctx(Map.of(), null, Map.of("harmony", harmonyTags()))
    );

    assertThat(cfg.reasoningTags().getOpenTag()).isEqualTo("<|channel|>analysis<|message|>");
    assertThat(cfg.toolCallTags().getCloseTag()).isEqualTo("<|call|>");
  }

  @Test
  void unknown_tags_id_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("gen", Map.of("tags", "nope"), EMPTY))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gen")
      .hasMessageContaining("nope");
  }

  @Test
  void template_id_resolves_against_workspace_templates() {
    var cfg = codec.parse(
      "gen",
      Map.of("prompt", Map.of("template_id", "raw")),
      ctx(Map.of("raw", "Q: {{ prompt }}"), null, Map.of())
    );
    assertThat(cfg.rawTemplate()).isEqualTo("Q: {{ prompt }}");
    assertThat(cfg.messages()).isEmpty();
  }

  @Test
  void unknown_template_id_fails_with_step_id() {
    assertThatThrownBy(() ->
      codec.parse("gen", Map.of("prompt", Map.of("template_id", "missing")), EMPTY)
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gen")
      .hasMessageContaining("missing");
  }

  @Test
  void template_file_reads_under_the_templates_base(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("p.jinja"), "FILE {{ prompt }}");

    var cfg = codec.parse(
      "gen",
      Map.of("prompt", Map.of("template_file", "p.jinja")),
      ctx(Map.of(), dir, Map.of())
    );
    assertThat(cfg.rawTemplate()).isEqualTo("FILE {{ prompt }}");
  }

  @Test
  void template_file_escaping_the_base_is_refused(@TempDir Path dir) {
    assertThatThrownBy(() ->
      codec.parse(
        "gen",
        Map.of("prompt", Map.of("template_file", "../../etc/passwd")),
        ctx(Map.of(), dir, Map.of())
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("outside");
  }

  @Test
  void raw_template_forms_are_mutually_exclusive() {
    assertThatThrownBy(() ->
      codec.parse(
        "gen",
        Map.of("prompt", Map.of("template", "x", "template_id", "raw")),
        ctx(Map.of("raw", "y"), null, Map.of())
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gen")
      .hasMessageContaining("mutually exclusive");
  }

  @Test
  void inline_template_wins_over_messages() {
    var cfg = codec.parse(
      "gen",
      Map.of(
        "prompt",
        Map.of("template", "RAW", "messages", List.of(Map.of("role", "user", "content", "m")))
      ),
      EMPTY
    );
    assertThat(cfg.rawTemplate()).isEqualTo("RAW");
    assertThat(cfg.messages()).isEmpty();
  }

  @Test
  void chat_template_id_resolves_and_inline_source_passes_through() {
    var c = ctx(
      Map.of("chatml", "{% for m in messages %}{{ m.content }}{% endfor %}"),
      null,
      Map.of()
    );
    assertThat(codec.parse("gen", Map.of("chat_template", "chatml"), c).chatTemplate()).isEqualTo(
      "{% for m in messages %}{{ m.content }}{% endfor %}"
    );
    assertThat(codec.parse("gen", Map.of("chat_template", "{{ x }}"), c).chatTemplate()).isEqualTo(
      "{{ x }}"
    );
  }

  @Test
  void invalid_shape_fails_with_step_id() {
    assertThatThrownBy(() -> codec.parse("gen", Map.of("sampling", "not-a-map"), EMPTY))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("gen");
  }
}
