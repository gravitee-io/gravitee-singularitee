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
package io.gravitee.singularitee.engine.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code harmony} (gpt-oss) built-in extraction template, referenced by name from
 * {@code examples/llama/gpt-oss-20b.yaml} and {@code docs/models/README.md}.
 *
 * <p>It reads the function name off the front of the captured span with a bare
 * {@code ^\s*([\w.-]+)} match. Applied to a real span that is correct; applied to ordinary prose
 * it will happily return the first word as a function name. Since a non-empty extraction nulls
 * {@code content} in the response, such a phantom does not merely add a bogus call — it deletes
 * the answer the model actually produced. Hence the name must be checked against the request's
 * declared tools, exactly as the {@code glm-name-json} built-in does.
 *
 * @author GraviteeSource Team
 */
class HarmonyToolTemplateTest {

  /** Same shape InferStepExecutor.toolsData builds: name + description per tool. */
  private static final List<Map<String, Object>> TOOLS = List.of(
    Map.of("name", "send_email", "description", "Send an email to a recipient."),
    Map.of("name", "get_weather", "description", "Get current weather for a city.")
  );

  private static List<ToolCallExtractor.ExtractedToolCall> extract(String output) {
    return ToolCallExtractor.extract(output, TOOLS, "harmony");
  }

  @Test
  void a_real_harmony_span_still_extracts() {
    var calls = extract(
      "send_email<|constrain|>json<|message|>{\"to\":\"jamie@example.com\",\"body\":\"by tomorrow\"}"
    );

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("send_email");
    assertThat(calls.get(0).argumentsJson()).contains("jamie@example.com");
  }

  @Test
  void a_span_with_no_arguments_defaults_to_an_empty_object() {
    var calls = extract("get_weather<|constrain|>json<|message|>");

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("get_weather");
    assertThat(calls.get(0).argumentsJson()).isEqualTo("{}");
  }

  /** The reported bug: reasoning prose beginning "User wants to..." became a call named "User". */
  @Test
  void prose_beginning_with_a_capitalised_word_is_not_a_tool_call() {
    var calls = extract(
      "User wants to send an email reminding Jamie. We don't have the address. Let's ask."
    );

    assertThat(calls).isEmpty();
  }

  @Test
  void a_plain_answer_is_not_a_tool_call() {
    var calls = extract("Could you give me Jamie's email address?");

    assertThat(calls).isEmpty();
  }

  /** A name that looks plausible but was never offered must still be rejected. */
  @Test
  void an_undeclared_tool_name_is_rejected() {
    var calls = extract("delete_everything<|constrain|>json<|message|>{\"path\":\"/\"}");

    assertThat(calls).isEmpty();
  }

  /**
   * The span the engine captures starts after the `to=functions.` marker, so it begins with the
   * function name whichever channel the model chose. gpt-oss is instructed to use `commentary`
   * but does not always comply — an `analysis` channel call must extract identically, or the whole
   * call leaks into the answer as text.
   */
  @Test
  void the_span_is_channel_agnostic() {
    var commentary = extract(
      "send_email<|channel|>commentary json<|message|>{\"to\":\"jamie@example.com\"}"
    );
    var analysis = extract(
      "send_email<|channel|>analysis json<|message|>{\"to\":\"jamie@example.com\"}"
    );

    assertThat(commentary).hasSize(1);
    assertThat(analysis).hasSize(1);
    assertThat(analysis.get(0).name()).isEqualTo(commentary.get(0).name());
    assertThat(analysis.get(0).argumentsJson()).isEqualTo(commentary.get(0).argumentsJson());
  }

  /** Underscored names survive the leading-name match (`[\w.-]+` includes underscore). */
  @Test
  void an_underscored_tool_name_extracts() {
    var calls = ToolCallExtractor.extract(
      "read_file<|channel|>analysis json<|message|>{\"path\":\"/tmp/x\"}",
      List.of(Map.of("name", "read_file", "description", "Read a file")),
      "harmony"
    );

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).name()).isEqualTo("read_file");
  }

  @Test
  void no_declared_tools_means_no_calls() {
    assertThat(
      ToolCallExtractor.extract(
        "send_email<|constrain|>json<|message|>{\"to\":\"a@b.c\"}",
        List.of(),
        "harmony"
      )
    ).isEmpty();
  }
}
