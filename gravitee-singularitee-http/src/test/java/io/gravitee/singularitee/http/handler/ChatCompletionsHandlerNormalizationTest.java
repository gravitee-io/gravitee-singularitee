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
package io.gravitee.singularitee.http.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.llmbridge4j.core.LlmBridge;
import io.gravitee.llmbridge4j.openai.chat.OpenAiChatAdapter;
import io.gravitee.singularitee.http.translation.CanonicalChatRequestMapper;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.Role;
import org.junit.jupiter.api.Test;

class ChatCompletionsHandlerNormalizationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final LlmBridge BRIDGE = LlmBridge.builder()
    .adapter(new OpenAiChatAdapter())
    .build();

  @Test
  void normalizesOnADeepCopy() throws Exception {
    JsonNode original = json(
      """
      {"model":"model","logprobs":true,"reasoning":{"effort":"medium"},
       "messages":[{"role":"legacy","content":"hello"}]}
      """
    );

    JsonNode normalized = ChatCompletionsHandler.normalizeForBridge(original);

    assertThat(original.path("reasoning_effort").isMissingNode()).isTrue();
    assertThat(original.path("top_logprobs").isMissingNode()).isTrue();
    assertThat(original.at("/messages/0/role").asText()).isEqualTo("legacy");
    assertThat(normalized.path("reasoning_effort").asText()).isEqualTo("medium");
    assertThat(normalized.path("top_logprobs").asInt()).isEqualTo(1);
    assertThat(normalized.at("/messages/0/role").asText()).isEqualTo("user");
  }

  @Test
  void flatReasoningEffortAndExplicitTopLogprobsWin() throws Exception {
    JsonNode normalized = ChatCompletionsHandler.normalizeForBridge(
      json(
        """
        {"reasoning_effort":"high","reasoning":{"effort":"low"},
         "logprobs":true,"top_logprobs":7,"messages":[{"role":"user","content":"hi"}]}
        """
      )
    );

    assertThat(normalized.path("reasoning_effort").asText()).isEqualTo("high");
    assertThat(normalized.path("top_logprobs").asInt()).isEqualTo(7);
  }

  @Test
  void knownDeveloperRoleIsRetainedWhileUnknownRoleFallsBackToUser() throws Exception {
    JsonNode normalized = ChatCompletionsHandler.normalizeForBridge(
      json(
        """
        {"messages":[{"role":"developer","content":"policy"},
                     {"role":"vendor-role","content":"legacy"}]}
        """
      )
    );

    assertThat(normalized.at("/messages/0/role").asText()).isEqualTo("developer");
    assertThat(normalized.at("/messages/1/role").asText()).isEqualTo("user");
  }

  @Test
  void normalizedCompatibilityFieldsReachTheCanonicalMapper() throws Exception {
    JsonNode normalized = ChatCompletionsHandler.normalizeForBridge(
      json(
        """
        {"model":"model","logprobs":true,"reasoning":{"effort":"medium"},
         "messages":[{"role":"vendor-role","content":"hello"}]}
        """
      )
    );

    var canonical = BRIDGE.toCanonical("openai-chat", normalized);
    InferRequest request = CanonicalChatRequestMapper.toDirect("model", canonical);

    assertThat(request.getSamplingParams().getTopLogprobs()).isEqualTo(1);
    assertThat(
      request.getTemplateContext().getFieldsOrThrow("reasoning_effort").getStringValue()
    ).isEqualTo("medium");
    assertThat(request.getMessages().getMessages(0).getRole()).isEqualTo(Role.ROLE_USER);
  }

  private static JsonNode json(String source) throws Exception {
    return MAPPER.readTree(source);
  }
}
