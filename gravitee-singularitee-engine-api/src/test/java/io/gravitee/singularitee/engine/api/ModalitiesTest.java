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
package io.gravitee.singularitee.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.MediaType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModalitiesTest {

  @Test
  void text_is_always_present_and_first() {
    assertThat(Modalities.of(false, false)).containsExactly("text");
    assertThat(Modalities.of(true, false)).containsExactly("text", "image");
    assertThat(Modalities.of(false, true)).containsExactly("text", "audio");
    assertThat(Modalities.of(true, true)).containsExactly("text", "image", "audio");
  }

  @Test
  void a_text_only_answer_reuses_the_shared_constant() {
    assertThat(Modalities.of(false, false)).isSameAs(Modalities.TEXT_ONLY);
  }

  @Test
  void attached_media_maps_to_modalities() {
    var messages = List.of(
      message(MediaType.MEDIA_TYPE_IMAGE_JPEG),
      message(MediaType.MEDIA_TYPE_AUDIO_WAV),
      ChatMessage.newBuilder().setContent("plain").build()
    );
    assertThat(Modalities.attached(messages)).containsExactly("image", "audio");
  }

  @Test
  void opaque_media_needs_a_media_capable_model() {
    assertThat(
      Modalities.attached(List.of(message(MediaType.MEDIA_TYPE_APPLICATION_OCTET)))
    ).containsExactly("image");
  }

  @Test
  void first_unsupported_names_the_missing_modality() {
    assertThat(Modalities.firstUnsupported(Set.of("image"), Modalities.TEXT_ONLY)).contains(
      "image"
    );
    assertThat(Modalities.firstUnsupported(Set.of("image"), List.of("text", "image"))).isEmpty();
    assertThat(Modalities.firstUnsupported(Set.of(), Modalities.TEXT_ONLY)).isEmpty();
  }

  private static ChatMessage message(MediaType type) {
    return ChatMessage.newBuilder()
      .addMedia(MediaContent.newBuilder().setMediaType(type).setData(ByteString.copyFromUtf8("x")))
      .build();
  }
}
