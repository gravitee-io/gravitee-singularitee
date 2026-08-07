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
package io.gravitee.singularitee.grpc.resolver;

import io.vertx.rxjava3.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves GGUF model files for the llama.cpp engine.
 *
 * <p>Resolution logic:
 * <ol>
 *   <li>If {@code modelPath} points to an existing local file, return it as-is.</li>
 *   <li>If the file is already in the local cache, return the cached copy.</li>
 *   <li>Otherwise download the file named {@code modelPath} from the HuggingFace
 *       repository identified by {@code modelName}.</li>
 * </ol>
 *
 * <p>Downloaded files are cached under
 * {@code ~/.cache/gravitee-singularitee/models/{modelName}/}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GgufModelResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(GgufModelResolver.class);
  private static final Path DEFAULT_CACHE_DIR = Path.of(
    System.getProperty("user.home"),
    ".cache",
    "gravitee-singularitee",
    "models"
  );

  private final HuggingFaceModelDownloader downloader;
  private final Path cacheDir;

  public GgufModelResolver(Vertx vertx) {
    this(new HuggingFaceModelDownloader(vertx), DEFAULT_CACHE_DIR);
  }

  public GgufModelResolver(Vertx vertx, String hfToken) {
    this(new HuggingFaceModelDownloader(vertx, hfToken), DEFAULT_CACHE_DIR);
  }

  public GgufModelResolver(HuggingFaceModelDownloader downloader, Path cacheDir) {
    this.downloader = downloader;
    this.cacheDir = cacheDir;
  }

  /**
   * Resolves a GGUF model file to a local path, downloading from HuggingFace
   * if necessary.
   *
   * @param modelName the HuggingFace repository (e.g. "Qwen/Qwen3-0.6B-GGUF")
   * @param modelPath the filename to pick from the repo (e.g. "Qwen3-0.6B-Q8_0.gguf"),
   *                  OR a full local path to an already-downloaded file
   * @return the absolute path to the local GGUF file
   */
  public Path resolve(String modelName, String modelPath) {
    // 1. If modelPath is already a local file, use it directly
    Path localPath = Path.of(modelPath);
    if (Files.exists(localPath)) {
      LOGGER.info("Model path is a local file: {}", localPath.toAbsolutePath());
      return localPath.toAbsolutePath();
    }

    // 2. Check if it was already downloaded in a previous run
    // mirror the HF handle on disk: <cacheRoot>/<org-id>/<model-id>
    Path modelCacheDir = cacheDir.resolve(modelName);
    Path cachedFile = modelCacheDir.resolve(modelPath);
    if (Files.exists(cachedFile)) {
      LOGGER.info("Model already cached: {}", cachedFile.toAbsolutePath());
      return cachedFile.toAbsolutePath();
    }

    // 3. Create cache directory and download from HuggingFace
    LOGGER.info("Downloading [{}] from HuggingFace repository [{}]", modelPath, modelName);
    try {
      Files.createDirectories(modelCacheDir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create cache directory: " + modelCacheDir, e);
    }

    List<Path> downloaded = downloader
      .download(modelName, List.of(modelPath), modelCacheDir)
      .blockingGet();

    if (downloaded.isEmpty()) {
      throw new IllegalStateException(
        "File [" + modelPath + "] not found in HuggingFace repository: " + modelName
      );
    }

    Path resolved = downloaded.getFirst();
    LOGGER.info("Resolved [{}] from [{}] to local path: {}", modelPath, modelName, resolved);
    return resolved;
  }
}
