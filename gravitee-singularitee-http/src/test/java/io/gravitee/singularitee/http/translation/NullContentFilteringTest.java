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
package io.gravitee.singularitee.http.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Inbound messages whose {@code content} carries no text.
 *
 * <p>{@code {"role":"assistant","content":null,"tool_calls":[...]}} is the OpenAI-correct shape for
 * a tool-call turn, and it is what a client echoes back from our own responses. Rendering that JSON
 * null as the four characters {@code "null"} puts it in the prompt as the assistant's words; over a
 * long agent session the model reads dozens of turns saying {@code null} and starts emitting it —
 * observed verbatim as {@code <|channel|>final<|message|>null<|end|>}.
 *
 * @author GraviteeSource Team
 */
class NullContentFilteringTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static java.util.List<String> contents(String json) throws Exception {
    return PipelineRequestBuilder.buildChatMessageList(MAPPER.readTree(json))
      .getMessagesList()
      .stream()
      .map(m -> m.getContent())
      .toList();
  }

  @Test
  void a_null_content_tool_call_turn_is_kept_with_empty_content() throws Exception {
    // The turn must survive — dropping it erases the fact that the assistant acted, and the model
    // replays the conversation as an unanswered question and calls the same tool again. What must
    // NOT survive is the JSON null as text.
    var messages = PipelineRequestBuilder.buildChatMessageList(
      MAPPER.readTree(
        """
        [{"role":"user","content":"show me the staged files"},
         {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",
           "function":{"name":"bash","arguments":{"command":"git diff --staged"}}}]},
         {"role":"tool","tool_call_id":"c1","name":"bash","content":"pom.xml"}]
        """
      )
    ).getMessagesList();

    assertThat(messages).hasSize(3);
    assertThat(messages.stream().map(m -> m.getContent())).noneMatch(c -> c.contains("null"));

    var assistant = messages.get(1);
    assertThat(assistant.getContent()).isEmpty();
    assertThat(assistant.getToolCallsList()).hasSize(1);
    assertThat(assistant.getToolCalls(0).getName()).isEqualTo("bash");
    assertThat(assistant.getToolCalls(0).getId()).isEqualTo("c1");
    assertThat(assistant.getToolCalls(0).getArgumentsJson()).contains("git diff --staged");

    var toolResult = messages.get(2);
    assertThat(toolResult.getRole()).isEqualTo(io.gravitee.singularitee.protocol.Role.ROLE_TOOL);
    assertThat(toolResult.getToolCallId()).isEqualTo("c1");
    assertThat(toolResult.getName()).isEqualTo("bash");
  }

  @Test
  void a_null_content_turn_with_no_calls_is_still_dropped() throws Exception {
    assertThat(
      contents(
        """
        [{"role":"assistant","content":null},{"role":"user","content":"hi"}]
        """
      )
    ).containsExactly("hi");
  }

  @Test
  void a_missing_content_message_is_still_dropped() throws Exception {
    assertThat(
      contents(
        """
        [{"role":"assistant"},{"role":"user","content":"hi"}]
        """
      )
    ).containsExactly("hi");
  }

  @Test
  void ordinary_messages_are_untouched() throws Exception {
    assertThat(
      contents(
        """
        [{"role":"system","content":"be terse"},
         {"role":"user","content":"hello"},
         {"role":"assistant","content":"hi there"}]
        """
      )
    ).containsExactly("be terse", "hello", "hi there");
  }

  /** The same trap for any non-text JSON: its source must not become the message's words. */
  @Test
  void non_text_content_does_not_leak_its_json_source() throws Exception {
    var result = contents(
      """
      [{"role":"assistant","content":{"unexpected":"shape"}},
       {"role":"assistant","content":42}]
      """
    );

    assertThat(result).allMatch(String::isEmpty);
    assertThat(result).noneMatch(c -> c.contains("unexpected") || c.contains("42"));
  }
}
