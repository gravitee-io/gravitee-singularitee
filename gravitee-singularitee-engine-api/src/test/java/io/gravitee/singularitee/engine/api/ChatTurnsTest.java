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
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.MediaType;
import io.gravitee.singularitee.protocol.Role;
import org.junit.jupiter.api.Test;

class ChatTurnsTest {

  private static final ChatMessageList WITH_IMAGE = ChatMessageList.newBuilder()
    .addMessages(ChatMessage.newBuilder().setRole(Role.ROLE_SYSTEM).setContent("be brief"))
    .addMessages(
      ChatMessage.newBuilder()
        .setRole(Role.ROLE_USER)
        .setContent("what colour?")
        .addMedia(
          MediaContent.newBuilder()
            .setMediaType(MediaType.MEDIA_TYPE_IMAGE_PNG)
            .setData(ByteString.copyFromUtf8("aGVsbG8="))
        )
    )
    .build();

  @Test
  void maps_role_text_and_media() {
    var turns = ChatTurns.fromProto(WITH_IMAGE);
    assertThat(turns).extracting(ChatTurn::role).containsExactly(ChatRole.SYSTEM, ChatRole.USER);
    assertThat(turns.get(0).media()).isEmpty();
    assertThat(turns.get(1).media()).containsExactly(
      new MediaAttachment(MediaAttachmentType.IMAGE_PNG, "aGVsbG8=")
    );
  }

  @Test
  void pipeline_context_keeps_attached_media() {
    var ctx = PipelineContext.fromRequest(
      InferPipelineRequest.newBuilder().setPipelineId("p").setMessages(WITH_IMAGE).build()
    );
    assertThat(ctx.messages().get(1).media()).containsExactly(
      new MediaAttachment(MediaAttachmentType.IMAGE_PNG, "aGVsbG8=")
    );
    assertThat(ctx.get(PipelineContext.KEY_PROMPT)).isEqualTo("what colour?");
  }

  @Test
  void unrecognised_media_type_is_opaque_bytes() {
    assertThat(ChatTurns.attachmentType(MediaType.MEDIA_TYPE_UNSPECIFIED)).isEqualTo(
      MediaAttachmentType.APPLICATION_OCTET_STREAM
    );
  }
}
