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

import static java.util.stream.Collectors.joining;

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
 *   <li>ONNX variant sub-directories ({@code onnx/}, {@code onnx_fp16/}, {@code onnx_quantized/}) — or,
 *       for a llama.cpp/ggml bundle ({@code "engine": "llamacpp"}), one {@code gguf/} directory whose
 *       {@code model.gguf} is the f16 default and {@code model-<variant>.gguf} a quantisation
 *       ({@code q8_0}, {@code q4_0}); the {@code variant} option then names the quantisation</li>
 *   <li>Tokenizer files ({@code tokenizer.json}, etc.) at the root</li>
 *   <li>{@code gliner4j_config.json} at the root (declares the family and the engine)</li>
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

  /** HuggingFace access with an optional token and the default cache directory. */
  public GlinerModelResolver(Vertx vertx, String hfToken) {
    this(new HuggingFaceModelDownloader(vertx, hfToken), DEFAULT_CACHE_DIR);
  }

  /** Full control over the downloader and cache directory (used by tests). */
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
        request.downloadExclude(),
        request.task(),
        request.visible(),
        request.modalities()
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
        request.downloadExclude(),
        request.task(),
        request.visible(),
        request.modalities()
      );
    });
  }

  /**
   * Resolves a model directory: local, then cached, then download from HuggingFace. Fully reactive: the
   * Vert.x event loop is never blocked, so download progress logging ticks normally.
   */
  private Single<Path> resolveModelDir(
    String modelName,
    String modelDir,
    String variant,
    Path modelCacheDir,
    List<String> exclude
  ) {
    // 1. Already a local directory with the variant sub-folder (or a gguf/ bundle)
    if (modelDir != null && !modelDir.isBlank() && !modelDir.equals(".")) {
      Path local = Path.of(modelDir);
      if (hasBundle(local, variant)) {
        LOGGER.info("GLiNER model is local: {}", local.toAbsolutePath());
        return Single.just(local.toAbsolutePath());
      }
    }

    // 2. Already cached with the weights this variant loads
    if (isCached(modelCacheDir, variant)) {
      LOGGER.info("GLiNER model already cached: {}", modelCacheDir.toAbsolutePath());
      return Single.just(modelCacheDir.toAbsolutePath());
    }

    // 3. Download from HuggingFace
    LOGGER.info("Downloading GLiNER model [{}] from HuggingFace (variant={})", modelName, variant);
    ensureCacheDir(modelCacheDir);

    return downloader
      .listRepoFileSizes(modelName)
      .flatMap(repoFileSizes -> {
        // Only the root files (tokenizer, gliner4j_config.json, ...) and the requested
        // ONNX variant sub-directory — or, for a ggml bundle, gguf/ minus the other
        // quantisations — are needed. The workspace's download.exclude: globs narrow
        // that further.
        List<String> filesToDownload = selectFiles(
          repoFileSizes.keySet(),
          variant,
          ExcludePatterns.excluder(exclude)
        );
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
        markComplete(modelCacheDir, variant, paths);
        LOGGER.info("GLiNER model resolved to: {}", modelCacheDir.toAbsolutePath());
        return modelCacheDir.toAbsolutePath();
      });
  }

  /** llama.cpp/ggml bundles keep every weight under {@code gguf/} whatever the variant. */
  private static final String GGUF_DIR = "gguf";

  /**
   * Whether a user-supplied {@code dir} holds a bundle: the ONNX variant folder, or a {@code gguf/}
   * folder with at least one {@code .gguf} file. Which weights a local bundle carries is the
   * user's choice, so the variant is not checked against them.
   */
  static boolean hasBundle(Path dir, String variant) {
    return (
      Files.isDirectory(dir) &&
      (Files.isDirectory(dir.resolve(variant)) || !ggufFiles(dir.resolve(GGUF_DIR)).isEmpty())
    );
  }

  /** Written into the cache directory once every file selected for a variant has been downloaded. */
  private static final String COMPLETION_MARKER_PREFIX = ".complete-";

  /**
   * Whether the cache holds a complete download for {@code variant}. Which files a bundle needs
   * cannot be told from the directory alone: a ggml bundle may carry per-quantisation weights
   * ({@code model.gguf}, {@code model-<variant>.gguf}) or several unrelated files (backbone,
   * scorer, heads), so a half-downloaded directory looks exactly like a complete one. Only the
   * marker {@link #markComplete} writes after a successful download counts; anything else sends the
   * resolver back to the repository listing, which re-fetches just the files that are missing or
   * the wrong size.
   */
  static boolean isCached(Path dir, String variant) {
    return Files.isRegularFile(completionMarker(dir, variant));
  }

  /** Records that every file selected for {@code variant} is on disk, listing them for diagnostics. */
  static void markComplete(Path dir, String variant, List<Path> files) {
    Path marker = completionMarker(dir, variant);
    try {
      Files.writeString(marker, files.stream().map(Path::toString).sorted().collect(joining("\n")));
    } catch (IOException | RuntimeException e) {
      LOGGER.warn(
        "Cannot write the GLiNER cache marker {}: the next start lists the repository again",
        marker,
        e
      );
    }
  }

  /** The marker path for {@code variant}, whose name is sanitised since it comes from the workspace. */
  private static Path completionMarker(Path dir, String variant) {
    return dir.resolve(COMPLETION_MARKER_PREFIX + variant.replaceAll("[^A-Za-z0-9_.-]", "_"));
  }

  /** The {@code .gguf} file names directly under {@code ggufDir}; empty when it is absent or unreadable. */
  private static List<String> ggufFiles(Path ggufDir) {
    if (!Files.isDirectory(ggufDir)) {
      return List.of();
    }
    try (var entries = Files.list(ggufDir)) {
      return entries
        .filter(Files::isRegularFile)
        .map(p -> p.getFileName().toString())
        .filter(name -> name.endsWith(".gguf"))
        .toList();
    } catch (IOException e) {
      LOGGER.warn("Cannot list GLiNER gguf directory {}", ggufDir, e);
      return List.of();
    }
  }

  /**
   * The repository files to fetch for {@code variant}: every root file, plus the ONNX
   * {@code <variant>/} folder — or, when the repository is a ggml bundle ({@code gguf/} present and
   * no such ONNX folder), the {@code gguf/} folder minus the quantisations that were not asked for:
   * {@code model-<q>.gguf} only when {@code q} equals the variant, and the f16 {@code model.gguf}
   * only when no {@code model-<variant>.gguf} exists (gliner4j falls back to it). Other files
   * under {@code gguf/} (heads, backbones, references) always come along.
   */
  static List<String> selectFiles(
    java.util.Collection<String> repoFiles,
    String variant,
    Predicate<String> excluded
  ) {
    String variantPrefix = variant + "/";
    String ggufPrefix = GGUF_DIR + "/";
    boolean onnx = repoFiles.stream().anyMatch(f -> f.startsWith(variantPrefix));
    boolean ggml = !onnx && repoFiles.stream().anyMatch(f -> f.startsWith(ggufPrefix));
    String wanted = ggufPrefix + "model-" + variant + ".gguf";
    boolean hasWanted = repoFiles.contains(wanted);
    Predicate<String> keep = file -> {
      if (!file.contains("/")) return true;
      if (!ggml) return file.startsWith(variantPrefix);
      if (!file.startsWith(ggufPrefix)) return false;
      String name = file.substring(ggufPrefix.length());
      if (name.equals("model.gguf")) return !hasWanted;
      if (name.startsWith("model-") && name.endsWith(".gguf")) return file.equals(wanted);
      return true;
    };
    return repoFiles
      .stream()
      .filter(keep)
      .filter(f -> !excluded.test(f))
      .toList();
  }

  private void ensureCacheDir(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create GLiNER cache directory: " + dir, e);
    }
  }
}
