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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.llmbridge4j.core.LlmBridge;
import io.gravitee.llmbridge4j.core.or.v1.model.LlmRequest;
import io.gravitee.llmbridge4j.openai.chat.OpenAiChatAdapter;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.MediaType;
import org.junit.jupiter.api.Test;

class CanonicalChatRequestMapperTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final LlmBridge BRIDGE = LlmBridge.builder()
    .adapter(new OpenAiChatAdapter())
    .build();

  @Test
  void directMappingPreservesLegacyRequestSemantics() throws Exception {
    JsonNode payload = json(
      """
      {
        "model":"model",
        "messages":[
          {"role":"system","content":"be concise"},
          {"role":"developer","content":"follow policy"},
          {"role":"user","content":"hello"},
          {"role":"assistant","content":"working"}
        ],
        "temperature":0.25,
        "top_p":0.8,
        "max_completion_tokens":33,
        "presence_penalty":0.1,
        "frequency_penalty":0.2,
        "seed":7,
        "stop":["END"],
        "reasoning_effort":"high",
        "prompt_cache_key":"cache",
        "logprobs":true,
        "top_logprobs":3,
        "tools":[{"type":"function","function":{"name":"lookup","description":"Find it",
          "parameters":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}}}]
      }
      """
    );

    InferRequest legacy = InferRequestBuilder.build("model", payload, EndpointType.CHAT);
    InferRequest actual = CanonicalChatRequestMapper.toDirect("model", canonical(payload));

    assertThat(actual.toBuilder().clearRequestId().build()).isEqualTo(
      legacy.toBuilder().clearRequestId().build()
    );
  }

  @Test
  void pipelineMappingPreservesStructuredLegacySemantics() throws Exception {
    JsonNode payload = json(
      """
      {
        "model":"pipeline:chat",
        "messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"}],
        "temperature":0.4,
        "max_tokens":19,
        "reasoning_effort":"medium",
        "user":"legacy-user",
        "tools":[{"type":"function","function":{"name":"lookup","description":"Find it",
          "parameters":{"type":"object","properties":{"q":{"type":"string","description":"query"}},"required":["q"]}}}]
      }
      """
    );

    InferPipelineRequest legacy = PipelineRequestBuilder.build("chat", payload, EndpointType.CHAT);
    InferPipelineRequest actual = CanonicalChatRequestMapper.toPipeline("chat", canonical(payload));

    assertThat(actual).isEqualTo(legacy);
  }

  @Test
  void toolCallsAndResultsReconstructTheLegacyTranscript() throws Exception {
    JsonNode payload = json(
      """
      {
        "model":"model",
        "messages":[
          {"role":"user","content":"What time is it?"},
          {"role":"assistant","content":null,"tool_calls":[
            {"id":"call-1","type":"function","function":{"name":"clock","arguments":"{\\"zone\\":\\"UTC\\"}"}}
          ]},
          {"role":"tool","tool_call_id":"call-1","name":"clock","content":"12:00"}
        ]
      }
      """
    );

    InferRequest legacy = InferRequestBuilder.build("model", payload, EndpointType.CHAT);
    InferRequest actual = CanonicalChatRequestMapper.toDirect("model", canonical(payload));

    assertThat(actual.toBuilder().clearRequestId().build()).isEqualTo(
      legacy.toBuilder().clearRequestId().build()
    );
  }

  @Test
  void legacyUserProvidesCacheAffinityWhenPromptCacheKeyIsAbsent() throws Exception {
    JsonNode payload = json(
      """
      {"model":"model","user":"legacy-user","messages":[{"role":"user","content":"hi"}]}
      """
    );
    InferRequest actual = CanonicalChatRequestMapper.toDirect("model", canonical(payload));
    assertThat(actual.getCacheKey()).isEqualTo("legacy-user");
  }

  @Test
  void mediaMappingMatchesLegacyAndRemoteMediaIsRejected() throws Exception {
    JsonNode payload = json(
      """
      {"model":"model","messages":[{"role":"user","content":[
        {"type":"text","text":"describe"},
        {"type":"image_url","image_url":{"url":"data:image/png;base64,aGk="}},
        {"type":"input_audio","input_audio":{"data":"aGk=","format":"wav"}}
      ]}]}
      """
    );
    InferRequest actual = CanonicalChatRequestMapper.toDirect("model", canonical(payload));
    var message = actual.getMessages().getMessages(0);
    assertThat(message.getContent()).isEqualTo("describe");
    assertThat(message.getMediaList())
      .extracting(media -> media.getMediaType())
      .containsExactly(MediaType.MEDIA_TYPE_IMAGE_PNG, MediaType.MEDIA_TYPE_AUDIO_WAV);

    JsonNode remote = json(
      """
      {"model":"model","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/cat.png"}}]}]}
      """
    );
    assertThatThrownBy(() -> CanonicalChatRequestMapper.toDirect("model", canonical(remote)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Remote image URLs are not supported");
  }

  @Test
  void nullAndAbsentMessageContentRetainsLegacyFiltering() throws Exception {
    JsonNode payload = json(
      """
      {"model":"model","messages":[
        {"role":"assistant","content":null},
        {"role":"user"},
        {"role":"user","content":"hello"},
        {"role":"user","content":""}
      ]}
      """
    );
    InferRequest legacy = InferRequestBuilder.build("model", payload, EndpointType.CHAT);
    InferRequest actual = CanonicalChatRequestMapper.toDirect("model", canonical(payload));
    assertThat(actual.toBuilder().clearRequestId().build()).isEqualTo(
      legacy.toBuilder().clearRequestId().build()
    );
  }

  private static LlmRequest canonical(JsonNode payload) {
    return BRIDGE.toCanonical("openai-chat", payload);
  }

  private static JsonNode json(String source) throws Exception {
    return MAPPER.readTree(source);
  }
}
