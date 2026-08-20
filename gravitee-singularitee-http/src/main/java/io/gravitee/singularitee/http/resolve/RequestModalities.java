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
package io.gravitee.singularitee.http.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.singularitee.engine.Modalities;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads which input modalities an incoming request actually carries.
 *
 * <p>Scans the conversation only — {@code messages} on Chat Completions,
 * {@code input} on Responses — rather than the whole payload, so a tool schema
 * that happens to contain the word {@code image_url} cannot be mistaken for an
 * attached image.
 *
 * <p>The part names mirror what {@code PipelineRequestBuilder.applyContent}
 * accepts, which is what makes this a faithful pre-flight: anything it counts here
 * is something that would have been forwarded to the engine as media.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RequestModalities {

  private RequestModalities() {}

  /**
   * Returns the media modalities present in the request, never including
   * {@link Modalities#TEXT} — the caller is asking "what beyond text is attached
   * here", and text needs no permission.
   */
  public static Set<String> of(JsonNode payload) {
    Set<String> found = new LinkedHashSet<>(2);
    collect(payload.at("/messages"), found);
    collect(payload.at("/input"), found);
    return found;
  }

  private static void collect(JsonNode conversation, Set<String> found) {
    if (!conversation.isArray()) {
      return;
    }
    for (JsonNode message : conversation) {
      JsonNode content = message.at("/content");
      if (!content.isArray()) {
        continue;
      }
      for (JsonNode part : content) {
        switch (part.at("/type").asText("")) {
          case "image_url", "input_image" -> found.add(Modalities.IMAGE);
          case "input_audio" -> found.add(Modalities.AUDIO);
          default -> {
            // Text and unknown part types need no capability from the model.
          }
        }
      }
    }
  }
}
