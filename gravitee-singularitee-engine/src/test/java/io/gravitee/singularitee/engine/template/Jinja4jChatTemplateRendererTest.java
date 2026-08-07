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
package io.gravitee.singularitee.engine.template;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.api.textgen.AudioContent;
import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import io.gravitee.singularitee.inference.api.textgen.ImageContent;
import io.gravitee.singularitee.inference.api.textgen.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Jinja4jChatTemplateRendererTest {

  // A minimal Llama 3 / ChatML-style template used across the tests.
  // Lets us verify role labels, special tokens, tools and multimodal parts
  // without depending on an external model file.
  private static final String SIMPLE_TEMPLATE = """
    {{ bos_token }}\
    {%- for message in messages %}\
    <|start_header_id|>{{ message.role }}<|end_header_id|>

    {{ message.content }}<|eot_id|>\
    {%- endfor %}\
    {%- if add_generation_prompt %}<|start_header_id|>assistant<|end_header_id|>

    {% endif %}""";

  private Jinja4jChatTemplateRenderer renderer;

  @BeforeEach
  void setUp() {
    renderer = new Jinja4jChatTemplateRenderer();
  }

  // ── Basic rendering ────────────────────────────────────────────────────────

  @Nested
  class BasicRendering {

    @Test
    void renders_single_user_message() {
      var messages = List.of(new ChatMessage(Role.USER, "Hello!", List.of()));
      String result = renderer.render(SIMPLE_TEMPLATE, messages, null, true, null);
      assertThat(result).contains("<|start_header_id|>user<|end_header_id|>");
      assertThat(result).contains("Hello!");
      assertThat(result).contains("<|eot_id|>");
    }

    @Test
    void renders_system_user_assistant_turn() {
      var messages = List.of(
        new ChatMessage(Role.SYSTEM, "You are helpful.", List.of()),
        new ChatMessage(Role.USER, "What is 2+2?", List.of()),
        new ChatMessage(Role.ASSISTANT, "It is 4.", List.of())
      );
      String result = renderer.render(SIMPLE_TEMPLATE, messages, null, false, null);
      assertThat(result).contains("<|start_header_id|>system<|end_header_id|>");
      assertThat(result).contains("You are helpful.");
      assertThat(result).contains("<|start_header_id|>user<|end_header_id|>");
      assertThat(result).contains("What is 2+2?");
      assertThat(result).contains("<|start_header_id|>assistant<|end_header_id|>");
      assertThat(result).contains("It is 4.");
    }

    @Test
    void add_generation_prompt_true_appends_assistant_header() {
      var messages = List.of(new ChatMessage(Role.USER, "Hi", List.of()));
      String result = renderer.render(SIMPLE_TEMPLATE, messages, null, true, null);
      // The trailing assistant header appears when add_generation_prompt=true
      long assistantHeaders = result
        .lines()
        .filter(l -> l.contains("<|start_header_id|>assistant<|end_header_id|>"))
        .count();
      assertThat(assistantHeaders).isGreaterThanOrEqualTo(1);
    }

    @Test
    void add_generation_prompt_false_omits_trailing_assistant_header() {
      var messages = List.of(new ChatMessage(Role.USER, "Hi", List.of()));
      String withGenPrompt = renderer.render(SIMPLE_TEMPLATE, messages, null, true, null);
      String withoutGenPrompt = renderer.render(SIMPLE_TEMPLATE, messages, null, false, null);
      assertThat(withGenPrompt.length()).isGreaterThan(withoutGenPrompt.length());
    }
  }

  // ── BOS / EOS tokens ──────────────────────────────────────────────────────

  @Nested
  class SpecialTokens {

    @Test
    void bos_token_default_is_empty_string() {
      // Without extra variables, bos_token defaults to ""
      var messages = List.of(new ChatMessage(Role.USER, "hello", List.of()));
      String result = renderer.render(SIMPLE_TEMPLATE, messages, null, false, null);
      assertThat(result).doesNotContain("<|begin_of_text|>");
    }

    @Test
    void bos_eos_tokens_injected_via_extra_variables() {
      var extras = Map.<String, Object>of(
        "bos_token",
        "<|begin_of_text|>",
        "eos_token",
        "<|end_of_text|>"
      );
      var messages = List.of(new ChatMessage(Role.USER, "hello", List.of()));
      String result = renderer.render(SIMPLE_TEMPLATE, messages, null, false, extras);
      assertThat(result).startsWith("<|begin_of_text|>");
    }

    @Test
    void extra_variables_are_merged_into_context() {
      String template = "{{ greeting }} {{ name }}";
      var extras = Map.<String, Object>of("greeting", "Hello", "name", "World");
      String result = renderer.render(template, null, null, false, extras);
      assertThat(result).isEqualTo("Hello World");
    }
  }

  // ── extraVariables messages vs parameter messages ─────────────────────────

  @Nested
  class MessagesPrecedence {

    @Test
    void null_messages_param_keeps_extraVariables_messages() {
      // The executor pre-builds Jinja-shaped message maps and passes them via
      // extraVariables. Passing null for the messages param must NOT clobber them.
      String template = "{% for m in messages %}{{ m.role }}: {{ m.content }}\n{% endfor %}";
      var prebuilt = List.of(Map.<String, Object>of("role", "user", "content", "from extras"));
      var extras = Map.<String, Object>of("messages", prebuilt);

      String result = renderer.render(template, null, null, false, extras);
      assertThat(result).isEqualTo("user: from extras\n");
    }

    @Test
    void non_null_messages_param_overrides_extraVariables_messages() {
      String template = "{% for m in messages %}{{ m.role }}: {{ m.content }}\n{% endfor %}";
      var prebuilt = List.of(Map.<String, Object>of("role", "user", "content", "from extras"));
      var extras = Map.<String, Object>of("messages", prebuilt);
      var param = List.of(new ChatMessage(Role.ASSISTANT, "from param", List.of()));

      String result = renderer.render(template, param, null, false, extras);
      assertThat(result).isEqualTo("assistant: from param\n");
    }
  }

  // ── Tools ──────────────────────────────────────────────────────────────────

  @Nested
  class ToolRendering {

    private static final String TOOLS_TEMPLATE =
      "{%- if tools %}" +
      "{% for tool in tools %}" +
      "{{ tool.function.name }}: {{ tool.function.description }}\n" +
      "{% endfor %}" +
      "{%- endif %}";

    @Test
    void tools_rendered_when_present() {
      var tool = Map.<String, Object>of(
        "type",
        "function",
        "function",
        Map.<String, Object>of("name", "get_weather", "description", "Returns current weather")
      );
      String result = renderer.render(TOOLS_TEMPLATE, null, List.of(tool), false, null);
      assertThat(result).contains("get_weather: Returns current weather");
    }

    @Test
    void tools_block_skipped_when_null() {
      String result = renderer.render(TOOLS_TEMPLATE, null, null, false, null);
      assertThat(result).isBlank();
    }

    @Test
    void tools_block_skipped_when_empty_list() {
      String result = renderer.render(TOOLS_TEMPLATE, null, List.of(), false, null);
      assertThat(result).isBlank();
    }
  }

  // ── Multimodal content parts ───────────────────────────────────────────────

  @Nested
  class MultimodalRendering {

    private static final String MULTIMODAL_TEMPLATE =
      "{% for m in messages %}" +
      "{% if m.content is iterable and m.content is not string %}" +
      "{% for part in m.content %}[{{ part.type }}]{% endfor %}" +
      "{% else %}{{ m.content }}{% endif %}" +
      "{% endfor %}";

    @Test
    void image_message_produces_image_type_placeholder() {
      var img = new ImageContent(null, "base64data");
      var msg = new ChatMessage(Role.USER, "Describe this", List.of(img));
      String result = renderer.render(MULTIMODAL_TEMPLATE, List.of(msg), null, false, null);
      assertThat(result).contains("[text]");
      assertThat(result).contains("[image]");
    }

    @Test
    void audio_message_produces_audio_type_placeholder() {
      var audio = new AudioContent(null, "base64data");
      var msg = new ChatMessage(Role.USER, "Transcribe this", List.of(audio));
      String result = renderer.render(MULTIMODAL_TEMPLATE, List.of(msg), null, false, null);
      assertThat(result).contains("[text]");
      assertThat(result).contains("[audio]");
    }

    @Test
    void text_only_message_produces_plain_content() {
      var msg = new ChatMessage(Role.USER, "Just text", List.of());
      String result = renderer.render(MULTIMODAL_TEMPLATE, List.of(msg), null, false, null);
      assertThat(result).contains("Just text");
      assertThat(result).doesNotContain("[image]");
      assertThat(result).doesNotContain("[audio]");
    }
  }

  // ── Template caching ──────────────────────────────────────────────────────

  @Nested
  class TemplateCaching {

    @Test
    void same_template_string_produces_consistent_results() {
      String template = "hello {{ name }}";
      var extras1 = Map.<String, Object>of("name", "Alice");
      var extras2 = Map.<String, Object>of("name", "Bob");
      String r1 = renderer.render(template, null, null, false, extras1);
      String r2 = renderer.render(template, null, null, false, extras2);
      assertThat(r1).isEqualTo("hello Alice");
      assertThat(r2).isEqualTo("hello Bob");
    }

    @Test
    void different_template_strings_are_cached_independently() {
      String t1 = "version: 1";
      String t2 = "version: 2";
      assertThat(renderer.render(t1, null, null, false, null)).isEqualTo("version: 1");
      assertThat(renderer.render(t2, null, null, false, null)).isEqualTo("version: 2");
    }
  }

  // ── Real Qwen3 template: thinking bypass ──────────────────────────────────

  @Nested
  class Qwen3ThinkingBypass {

    /**
     * The exact suffix the Qwen3 chat template must emit when
     * {@code enable_thinking} is the boolean {@code false} and
     * {@code add_generation_prompt} is true: an assistant header followed by
     * an empty think-block prefill. The prefill is what suppresses reasoning
     * at generation time — if it is missing, the model thinks.
     */
    private static final String THINK_PREFILL = "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    /** The real Qwen3-0.6B chat_template, vendored from tokenizer_config.json. */
    private static String qwen3Template() {
      try (
        var in = Jinja4jChatTemplateRendererTest.class.getResourceAsStream(
          "/templates/qwen3-chat-template.jinja"
        )
      ) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private String render(Object enableThinking) {
      var extras = new HashMap<String, Object>();
      if (enableThinking != null) {
        extras.put("enable_thinking", enableThinking);
      }
      var messages = List.of(new ChatMessage(Role.USER, "Hello!", List.of()));
      return renderer.render(qwen3Template(), messages, null, true, extras);
    }

    @Test
    void boolean_false_emits_empty_think_prefill() {
      assertThat(render(Boolean.FALSE)).endsWith(THINK_PREFILL);
    }

    @Test
    void boolean_true_omits_prefill() {
      assertThat(render(Boolean.TRUE))
        .endsWith("<|im_start|>assistant\n")
        .doesNotContain("<think>");
    }

    @Test
    void absent_enable_thinking_omits_prefill() {
      assertThat(render(null)).endsWith("<|im_start|>assistant\n").doesNotContain("<think>");
    }

    @Test
    void string_false_does_NOT_trigger_prefill() {
      // 'enable_thinking is false' is a strict boolean-identity test in
      // Jinja2. A quoted YAML value ("false") arrives as a String and
      // silently disables the bypass — this pins down that semantic so the
      // hardening warning in JinjaContextHelper stays honest.
      assertThat(render("false")).endsWith("<|im_start|>assistant\n").doesNotContain("<think>");
    }

    @Test
    void full_chatml_scaffolding_present() {
      String result = render(Boolean.FALSE);
      assertThat(result).startsWith("<|im_start|>user\nHello!<|im_end|>\n");
    }
  }

  // ── defaultEnvironment ────────────────────────────────────────────────────

  @Nested
  class DefaultEnvironment {

    @Test
    void auto_escaping_is_disabled_so_special_tokens_are_not_escaped() {
      // HTML-sensitive characters in special tokens must NOT be escaped
      String template = "{{ bos_token }}";
      var extras = Map.<String, Object>of("bos_token", "<|begin_of_text|>");
      String result = renderer.render(template, null, null, false, extras);
      assertThat(result).isEqualTo("<|begin_of_text|>");
      assertThat(result).doesNotContain("&lt;");
    }
  }
}
