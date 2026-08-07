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

import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a vLLM model to a local directory, downloading it from HuggingFace
 * when needed.
 *
 * <p>vLLM can fetch weights itself, but then the download happens inside the
 * embedded CPython interpreter: it does not share the cache the other engines
 * use, it does not report progress through our logs, and it makes the model
 * cache depend on the Python environment. Every other backend — llama.cpp, ONNX,
 * GLiNER — resolves its files in Java through {@link HuggingFaceModelDownloader}
 * first and hands the engine a path. This does the same for vLLM, so all four
 * share one cache layout and one download path.
 *
 * <p>The engine is then pointed at the resulting directory, which also means it
 * loads offline: nothing is fetched once the cache is warm.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmModelResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(VllmModelResolver.class);

  private static final Path DEFAULT_CACHE_DIR = Path.of(
    System.getProperty("user.home"),
    ".cache",
    "gravitee-singularitee",
    "models"
  );

  /**
   * Metadata vLLM reads: the model config, the tokenizer in any of its forms,
   * and chat/generation templates. Small, and all of it is needed.
   */
  private static final Set<String> METADATA_SUFFIXES = Set.of(".json", ".txt", ".model", ".jinja");

  /** Weight formats, most preferred first. */
  private static final String SAFETENSORS = ".safetensors";

  private static final String PYTORCH_BIN = ".bin";

  /**
   * Never useful to vLLM and sometimes very large: other runtimes' copies of the
   * same weights, and repository furniture.
   */
  private static final Set<String> EXCLUDED_SUFFIXES = Set.of(
    ".gguf",
    ".onnx",
    ".onnx_data",
    ".pth",
    ".pt",
    ".h5",
    ".msgpack",
    ".tflite",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".svg",
    ".md"
  );

  private final HuggingFaceModelDownloader downloader;
  private final Path cacheDir;

  public VllmModelResolver(Vertx vertx, String hfToken) {
    this(new HuggingFaceModelDownloader(vertx, hfToken), DEFAULT_CACHE_DIR);
  }

  public VllmModelResolver(HuggingFaceModelDownloader downloader, Path cacheDir) {
    this.downloader = downloader;
    this.cacheDir = cacheDir;
  }

  /** Resolves {@code modelName} with no download excludes. */
  public Single<Path> resolve(String modelName) {
    return resolve(modelName, List.of());
  }

  /**
   * Resolves {@code modelName} to a local directory holding the model.
   *
   * @param modelName a HuggingFace repo id, or an absolute path to a directory
   *                  that already holds one
   * @param exclude   {@code download.exclude:} globs; files matching any of them
   *                  are left in the repository. Narrows {@link #selectFiles},
   *                  never widens it.
   * @return the local directory to hand to vLLM
   */
  public Single<Path> resolve(String modelName, List<String> exclude) {
    if (modelName == null || modelName.isBlank()) {
      return Single.error(new IllegalArgumentException("vLLM model name must not be blank"));
    }

    // Already a local model directory — nothing to fetch.
    Path asPath = Path.of(modelName);
    if (Files.isDirectory(asPath)) {
      LOGGER.info("vLLM model is local: {}", asPath.toAbsolutePath());
      return Single.just(asPath.toAbsolutePath());
    }

    // Mirror the HF handle on disk: <cacheRoot>/<org>/<model>
    Path modelCacheDir = cacheDir.resolve(modelName);
    if (isComplete(modelCacheDir)) {
      LOGGER.info("vLLM model already cached: {}", modelCacheDir.toAbsolutePath());
      return Single.just(modelCacheDir.toAbsolutePath());
    }

    LOGGER.info("Downloading vLLM model [{}] from HuggingFace", modelName);
    ensureCacheDir(modelCacheDir);

    return downloader
      .listRepoFileSizes(modelName)
      .flatMap(repoFileSizes -> {
        List<String> files = selectFiles(repoFileSizes.keySet(), exclude);
        if (files.isEmpty()) {
          return Single.<List<Path>>error(
            new IllegalStateException(
              "vLLM model [" +
                modelName +
                "]: the HuggingFace repository contains no loadable weights " +
                "(.safetensors or .bin)" +
                (exclude == null || exclude.isEmpty()
                    ? ""
                    : " once download.exclude " + exclude + " is applied")
            )
          );
        }
        long bytes = files
          .stream()
          .mapToLong(f -> repoFileSizes.getOrDefault(f, 0L))
          .sum();
        LOGGER.info(
          "Downloading {} file(s), {} for vLLM model [{}]",
          files.size(),
          humanReadable(bytes),
          modelName
        );
        return downloader.download(modelName, files, modelCacheDir, repoFileSizes);
      })
      .map(paths -> {
        LOGGER.info("vLLM model resolved to: {}", modelCacheDir.toAbsolutePath());
        return modelCacheDir.toAbsolutePath();
      });
  }

  /**
   * Picks the files vLLM needs.
   *
   * <p>Repositories routinely ship the same weights several times over — a GGUF
   * for llama.cpp, an ONNX export, a legacy PyTorch {@code .bin} beside the
   * safetensors. Downloading all of it would multiply the transfer for no
   * benefit, so this takes the metadata plus exactly one weight format,
   * preferring safetensors and falling back to {@code .bin} only when the repo
   * has no safetensors at all.
   *
   * <p>The workspace's {@code download.exclude:} globs are applied on top, and
   * deliberately <em>before</em> the weight format is chosen: excluding a repo's
   * safetensors then falls back to its {@code .bin} weights rather than selecting
   * a format that has just been excluded away to nothing.
   */
  static List<String> selectFiles(Set<String> repoFiles) {
    return selectFiles(repoFiles, List.of());
  }

  static List<String> selectFiles(Set<String> repoFiles, List<String> exclude) {
    Predicate<String> excluded = ExcludePatterns.excluder(exclude);

    List<String> candidates = repoFiles
      .stream()
      .filter(file -> !hasAnySuffix(file, EXCLUDED_SUFFIXES))
      .filter(file -> !excluded.test(file))
      .toList();

    boolean hasSafetensors = candidates.stream().anyMatch(f -> hasSuffix(f, SAFETENSORS));
    String weightSuffix = hasSafetensors ? SAFETENSORS : PYTORCH_BIN;

    return candidates
      .stream()
      .filter(file -> hasAnySuffix(file, METADATA_SUFFIXES) || hasSuffix(file, weightSuffix))
      .sorted()
      .toList();
  }

  /**
   * A cache entry counts as complete only when {@code config.json} and at least
   * one weight file are present.
   *
   * <p>An interrupted download leaves a partial directory behind; treating that
   * as a hit would surface much later as an opaque vLLM load error instead of
   * simply fetching the rest.
   */
  private static boolean isComplete(Path dir) {
    if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("config.json"))) {
      return false;
    }
    try (var entries = Files.list(dir)) {
      return entries.anyMatch(p -> {
        String name = p.getFileName().toString();
        return hasSuffix(name, SAFETENSORS) || hasSuffix(name, PYTORCH_BIN);
      });
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean hasSuffix(String file, String suffix) {
    return file.toLowerCase(Locale.ENGLISH).endsWith(suffix);
  }

  private static boolean hasAnySuffix(String file, Set<String> suffixes) {
    String lower = file.toLowerCase(Locale.ENGLISH);
    return suffixes.stream().anyMatch(lower::endsWith);
  }

  private static String humanReadable(long bytes) {
    if (bytes >= 1024L * 1024 * 1024) {
      return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024));
    }
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
  }

  private void ensureCacheDir(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException(
        "Could not create model cache directory: " + dir + " (" + e + ")",
        e
      );
    }
  }

  /** Exposed for the engine factory, which needs the same cache layout. */
  public Path cacheDir() {
    return cacheDir;
  }
}
