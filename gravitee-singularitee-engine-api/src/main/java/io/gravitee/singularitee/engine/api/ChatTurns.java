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

import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.MediaType;
import java.util.List;

/**
 * Maps wire chat messages to {@link ChatTurn}s, attached media included, so a raw model call and
 * a pipeline see the same conversation.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ChatTurns {

  private ChatTurns() {}

  /** One turn per message, in order. */
  public static List<ChatTurn> fromProto(ChatMessageList messages) {
    return messages.getMessagesList().stream().map(ChatTurns::fromProto).toList();
  }

  /** {@code message} as a turn: role, text, media, tool calls and tool identity. */
  public static ChatTurn fromProto(ChatMessage message) {
    var role = switch (message.getRole()) {
      case ROLE_SYSTEM -> ChatRole.SYSTEM;
      case ROLE_ASSISTANT -> ChatRole.ASSISTANT;
      case ROLE_TOOL -> ChatRole.TOOL;
      default -> ChatRole.USER;
    };
    var media = message
      .getMediaList()
      .stream()
      .map(mc ->
        new MediaAttachment(attachmentType(mc.getMediaType()), mc.getData().toStringUtf8())
      )
      .toList();
    var toolCalls = message
      .getToolCallsList()
      .stream()
      .map(tc -> new ChatTurn.ToolCallTurn(tc.getId(), tc.getName(), tc.getArgumentsJson()))
      .toList();
    return new ChatTurn(
      role,
      message.getContent(),
      media,
      toolCalls,
      message.getToolCallId().isEmpty() ? null : message.getToolCallId(),
      message.getName().isEmpty() ? null : message.getName()
    );
  }

  /** The attachment type for a wire media type; anything unrecognised is opaque bytes. */
  public static MediaAttachmentType attachmentType(MediaType type) {
    return switch (type) {
      case MEDIA_TYPE_IMAGE_JPEG -> MediaAttachmentType.IMAGE_JPEG;
      case MEDIA_TYPE_IMAGE_PNG -> MediaAttachmentType.IMAGE_PNG;
      case MEDIA_TYPE_IMAGE_GIF -> MediaAttachmentType.IMAGE_GIF;
      case MEDIA_TYPE_IMAGE_BMP -> MediaAttachmentType.IMAGE_BMP;
      case MEDIA_TYPE_AUDIO_WAV -> MediaAttachmentType.AUDIO_WAV;
      default -> MediaAttachmentType.APPLICATION_OCTET_STREAM;
    };
  }
}
