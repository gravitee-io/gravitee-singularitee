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

/**
 * Input modality slugs exposed in {@code GetModelResponse.input_modalities}.
 *
 * <p>Answers what a model will <em>accept</em>, which is a different question from
 * {@link ModelTasks} — that one says which endpoint the model belongs on. A
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

  /** Still images — OpenAI `image_url` / `input_image` content parts. */
  public static final String IMAGE = "image";

  /** Audio clips — OpenAI `input_audio` content parts. */
  public static final String AUDIO = "audio";

  /** The default for a model that reads nothing but text. */
  public static final List<String> TEXT_ONLY = List.of(TEXT);

  /**
   * Builds the modality list for a backend that reports its projector capabilities.
   * Order is stable — text first, then image, then audio — so the value is
   * comparable across responses.
   */
  public static List<String> of(boolean vision, boolean audio) {
    if (!vision && !audio) {
      return TEXT_ONLY;
    }
    var modalities = new java.util.ArrayList<String>(3);
    modalities.add(TEXT);
    if (vision) modalities.add(IMAGE);
    if (audio) modalities.add(AUDIO);
    return List.copyOf(modalities);
  }

  private Modalities() {}
}
