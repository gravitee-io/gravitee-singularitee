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
package io.gravitee.singularitee.inference.api.textgen;

public record ChatMessage(Role role, String content, java.util.List<Content> media) {
  public ChatMessage {
    if (content == null) {
      content = "";
    }
    if (media == null) {
      media = java.util.List.of();
    }

    // Validate that media contains only ImageContent or AudioContent
    for (Content c : media) {
      if (!(c instanceof ImageContent || c instanceof AudioContent)) {
        throw new IllegalArgumentException("Media must be either ImageContent or AudioContent");
      }
    }
  }

  public boolean hasMedia() {
    return media != null && !media.isEmpty();
  }

  public boolean hasText() {
    return content != null && !content.trim().isEmpty();
  }

  public boolean isEmpty() {
    return !hasText() && !hasMedia();
  }
}
