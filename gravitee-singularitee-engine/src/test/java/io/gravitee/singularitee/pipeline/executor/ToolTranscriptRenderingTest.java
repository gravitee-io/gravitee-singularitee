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

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a transcript turn looks like by the time a chat template sees it.
 *
 * <p>A tool-using conversation only makes sense to the model if it can see what it already did.
 * With {@code tool_calls} missing, the replayed conversation is a question the assistant never
 * acted on: it plans the same call, runs it, gets the result as an anonymous message, and plans
 * the same call again — observed as an agent looping on one {@code git log} indefinitely.
 *
 * @author GraviteeSource Team
 */
class ToolTranscriptRenderingTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> functionOf(Map<String, Object> message, int index) {
    var calls = (List<Map<String, Object>>) message.get("tool_calls");
    return (Map<String, Object>) calls.get(index).get("function");
  }

  @Test
  void an_assistant_turn_carries_its_calls() {
    var turn = new ChatTurn(
      ChatRole.ASSISTANT,
      "",
      List.of(),
      List.of(new ChatTurn.ToolCallTurn("c1", "bash", "{\"command\":\"git log -n 1\"}")),
      null,
      null
    );

    var message = InferStepExecutor.toTemplateMessage(turn);

    assertThat(message.get("role")).isEqualTo("assistant");
    assertThat(functionOf(message, 0).get("name")).isEqualTo("bash");
  }

  @Test
  void arguments_are_a_mapping_not_their_json_text() {
    // Templates iterate arguments as a mapping — Gemma's raises outright on a string.
    var turn = new ChatTurn(
      ChatRole.ASSISTANT,
      "",
      List.of(),
      List.of(new ChatTurn.ToolCallTurn("c1", "bash", "{\"command\":\"git log -n 1\"}")),
      null,
      null
    );

    var arguments = functionOf(InferStepExecutor.toTemplateMessage(turn), 0).get("arguments");

    assertThat(arguments).isInstanceOf(Map.class);
    assertThat((Map<String, Object>) arguments).containsEntry("command", "git log -n 1");
  }

  @Test
  void unparseable_arguments_degrade_to_an_empty_mapping() {
    // Fail-open: a malformed argument blob must not take the whole render down with it.
    var turn = new ChatTurn(
      ChatRole.ASSISTANT,
      "",
      List.of(),
      List.of(new ChatTurn.ToolCallTurn("c1", "bash", "{not json")),
      null,
      null
    );

    assertThat(functionOf(InferStepExecutor.toTemplateMessage(turn), 0).get("arguments")).isEqualTo(
      Map.of()
    );
  }

  @Test
  void a_tool_result_keeps_the_id_that_pairs_it_to_its_call() {
    var turn = new ChatTurn(ChatRole.TOOL, "pom.xml", List.of(), List.of(), "c1", "bash");

    var message = InferStepExecutor.toTemplateMessage(turn);

    assertThat(message.get("role")).isEqualTo("tool");
    assertThat(message.get("tool_call_id")).isEqualTo("c1");
    assertThat(message.get("name")).isEqualTo("bash");
    assertThat(message.get("content")).isEqualTo("pom.xml");
  }

  @Test
  void an_ordinary_turn_carries_no_tool_keys() {
    var message = InferStepExecutor.toTemplateMessage(new ChatTurn(ChatRole.USER, "hello"));

    assertThat(message).containsOnlyKeys("role", "content");
  }

  @Test
  void null_content_renders_as_empty_text_never_as_the_word_null() {
    var message = InferStepExecutor.toTemplateMessage(
      new ChatTurn(
        ChatRole.ASSISTANT,
        null,
        List.of(),
        List.of(new ChatTurn.ToolCallTurn("c1", "bash", "{}")),
        null,
        null
      )
    );

    assertThat(message.get("content")).isEqualTo("");
  }
}
