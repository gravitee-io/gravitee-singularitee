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
package io.gravitee.singularitee.engine;

import java.util.List;
import java.util.Optional;

/**
 * A chat-conversation turn submitted to a {@link TextGenEngine}.
 *
 * <p>This is the local equivalent of {@code ChatMessage} from
 * {@code gravitee-inference-api}. Defined here so that no layer above the
 * {@code adapter} package ever imports an external inference type.
 *
 * @param role    the speaker role in the conversation
 * @param content the text content of the turn (may be {@code null} if only media is present)
 * @param media   optional multimodal attachments (images, audio)
 * @param toolCalls calls this assistant turn made; empty for every other role
 * @param toolCallId for a {@link ChatRole#TOOL} turn, the id of the call it answers
 * @param name       for a {@link ChatRole#TOOL} turn, the tool's name when known
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ChatTurn(
  ChatRole role,
  String content,
  List<MediaAttachment> media,
  List<ToolCallTurn> toolCalls,
  String toolCallId,
  String name
) {
  public ChatTurn {
    media = media == null ? List.of() : List.copyOf(media);
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /** A call the assistant made, as recorded in the transcript. */
  public record ToolCallTurn(String id, String name, String argumentsJson) {}

  /**
   * Convenience constructor for text-only turns.
   *
   * @param role    the speaker role
   * @param content the text content
   */
  public ChatTurn(ChatRole role, String content) {
    this(role, content, List.of());
  }

  /** Text plus media, with no tool metadata. */
  public ChatTurn(ChatRole role, String content, List<MediaAttachment> media) {
    this(role, content, media, List.of(), null, null);
  }

  /**
   * Returns the content of the last {@link ChatRole#USER} turn in the given
   * list, or {@link Optional#empty()} if there is none.
   *
   * @param turns the conversation turns (may be {@code null})
   * @return the last user content, if any
   */
  public static Optional<String> lastUserContent(List<ChatTurn> turns) {
    if (turns == null || turns.isEmpty()) return Optional.empty();
    return turns
      .stream()
      .filter(t -> t.role() == ChatRole.USER)
      .reduce((a, b) -> b)
      .map(ChatTurn::content);
  }
}
