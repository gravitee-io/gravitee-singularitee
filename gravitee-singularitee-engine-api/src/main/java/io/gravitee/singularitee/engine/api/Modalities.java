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
import io.gravitee.singularitee.protocol.MediaContent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Input modality slugs exposed in {@code GetModelResponse.input_modalities}.
 *
 * <p>Answers what a model will <em>accept</em>, which is a different question from
 * {@link ModelTasks}, which says which endpoint the model belongs on. A
 * vision-language model and a text-only model are both {@code text-generation}:
 * they serve the same endpoint and differ only in what they will read. Folding
 * modality into the task slug would break the routing contract the slug exists
 * to serve, so it travels beside it instead.
 *
 * <p>Every model accepts {@link #TEXT}; {@link #IMAGE} and {@link #AUDIO} are
 * added when the backend reports a projector that can decode them.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Modalities {

  /** Plain text input. Every model accepts it. */
  public static final String TEXT = "text";

  /** Still images ({@code image_url} / {@code input_image} content parts). */
  public static final String IMAGE = "image";

  /** Audio clips ({@code input_audio} content parts). */
  public static final String AUDIO = "audio";

  /** The default for a model that reads nothing but text. */
  public static final List<String> TEXT_ONLY = List.of(TEXT);

  /**
   * Builds the modality list for a backend that reports its projector capabilities.
   * Order is stable (text, then image, then audio) so the value is
   * comparable across responses.
   */
  public static List<String> of(boolean vision, boolean audio) {
    if (!vision && !audio) {
      return TEXT_ONLY;
    }
    var modalities = new ArrayList<String>(3);
    modalities.add(TEXT);
    if (vision) modalities.add(IMAGE);
    if (audio) modalities.add(AUDIO);
    return List.copyOf(modalities);
  }

  /**
   * Returns the media modalities attached to {@code messages}, never including {@link #TEXT}.
   * An unspecified or opaque media type counts as {@link #IMAGE}: it is still handed to the
   * engine as media, so it needs a model that reads media.
   */
  public static Set<String> attached(List<ChatMessage> messages) {
    Set<String> found = new LinkedHashSet<>(2);
    for (ChatMessage message : messages) {
      for (MediaContent media : message.getMediaList()) {
        found.add(
          switch (media.getMediaType()) {
            case MEDIA_TYPE_AUDIO_WAV -> AUDIO;
            default -> IMAGE;
          }
        );
      }
    }
    return found;
  }

  /** The first of {@code attached} that {@code accepted} lacks, if any. */
  public static Optional<String> firstUnsupported(Set<String> attached, List<String> accepted) {
    return attached
      .stream()
      .filter(m -> !accepted.contains(m))
      .findFirst();
  }

  private Modalities() {}
}
