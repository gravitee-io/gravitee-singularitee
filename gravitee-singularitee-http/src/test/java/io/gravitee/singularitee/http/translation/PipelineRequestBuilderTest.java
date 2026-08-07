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
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.MediaType;
import org.junit.jupiter.api.Test;

class PipelineRequestBuilderTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode json(String s) throws Exception {
    return mapper.readTree(s);
  }

  @Test
  void plainStringContentStillWorks() throws Exception {
    ChatMessageList list = PipelineRequestBuilder.buildChatMessageList(
      json("[{\"role\":\"user\",\"content\":\"hello\"}]")
    );
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getContent()).isEqualTo("hello");
    assertThat(msg.getMediaCount()).isZero();
  }

  @Test
  void extractsImageFromDataUrlWithDetectedType() throws Exception {
    // "hi" base64-encoded is "aGk=".
    ChatMessageList list = PipelineRequestBuilder.buildChatMessageList(
      json(
        "[{\"role\":\"user\",\"content\":[" +
          "{\"type\":\"text\",\"text\":\"what is this?\"}," +
          "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aGk=\"}}" +
          "]}]"
      )
    );
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getContent()).isEqualTo("what is this?");
    assertThat(msg.getMediaCount()).isEqualTo(1);
    assertThat(msg.getMedia(0).getMediaType()).isEqualTo(MediaType.MEDIA_TYPE_IMAGE_PNG);
    // Payload is stored as the base64 string (UTF-8 bytes), matching the gRPC/engine contract.
    assertThat(msg.getMedia(0).getData().toStringUtf8()).isEqualTo("aGk=");
  }

  @Test
  void extractsAudioUsingFormatHint() throws Exception {
    ChatMessageList list = PipelineRequestBuilder.buildChatMessageList(
      json(
        "[{\"role\":\"user\",\"content\":[" +
          "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"aGk=\",\"format\":\"wav\"}}" +
          "]}]"
      )
    );
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getMediaCount()).isEqualTo(1);
    assertThat(msg.getMedia(0).getMediaType()).isEqualTo(MediaType.MEDIA_TYPE_AUDIO_WAV);
    assertThat(msg.getMedia(0).getData().toStringUtf8()).isEqualTo("aGk=");
  }

  @Test
  void unknownMimeFallsBackToOctet() throws Exception {
    ChatMessageList list = PipelineRequestBuilder.buildChatMessageList(
      json(
        "[{\"role\":\"user\",\"content\":[" +
          "{\"type\":\"image_url\",\"image_url\":{\"url\":\"aGk=\"}}" +
          "]}]"
      )
    );
    assertThat(list.getMessages(0).getMedia(0).getMediaType()).isEqualTo(
      MediaType.MEDIA_TYPE_APPLICATION_OCTET
    );
  }

  @Test
  void remoteImageUrlThrows() throws Exception {
    JsonNode messages = json(
      "[{\"role\":\"user\",\"content\":[" +
        "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.png\"}}" +
        "]}]"
    );
    assertThatThrownBy(() -> PipelineRequestBuilder.buildChatMessageList(messages))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Remote image URLs are not supported");
  }

  /**
   * Formats without a decoder behind them must NOT map to a dedicated MediaType.
   * Compressed audio is decoded by {@code javax.sound.sampled}, which has no
   * reader for it; when these had their own enum constants the payload reached
   * the engine, failed to decode, and the request completed with empty content
   * and zero tokens instead of an error.
   */
  @Test
  void undecodableAudioFormatsFallThroughToOctetStream() throws Exception {
    for (String format : new String[] { "mp3", "ogg", "flac", "aac", "m4a" }) {
      ChatMessageList list = PipelineRequestBuilder.buildResponsesMessageList(
        json(
          "{\"input\":[{\"role\":\"user\",\"content\":[" +
            "{\"type\":\"input_text\",\"text\":\"transcribe\"}," +
            "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"aGk=\",\"format\":\"" +
            format +
            "\"}}" +
            "]}]}"
        )
      );
      assertThat(list.getMessages(0).getMedia(0).getMediaType())
        .as("audio format %s must not claim a decodable MediaType", format)
        .isEqualTo(MediaType.MEDIA_TYPE_APPLICATION_OCTET);
    }
  }

  @Test
  void responsesInputAudioIsExtracted() throws Exception {
    ChatMessageList list = PipelineRequestBuilder.buildResponsesMessageList(
      json(
        "{\"input\":[{\"role\":\"user\",\"content\":[" +
          "{\"type\":\"input_text\",\"text\":\"transcribe\"}," +
          "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"aGk=\",\"format\":\"wav\"}}" +
          "]}]}"
      )
    );
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getContent()).isEqualTo("transcribe");
    assertThat(msg.getMedia(0).getMediaType()).isEqualTo(MediaType.MEDIA_TYPE_AUDIO_WAV);
    assertThat(msg.getMedia(0).getData().toStringUtf8()).isEqualTo("aGk=");
  }

  @Test
  void responsesInputImageIsExtracted() throws Exception {
    ChatMessageList list = PipelineRequestBuilder.buildResponsesMessageList(
      json(
        "{\"input\":[{\"role\":\"user\",\"content\":[" +
          "{\"type\":\"input_text\",\"text\":\"describe\"}," +
          "{\"type\":\"input_image\",\"image_url\":\"data:image/jpeg;base64,aGk=\"}" +
          "]}]}"
      )
    );
    ChatMessage msg = list.getMessages(0);
    assertThat(msg.getContent()).isEqualTo("describe");
    assertThat(msg.getMedia(0).getMediaType()).isEqualTo(MediaType.MEDIA_TYPE_IMAGE_JPEG);
  }

  @Test
  void reasoningEffortIsCarriedInPipelineContext() throws Exception {
    var req = PipelineRequestBuilder.build(
      "pipe",
      json("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"reasoning_effort\":\"high\"}"),
      EndpointType.CHAT
    );
    assertThat(req.getContextMap()).containsEntry("reasoning_effort", "high");
  }

  @Test
  void nestedReasoningEffortIsAcceptedOnResponsesShape() throws Exception {
    var req = PipelineRequestBuilder.build(
      "pipe",
      json("{\"input\":\"hi\",\"reasoning\":{\"effort\":\"medium\"}}"),
      EndpointType.RESPONSES
    );
    assertThat(req.getContextMap()).containsEntry("reasoning_effort", "medium");
  }

  @Test
  void flatReasoningEffortWinsOverNested() throws Exception {
    var req = PipelineRequestBuilder.build(
      "pipe",
      json(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
          "\"reasoning_effort\":\"high\",\"reasoning\":{\"effort\":\"low\"}}"
      ),
      EndpointType.CHAT
    );
    assertThat(req.getContextMap()).containsEntry("reasoning_effort", "high");
  }

  @Test
  void absentReasoningEffortLeavesContextEmpty() throws Exception {
    var req = PipelineRequestBuilder.build(
      "pipe",
      json("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),
      EndpointType.CHAT
    );
    assertThat(req.getContextMap()).doesNotContainKey("reasoning_effort");
  }

  @Test
  void reasoningEffortIsCarriedAsTemplateContextOnDirectPath() throws Exception {
    var req = InferRequestBuilder.build(
      "model",
      json("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"reasoning_effort\":\"low\"}"),
      EndpointType.CHAT
    );
    assertThat(req.hasTemplateContext()).isTrue();
    assertThat(
      req.getTemplateContext().getFieldsOrThrow("reasoning_effort").getStringValue()
    ).isEqualTo("low");
  }

  @Test
  void absentReasoningEffortLeavesTemplateContextUnset() throws Exception {
    var req = InferRequestBuilder.build(
      "model",
      json("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),
      EndpointType.CHAT
    );
    assertThat(req.hasTemplateContext()).isFalse();
  }
}
