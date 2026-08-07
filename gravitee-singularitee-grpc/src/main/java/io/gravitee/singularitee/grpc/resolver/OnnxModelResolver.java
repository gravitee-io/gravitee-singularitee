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
import io.gravitee.singularitee.workspace.config.OnnxClassifierConfig;
import io.gravitee.singularitee.workspace.config.OnnxEmbeddingConfig;
import io.gravitee.singularitee.workspace.config.OnnxRerankerConfig;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves ONNX model files (model, tokenizer, optional config) for the
 * classifier and embedding engines.
 *
 * <p>Resolution logic for each declared path:
 * <ol>
 *   <li>If the path points to an existing local file or directory, use it as-is.</li>
 *   <li>If the path was already downloaded in a previous run
 *       ({@code ~/.cache/gravitee-singularitee/models/{repo}/}), use the cached copy.</li>
 *   <li>Otherwise treat the path as a filename (or prefix) within the HuggingFace
 *       repository and download it via {@link HuggingFaceModelDownloader}.</li>
 * </ol>
 *
 * <p>For the tokenizer, an entire sub-directory of files is often needed
 * (e.g. {@code tokenizer.json}, {@code tokenizer_config.json},
 * {@code special_tokens_map.json}, {@code vocab.txt}).  When the declared
 * {@code tokenizer_path} is a bare directory name rather than a single file,
 * the resolver lists the repository and downloads every file whose path starts
 * with that prefix.
 *
 * <p>Returns a {@link Single} emitting a new {@link ModelLoadRequest} with all
 * paths replaced by their resolved absolute local paths, ready for the factory
 * to consume. All I/O is non-blocking.
 *
 * <p>Downloaded files are cached under
 * {@code ~/.cache/gravitee-singularitee/models/{modelName}/}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxModelResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnnxModelResolver.class);

  private static final Path DEFAULT_CACHE_DIR = Path.of(
    System.getProperty("user.home"),
    ".cache",
    "gravitee-singularitee",
    "models"
  );

  private final HuggingFaceModelDownloader downloader;
  private final Path cacheDir;

  public OnnxModelResolver(Vertx vertx) {
    this(new HuggingFaceModelDownloader(vertx), DEFAULT_CACHE_DIR);
  }

  public OnnxModelResolver(Vertx vertx, String hfToken) {
    this(new HuggingFaceModelDownloader(vertx, hfToken), DEFAULT_CACHE_DIR);
  }

  public OnnxModelResolver(HuggingFaceModelDownloader downloader, Path cacheDir) {
    this.downloader = downloader;
    this.cacheDir = cacheDir;
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Resolves all ONNX paths in the request and returns a new request with
   * the resolved absolute local paths substituted in.
   *
   * @param request the original publish request (must contain an ONNX config)
   * @return a {@link Single} emitting a new request with all paths resolved
   */
  public Single<ModelLoadRequest> resolve(ModelLoadRequest request) {
    String modelName = request.modelName();
    // mirror the HF handle on disk: <cacheRoot>/<org-id>/<model-id>
    Path modelCacheDir = cacheDir.resolve(modelName);

    ensureCacheDir(modelCacheDir);

    if (request.hasOnnxClassifier()) {
      return resolveClassifier(request, modelName, modelCacheDir);
    } else if (request.hasOnnxEmbedding()) {
      return resolveEmbedding(request, modelName, modelCacheDir);
    } else if (request.hasOnnxReranker()) {
      return resolveReranker(request, modelName, modelCacheDir);
    }

    return Single.just(request);
  }

  // ---------------------------------------------------------------------------
  // Classifier resolution
  // ---------------------------------------------------------------------------

  private Single<ModelLoadRequest> resolveClassifier(
    ModelLoadRequest request,
    String modelName,
    Path modelCacheDir
  ) {
    OnnxClassifierConfig cfg = request.onnxClassifier();
    List<String> exclude = request.downloadExclude();

    Single<Path> modelSingle = resolveFile(modelName, cfg.modelPath(), modelCacheDir, exclude);
    Single<Path> tokenizerSingle = resolveTokenizer(
      modelName,
      cfg.tokenizerPath(),
      modelCacheDir,
      exclude
    );
    Single<Path> configSingle = cfg.configJsonPath().isBlank()
      ? Single.just(Path.of(""))
      : resolveFile(modelName, cfg.configJsonPath(), modelCacheDir, exclude);

    return Single.zip(
      modelSingle,
      tokenizerSingle,
      configSingle,
      (resolvedModel, resolvedTokenizer, resolvedConfig) -> {
        var newCfg = cfg
          .withModelPath(resolvedModel.toString())
          .withTokenizerPath(resolvedTokenizer.toString());
        if (!resolvedConfig.toString().isEmpty()) newCfg = newCfg.withConfigJsonPath(
          resolvedConfig.toString()
        );
        return new ModelLoadRequest(
          request.modelId(),
          request.modelName(),
          request.modelPath(),
          request.memoryCheckPolicy(),
          null,
          null,
          newCfg,
          null,
          null,
          null,
          null,
          null,
          null,
          exclude
        );
      }
    );
  }

  // ---------------------------------------------------------------------------
  // Embedding resolution
  // ---------------------------------------------------------------------------

  private Single<ModelLoadRequest> resolveEmbedding(
    ModelLoadRequest request,
    String modelName,
    Path modelCacheDir
  ) {
    OnnxEmbeddingConfig cfg = request.onnxEmbedding();
    List<String> exclude = request.downloadExclude();

    Single<Path> modelSingle = resolveFile(modelName, cfg.modelPath(), modelCacheDir, exclude);
    Single<Path> tokenizerSingle = resolveTokenizer(
      modelName,
      cfg.tokenizerPath(),
      modelCacheDir,
      exclude
    );
    Single<Path> configSingle = cfg.configJsonPath().isBlank()
      ? Single.just(Path.of(""))
      : resolveFile(modelName, cfg.configJsonPath(), modelCacheDir, exclude);

    return Single.zip(
      modelSingle,
      tokenizerSingle,
      configSingle,
      (resolvedModel, resolvedTokenizer, resolvedConfig) -> {
        var newCfg = cfg
          .withModelPath(resolvedModel.toString())
          .withTokenizerPath(resolvedTokenizer.toString());
        if (!resolvedConfig.toString().isEmpty()) newCfg = newCfg.withConfigJsonPath(
          resolvedConfig.toString()
        );
        return new ModelLoadRequest(
          request.modelId(),
          request.modelName(),
          request.modelPath(),
          request.memoryCheckPolicy(),
          null,
          null,
          null,
          newCfg,
          null,
          null,
          null,
          null,
          null,
          exclude
        );
      }
    );
  }

  // ---------------------------------------------------------------------------
  // Reranker resolution
  // ---------------------------------------------------------------------------

  private Single<ModelLoadRequest> resolveReranker(
    ModelLoadRequest request,
    String modelName,
    Path modelCacheDir
  ) {
    OnnxRerankerConfig cfg = request.onnxReranker();
    List<String> exclude = request.downloadExclude();

    Single<Path> modelSingle = resolveFile(modelName, cfg.modelPath(), modelCacheDir, exclude);
    Single<Path> tokenizerSingle = resolveTokenizer(
      modelName,
      cfg.tokenizerPath(),
      modelCacheDir,
      exclude
    );
    Single<Path> configSingle = cfg.configJsonPath().isBlank()
      ? Single.just(Path.of(""))
      : resolveFile(modelName, cfg.configJsonPath(), modelCacheDir, exclude);

    return Single.zip(
      modelSingle,
      tokenizerSingle,
      configSingle,
      (resolvedModel, resolvedTokenizer, resolvedConfig) -> {
        var newCfg = cfg
          .withModelPath(resolvedModel.toString())
          .withTokenizerPath(resolvedTokenizer.toString());
        if (!resolvedConfig.toString().isEmpty()) newCfg = newCfg.withConfigJsonPath(
          resolvedConfig.toString()
        );
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
          null,
          newCfg,
          null,
          null,
          exclude
        );
      }
    );
  }

  // ---------------------------------------------------------------------------
  // Single-file resolution (model.onnx, config.json, …)
  // ---------------------------------------------------------------------------

  private Single<Path> resolveFile(
    String modelName,
    String filePath,
    Path modelCacheDir,
    List<String> exclude
  ) {
    if (filePath == null || filePath.isBlank()) {
      return Single.error(
        new IllegalArgumentException(
          "ONNX model '" + modelName + "': file path is blank (mandatory)"
        )
      );
    }

    // 1. Already a local absolute file
    Path local = Path.of(filePath);
    if (Files.exists(local)) {
      LOGGER.info("ONNX file is local: {}", local.toAbsolutePath());
      return Single.just(local.toAbsolutePath());
    }

    // 2. Already cached from a previous run
    Path cached = modelCacheDir.resolve(filePath);
    if (Files.exists(cached)) {
      LOGGER.info("ONNX file already cached: {}", cached.toAbsolutePath());
      return Single.just(cached.toAbsolutePath());
    }

    // 3. Download from HuggingFace — download all sibling files in the same directory
    LOGGER.info("Downloading ONNX file [{}] from repository [{}]", filePath, modelName);

    // Determine the directory prefix (e.g. "onnx/layer-22/" for "onnx/layer-22/model.onnx")
    int lastSlash = filePath.lastIndexOf('/');
    String dirPrefix = lastSlash >= 0 ? filePath.substring(0, lastSlash + 1) : "";

    Predicate<String> excluded = ExcludePatterns.excluder(exclude);

    return downloader
      .listRepoFiles(modelName)
      .flatMap(repoFiles -> {
        // Collect all files sharing the same directory prefix, minus anything
        // download.exclude: rules out. The requested file itself is never
        // excluded — a pattern that swept it up would turn a working model
        // definition into a failed load rather than a smaller download.
        List<String> filesToDownload = repoFiles
          .stream()
          .filter(f -> dirPrefix.isEmpty() ? f.equals(filePath) : f.startsWith(dirPrefix))
          .filter(f -> f.equals(filePath) || !excluded.test(f))
          .collect(Collectors.toList());

        if (filesToDownload.isEmpty()) {
          return Single.<List<Path>>error(
            new IllegalStateException(
              "ONNX file [" + filePath + "] not found in repository: " + modelName
            )
          );
        }

        LOGGER.info(
          "Downloading {} file(s) from [{}] (prefix: '{}')",
          filesToDownload.size(),
          modelName,
          dirPrefix.isEmpty() ? "(root)" : dirPrefix
        );
        return downloader.download(modelName, filesToDownload, modelCacheDir);
      })
      .map(_ -> {
        // Return the originally requested file, not the first downloaded sibling
        Path resolved = modelCacheDir.resolve(filePath);
        if (!Files.exists(resolved)) {
          throw new IllegalStateException(
            "ONNX file [" + filePath + "] not found after download in: " + modelCacheDir
          );
        }
        return resolved.toAbsolutePath();
      });
  }

  // ---------------------------------------------------------------------------
  // Tokenizer directory resolution
  // ---------------------------------------------------------------------------

  /**
   * Resolves the tokenizer path, which may be either:
   * <ul>
   *   <li>An absolute local directory that already exists → use as-is.</li>
   *   <li>A single tokenizer file (e.g. {@code tokenizer.json}) → download that file,
   *       return its parent directory.</li>
   *   <li>A directory prefix in the HF repo (e.g. {@code "tokenizer/"}) → download
   *       every file under that prefix, return the local directory.</li>
   *   <li>A bare {@code "."} or empty prefix meaning all tokenizer files are at the
   *       repo root → download all well-known tokenizer filenames.</li>
   * </ul>
   *
   * <p>The returned path is always a local directory containing all tokenizer files.
   */
  private Single<Path> resolveTokenizer(
    String modelName,
    String tokenizerPath,
    Path modelCacheDir,
    List<String> exclude
  ) {
    if (tokenizerPath == null || tokenizerPath.isBlank()) {
      return Single.error(
        new IllegalArgumentException(
          "ONNX model '" + modelName + "': tokenizer_path is blank (mandatory)"
        )
      );
    }

    // 1. Already a local directory
    Path local = Path.of(tokenizerPath);
    if (Files.isDirectory(local)) {
      LOGGER.info("Tokenizer is a local directory: {}", local.toAbsolutePath());
      return Single.just(local.toAbsolutePath());
    }

    // 2. Already a local file — return its parent directory
    if (Files.isRegularFile(local)) {
      LOGGER.info("Tokenizer is a local file: {} — using parent directory", local.toAbsolutePath());
      return Single.just(local.getParent().toAbsolutePath());
    }

    // 3. Check if already cached
    Path cachedDir = modelCacheDir.resolve(tokenizerPath);
    if (Files.isDirectory(cachedDir)) {
      LOGGER.info("Tokenizer already cached: {}", cachedDir.toAbsolutePath());
      return Single.just(cachedDir.toAbsolutePath());
    }

    // 4. Download from HuggingFace — list the repo and grab all tokenizer files
    LOGGER.info("Resolving tokenizer [{}] from repository [{}]", tokenizerPath, modelName);

    String normalizedPrefix = tokenizerPath.endsWith("/") ? tokenizerPath : tokenizerPath + "/";

    Predicate<String> excluded = ExcludePatterns.excluder(exclude);

    return downloader
      .listRepoFiles(modelName)
      .flatMap(repoFiles -> {
        // Collect files that match the tokenizer prefix or are well-known tokenizer
        // files, minus anything download.exclude: rules out — except tokenizerPath
        // itself, which was named explicitly and is always fetched.
        List<String> tokenizerFiles = repoFiles
          .stream()
          .filter(f -> f.startsWith(normalizedPrefix) || isWellKnownTokenizerFile(f, tokenizerPath))
          .filter(f -> f.equals(tokenizerPath) || !excluded.test(f))
          .collect(Collectors.toList());

        // If nothing matched the prefix, treat tokenizerPath itself as a single file
        if (tokenizerFiles.isEmpty()) {
          if (repoFiles.contains(tokenizerPath)) {
            tokenizerFiles = List.of(tokenizerPath);
          } else {
            return Single.<Path>error(
              new IllegalStateException(
                "Tokenizer [" + tokenizerPath + "] not found in repository: " + modelName
              )
            );
          }
        }

        LOGGER.info("Downloading {} tokenizer file(s) for [{}]", tokenizerFiles.size(), modelName);

        // Determine the local target directory
        Path tokenizerTargetDir = isWellKnownTokenizerFile(tokenizerFiles.getFirst(), tokenizerPath)
          ? modelCacheDir
          : modelCacheDir.resolve(tokenizerPath.replace("/", ""));

        ensureCacheDir(tokenizerTargetDir);

        // Strip the prefix from filenames so they land flat in the target directory
        List<String> flatNames = tokenizerFiles
          .stream()
          .map(f -> f.startsWith(normalizedPrefix) ? f.substring(normalizedPrefix.length()) : f)
          .filter(f -> !f.isBlank())
          .collect(Collectors.toList());

        return downloader
          .download(modelName, flatNames.isEmpty() ? tokenizerFiles : flatNames, tokenizerTargetDir)
          .map(_ -> {
            LOGGER.info("Tokenizer resolved to: {}", tokenizerTargetDir.toAbsolutePath());
            return tokenizerTargetDir.toAbsolutePath();
          });
      });
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static final List<String> WELL_KNOWN_TOKENIZER_FILES = List.of(
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "vocab.txt",
    "vocab.json",
    "merges.txt",
    "sentencepiece.bpe.model"
  );

  private static boolean isWellKnownTokenizerFile(String repoFile, String tokenizerPath) {
    return (
      WELL_KNOWN_TOKENIZER_FILES.contains(repoFile) &&
      (tokenizerPath.equals(".") ||
        tokenizerPath.isBlank() ||
        repoFile.equals(tokenizerPath) ||
        WELL_KNOWN_TOKENIZER_FILES.contains(tokenizerPath))
    );
  }

  private void ensureCacheDir(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create ONNX cache directory: " + dir, e);
    }
  }
}
