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
package io.gravitee.singularitee.plugin.llmguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmGuardStepCodecTest {

  private final LlmGuardStepCodec codec = new LlmGuardStepCodec();

  private static StepCodecContext ctx(Map<String, String> templates, Path base) {
    return new StepCodecContext(templates, base, Map.of(), null);
  }

  private static Map<String, Object> harmonyTags() {
    var tags = new LinkedHashMap<String, Object>();
    tags.put("reasoning_open", List.of("<|channel|>analysis<|message|>", "<think>"));
    tags.put("reasoning_close", "<|channel|>final<|message|>");
    tags.put("reasoning_repeatable", true);
    tags.put("tool_open", "<|channel|>commentary to=functions.");
    tags.put("tool_close", "<|call|>");
    return tags;
  }

  private static Map<String, Object> judge(Object tags) {
    var raw = new LinkedHashMap<String, Object>();
    raw.put("model_id", "judge");
    raw.put("prompt", Map.of("messages", List.of(Map.of("content", "{{ prompt }}"))));
    raw.put("tags", tags);
    return raw;
  }

  @Test
  void parsesInlineTagsLikeInfer() {
    var cfg = codec.parse("g", judge(harmonyTags()), ctx(Map.of(), null));

    assertThat(cfg.reasoningTags().getOpenTag()).isEqualTo("<|channel|>analysis<|message|>");
    assertThat(cfg.reasoningTags().getOpenTagAlternativesList()).containsExactly("<think>");
    assertThat(cfg.reasoningTags().getCloseTag()).isEqualTo("<|channel|>final<|message|>");
    assertThat(cfg.reasoningTags().getRepeatable()).isTrue();
    assertThat(cfg.toolCallTags().getOpenTag()).isEqualTo("<|channel|>commentary to=functions.");
    assertThat(cfg.toolCallTags().getCloseTag()).isEqualTo("<|call|>");
  }

  @Test
  void resolvesTagsReferenceFromWorkspaceSection() {
    var ctx = new StepCodecContext(
      Map.of(),
      null,
      Map.of("tags", Map.of("harmony", harmonyTags())),
      null
    );
    var cfg = codec.parse("g", judge("harmony"), ctx);

    assertThat(cfg.reasoningTags().getOpenTag()).isEqualTo("<|channel|>analysis<|message|>");
    assertThat(cfg.toolCallTags().getCloseTag()).isEqualTo("<|call|>");
  }

  @Test
  void unknownTagsReferenceFailsTheLoad() {
    assertThatThrownBy(() -> codec.parse("g", judge("nope"), ctx(Map.of(), null)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("unknown tags id 'nope'");
  }

  @Test
  void noTagsLeavesTheDefaults() {
    var cfg = codec.parse("g", judge(null), ctx(Map.of(), null));

    assertThat(cfg.reasoningTags()).isNull();
    assertThat(cfg.toolCallTags()).isNull();
  }

  @Test
  void parsesMessagesPrompt() {
    var cfg = codec.parse(
      "judge",
      Map.of(
        "model_id",
        "llm",
        "action",
        "warn",
        "safe_token",
        "SAFE",
        "message",
        "Nope: {{ judge.verdict }}",
        "prompt",
        Map.of(
          "messages",
          List.of(
            Map.of("role", "system", "content", "You are a judge."),
            Map.of("content", "Is this safe? {{ prompt }}")
          )
        ),
        "sampling",
        Map.of("max_tokens", 8, "temperature", 0.1, "top_p", 0.9),
        "context",
        Map.of("categories", List.of("hate", "spam"))
      ),
      ctx(Map.of(), null)
    );

    assertThat(cfg.modelId()).isEqualTo("llm");
    assertThat(cfg.action()).isEqualTo(GuardAction.WARN);
    assertThat(cfg.safeToken()).isEqualTo("SAFE");
    assertThat(cfg.message()).isEqualTo("Nope: {{ judge.verdict }}");
    assertThat(cfg.rawTemplate()).isEmpty();
    assertThat(cfg.messages()).containsExactly(
      new MessageTemplate("system", "You are a judge."),
      new MessageTemplate("user", "Is this safe? {{ prompt }}")
    );
    assertThat(cfg.samplingParams().getMaxTokens()).isEqualTo(8);
    assertThat(cfg.samplingParams().getTemperature()).isEqualTo(0.1f);
    assertThat(cfg.samplingParams().getTopP()).isEqualTo(0.9f);
    assertThat(cfg.context()).containsEntry("categories", List.of("hate", "spam"));
  }

  @Test
  void inlineTemplateWinsOverMessages() {
    var cfg = codec.parse(
      "judge",
      Map.of(
        "prompt",
        Map.of(
          "template",
          "Judge: {{ prompt }}",
          "messages",
          List.of(Map.of("role", "user", "content", "ignored"))
        )
      ),
      ctx(Map.of(), null)
    );

    assertThat(cfg.rawTemplate()).isEqualTo("Judge: {{ prompt }}");
    assertThat(cfg.messages()).isEmpty();
  }

  @Test
  void templateIdResolvesFromWorkspaceTemplates() {
    var cfg = codec.parse(
      "judge",
      Map.of("prompt", Map.of("template_id", "judge-prompt")),
      ctx(Map.of("judge-prompt", "From registry {{ prompt }}"), null)
    );

    assertThat(cfg.rawTemplate()).isEqualTo("From registry {{ prompt }}");
  }

  @Test
  void templateFileResolvesUnderBasePath(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("judge.j2"), "From file {{ prompt }}");

    var cfg = codec.parse(
      "judge",
      Map.of("prompt", Map.of("template_file", "judge.j2")),
      ctx(Map.of(), dir)
    );

    assertThat(cfg.rawTemplate()).isEqualTo("From file {{ prompt }}");
  }

  @Test
  void unknownTemplateIdNamesTheStep() {
    assertThatThrownBy(() ->
      codec.parse(
        "input_judge",
        Map.of("prompt", Map.of("template_id", "missing")),
        ctx(Map.of(), null)
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("input_judge")
      .hasMessageContaining("missing");
  }

  @Test
  void mutuallyExclusiveTemplateSourcesNameTheStep() {
    assertThatThrownBy(() ->
      codec.parse(
        "input_judge",
        Map.of("prompt", Map.of("template", "a", "template_id", "b")),
        ctx(Map.of("b", "x"), null)
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("input_judge")
      .hasMessageContaining("mutually exclusive");
  }
}
