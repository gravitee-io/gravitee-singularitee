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
package io.gravitee.singularitee.adapter.textgen;

import io.gravitee.singularitee.engine.Modalities;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the input modalities of a HuggingFace checkpoint out of its {@code config.json}.
 *
 * <p>Used by the vLLM path, which has no equivalent of llama.cpp's {@code mtmd}
 * projector to interrogate. A transformers config declares its extra encoders as
 * sibling blocks of the language config — {@code vision_config} for an image
 * tower, {@code audio_config} for an audio one — so their presence is the
 * checkpoint's own statement about what it will read.
 *
 * <p>Read here rather than through vLLM4j's {@code ModelIntrospection} for two
 * reasons: that call collapses both into one boolean, so it cannot tell a VLM
 * from an ALM; and it runs only inside the VRAM pre-flight, which is skipped
 * whenever {@code memory_check} is disabled or the workspace supplies its own
 * parameter counts — exactly the hand-tuned deployments. {@code VllmModelResolver}
 * already refuses a cache directory that has no {@code config.json}, so by load
 * time the file is local and this costs one small JSON parse.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class CheckpointModalities {

  private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointModalities.class);

  private static final String CONFIG_JSON = "config.json";

  private CheckpointModalities() {}

  /**
   * Returns what the checkpoint in {@code modelDir} accepts as input.
   *
   * <p>Falls back to text-only when the directory is unknown or the config cannot
   * be read — with a warning, because that answer makes media requests fail the
   * pre-flight, and a silent fallback would look like a model that simply refuses
   * images for no stated reason.
   *
   * @param modelDir  local checkpoint directory, or {@code null} when the weights were
   *                  never resolved locally (vLLM resolving the repo id itself)
   * @param modelName model name, for logging only
   */
  static List<String> read(Path modelDir, String modelName) {
    if (modelDir == null) {
      LOGGER.warn(
        "Model '{}' was not resolved to a local directory — assuming text-only input. " +
          "Image and audio requests will be refused; declare `modalities:` in the workspace to override.",
        modelName
      );
      return Modalities.TEXT_ONLY;
    }

    Path config = modelDir.resolve(CONFIG_JSON);
    if (!Files.isRegularFile(config)) {
      LOGGER.warn(
        "No {} under {} for model '{}' — assuming text-only input. " +
          "Image and audio requests will be refused; declare `modalities:` in the workspace to override.",
        CONFIG_JSON,
        modelDir,
        modelName
      );
      return Modalities.TEXT_ONLY;
    }

    try {
      // Vert.x JSON rather than an ObjectMapper of our own: vertx-core is already a
      // dependency of this module, and testing for two keys does not justify pulling
      // jackson-databind onto its compile classpath.
      JsonObject root = new JsonObject(Files.readString(config));
      boolean vision = isPresent(root, "vision_config");
      boolean audio = isPresent(root, "audio_config");
      List<String> modalities = Modalities.of(vision, audio);
      LOGGER.info("Model '{}' accepts {} (read from {})", modelName, modalities, CONFIG_JSON);
      return modalities;
    } catch (Exception e) {
      LOGGER.warn(
        "Could not read {} for model '{}' ({}) — assuming text-only input.",
        CONFIG_JSON,
        modelName,
        e.getMessage()
      );
      return Modalities.TEXT_ONLY;
    }
  }

  /**
   * A key counts only when it holds a populated object: present-but-empty says
   * nothing was configured, and advertising a capability off that would promise a
   * decoder the model does not have.
   */
  private static boolean isPresent(JsonObject root, String field) {
    JsonObject block = root.getJsonObject(field, null);
    return block != null && !block.isEmpty();
  }
}
