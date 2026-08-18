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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.Role;
import org.junit.jupiter.api.Test;

/**
 * Replay of Responses-API {@code input} items ({@code function_call},
 * {@code function_call_output}, {@code reasoning}) into the pipeline transcript.
 */
class ResponsesReplayInputItemsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private ChatMessageList build(String inputArrayJson) throws Exception {
    JsonNode payload = mapper.readTree("{\"input\":" + inputArrayJson + "}");
    return PipelineRequestBuilder.buildResponsesMessageList(payload);
  }

  @Test
  void functionCallItemBecomesAssistantToolCallTurn() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"," +
        "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}]"
    );

    assertThat(list.getMessagesCount()).isEqualTo(1);
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getRole()).isEqualTo(Role.ROLE_ASSISTANT);
    assertThat(msg.getContent()).isEmpty();
    assertThat(msg.getToolCallsCount()).isEqualTo(1);
    assertThat(msg.getToolCalls(0).getId()).isEqualTo("call_1");
    assertThat(msg.getToolCalls(0).getName()).isEqualTo("get_weather");
    assertThat(msg.getToolCalls(0).getArgumentsJson()).isEqualTo("{\"city\":\"Paris\"}");
  }

  @Test
  void functionCallItemFallsBackToIdWhenCallIdIsAbsent() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"function_call\",\"id\":\"fc_9\",\"name\":\"lookup\",\"arguments\":\"{}\"}]"
    );

    assertThat(list.getMessages(0).getToolCalls(0).getId()).isEqualTo("fc_9");
  }

  @Test
  void functionCallItemWithoutArgumentsDefaultsToEmptyObject() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"noop\"}]"
    );

    assertThat(list.getMessages(0).getToolCalls(0).getArgumentsJson()).isEqualTo("{}");
  }

  @Test
  void functionCallOutputBecomesToolTurnWithRoundTrippedCallId() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"sunny, 21C\"}]"
    );

    assertThat(list.getMessagesCount()).isEqualTo(1);
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getRole()).isEqualTo(Role.ROLE_TOOL);
    assertThat(msg.getToolCallId()).isEqualTo("call_1");
    assertThat(msg.getContent()).isEqualTo("sunny, 21C");
  }

  @Test
  void nonTextualFunctionCallOutputIsSerializedAsJson() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":{\"temp\":21}}]"
    );

    assertThat(list.getMessages(0).getContent()).isEqualTo("{\"temp\":21}");
  }

  @Test
  void reasoningItemIsSkipped() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"thought\"}]}," +
        "{\"role\":\"user\",\"content\":\"hello\"}]"
    );

    assertThat(list.getMessagesCount()).isEqualTo(1);
    assertThat(list.getMessages(0).getRole()).isEqualTo(Role.ROLE_USER);
    assertThat(list.getMessages(0).getContent()).isEqualTo("hello");
  }

  @Test
  void unknownItemWithoutRoleOrContentIsDropped() throws Exception {
    ChatMessageList list = build("[{\"type\":\"mystery_item\",\"payload\":42}]");

    assertThat(list.getMessagesCount()).isZero();
  }

  @Test
  void unknownItemTypeWithRoleAndContentFallsBackToMessageHandling() throws Exception {
    ChatMessageList list = build(
      "[{\"type\":\"mystery_item\",\"role\":\"assistant\",\"content\":\"prior answer\"}]"
    );

    assertThat(list.getMessagesCount()).isEqualTo(1);
    assertThat(list.getMessages(0).getRole()).isEqualTo(Role.ROLE_ASSISTANT);
    assertThat(list.getMessages(0).getContent()).isEqualTo("prior answer");
  }

  @Test
  void toolTurnSequenceIsReplayedInOrder() throws Exception {
    ChatMessageList list = build(
      "[\"what is the weather?\"," +
        "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"," +
        "\"arguments\":\"{}\"}," +
        "{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"sunny\"}]"
    );

    assertThat(list.getMessagesCount()).isEqualTo(3);
    assertThat(list.getMessages(0).getRole()).isEqualTo(Role.ROLE_USER);
    assertThat(list.getMessages(1).getRole()).isEqualTo(Role.ROLE_ASSISTANT);
    assertThat(list.getMessages(2).getRole()).isEqualTo(Role.ROLE_TOOL);
    assertThat(list.getMessages(1).getToolCalls(0).getId()).isEqualTo(
      list.getMessages(2).getToolCallId()
    );
  }
}
