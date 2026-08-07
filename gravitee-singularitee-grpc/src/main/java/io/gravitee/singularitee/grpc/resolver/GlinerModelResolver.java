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

import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.GlinerClassifierConfig;
import io.gravitee.singularitee.workspace.config.GlinerNerConfig;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves GLiNER model directories, downloading from HuggingFace when needed.
 *
 * <p>GLiNER4j expects a single root directory containing:
 * <ul>
 *   <li>ONNX variant sub-directories ({@code onnx/}, {@code onnx_fp16/}, {@code onnx_quantized/})</li>
 *   <li>Tokenizer files ({@code tokenizer.json}, etc.) at the root</li>
 *   <li>{@code gliner_config.json} at the root</li>
 * </ul>
 *
 * <p>Resolution strategy for the {@code model_dir} field:
 * <ol>
 *   <li>If the path is an existing local directory, use it as-is.</li>
 *   <li>If a cached copy exists at {@code ~/.cache/gravitee-singularitee/models/{name}/}, use it.</li>
 *   <li>Otherwise download the required files from HuggingFace and cache locally.</li>
 * </ol>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerModelResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlinerModelResolver.class);

  private static final Path DEFAULT_CACHE_DIR = Path.of(
    System.getProperty("user.home"),
    ".cache",
    "gravitee-singularitee",
    "models"
  );

  private final HuggingFaceModelDownloader downloader;
  private final Path cacheDir;

  public GlinerModelResolver(Vertx vertx, String hfToken) {
    this(new HuggingFaceModelDownloader(vertx, hfToken), DEFAULT_CACHE_DIR);
  }

  public GlinerModelResolver(HuggingFaceModelDownloader downloader, Path cacheDir) {
    this.downloader = downloader;
    this.cacheDir = cacheDir;
  }

  /**
   * Resolves all GLiNER paths in the request and returns a new request with
   * the {@code model_dir} rewritten to an absolute local path.
   */
  public Single<ModelLoadRequest> resolve(ModelLoadRequest request) {
    String modelName = request.modelName();
    // mirror the HF handle on disk: <cacheRoot>/<org-id>/<model-id>
    Path modelCacheDir = cacheDir.resolve(modelName);

    if (request.hasGlinerClassifier()) {
      return resolveClassifier(request, modelName, modelCacheDir);
    } else if (request.hasGlinerNer()) {
      return resolveNer(request, modelName, modelCacheDir);
    }
    return Single.just(request);
  }

  private Single<ModelLoadRequest> resolveClassifier(
    ModelLoadRequest request,
    String modelName,
    Path modelCacheDir
  ) {
    GlinerClassifierConfig cfg = request.glinerClassifier();
    String variant = cfg.variant().isBlank() ? "onnx" : cfg.variant();
    return resolveModelDir(
      modelName,
      cfg.modelDir(),
      variant,
      modelCacheDir,
      request.downloadExclude()
    ).map(resolvedDir -> {
      var newCfg = cfg.withModelDir(resolvedDir.toString());
      return new ModelLoadRequest(
        request.modelId(),
        request.modelName(),
        request.modelPath(),
        request.memoryCheckPolicy(),
        null,
        null,
        null,
        null,
        newCfg,
        null,
        null,
        null,
        null,
        request.downloadExclude()
      );
    });
  }

  private Single<ModelLoadRequest> resolveNer(
    ModelLoadRequest request,
    String modelName,
    Path modelCacheDir
  ) {
    GlinerNerConfig cfg = request.glinerNer();
    String variant = cfg.variant().isBlank() ? "onnx" : cfg.variant();
    return resolveModelDir(
      modelName,
      cfg.modelDir(),
      variant,
      modelCacheDir,
      request.downloadExclude()
    ).map(resolvedDir -> {
      var newCfg = cfg.withModelDir(resolvedDir.toString());
      return new ModelLoadRequest(
        request.modelId(),
        request.modelName(),
        request.modelPath(),
        request.memoryCheckPolicy(),
        null,
        null,
        null,
        null,
        null,
        newCfg,
        null,
        null,
        null,
        request.downloadExclude()
      );
    });
  }

  /**
   * Resolves a model directory: local → cached → download from HuggingFace. Fully reactive — the
   * Vert.x event loop is never blocked, so download progress logging ticks normally.
   */
  private Single<Path> resolveModelDir(
    String modelName,
    String modelDir,
    String variant,
    Path modelCacheDir,
    List<String> exclude
  ) {
    // 1. Already a local directory with the variant sub-folder
    if (modelDir != null && !modelDir.isBlank() && !modelDir.equals(".")) {
      Path local = Path.of(modelDir);
      if (Files.isDirectory(local) && Files.isDirectory(local.resolve(variant))) {
        LOGGER.info("GLiNER model is local: {}", local.toAbsolutePath());
        return Single.just(local.toAbsolutePath());
      }
    }

    // 2. Already cached
    if (Files.isDirectory(modelCacheDir) && Files.isDirectory(modelCacheDir.resolve(variant))) {
      LOGGER.info("GLiNER model already cached: {}", modelCacheDir.toAbsolutePath());
      return Single.just(modelCacheDir.toAbsolutePath());
    }

    // 3. Download from HuggingFace
    LOGGER.info("Downloading GLiNER model [{}] from HuggingFace (variant={})", modelName, variant);
    ensureCacheDir(modelCacheDir);

    return downloader
      .listRepoFileSizes(modelName)
      .flatMap(repoFileSizes -> {
        // Only the root files (tokenizer, gliner_config.json, ...) and the requested
        // ONNX variant sub-directory are needed; skip the other variants. The
        // workspace's download.exclude: globs narrow that further.
        String variantPrefix = variant + "/";
        Predicate<String> excluded = ExcludePatterns.excluder(exclude);
        List<String> filesToDownload = repoFileSizes
          .keySet()
          .stream()
          .filter(file -> !file.contains("/") || file.startsWith(variantPrefix))
          .filter(file -> !excluded.test(file))
          .toList();
        if (filesToDownload.isEmpty()) {
          return Single.<List<Path>>error(
            new IllegalStateException(
              "GLiNER model [" +
                modelName +
                "]: no files found for variant '" +
                variant +
                "' in the HuggingFace repository"
            )
          );
        }
        LOGGER.info(
          "Downloading {} file(s) for GLiNER model [{}]",
          filesToDownload.size(),
          modelName
        );
        return downloader.download(modelName, filesToDownload, modelCacheDir, repoFileSizes);
      })
      .map(paths -> {
        LOGGER.info("GLiNER model resolved to: {}", modelCacheDir.toAbsolutePath());
        return modelCacheDir.toAbsolutePath();
      });
  }

  private void ensureCacheDir(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create GLiNER cache directory: " + dir, e);
    }
  }
}
