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

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.client.HttpRequest;
import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.codec.BodyCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal HuggingFace Hub downloader tailored for Singularitee's needs.
 *
 * <p>Given a repository name and a list of filenames, this class:
 * <ol>
 *   <li>Calls the HF API once to list all files in the repo with their blob sizes</li>
 *   <li>Validates that every requested file actually exists</li>
 *   <li>Skips files that are already present locally with the expected size
 *       (a size mismatch — e.g. a half-written file from a crashed boot — is re-downloaded)</li>
 *   <li>Downloads the remaining files with streaming (pipe to disk)</li>
 * </ol>
 *
 * <p>File sizes come from the {@code ?blobs=true} repo listing, so no per-file HEAD
 * request is needed: progress totals are exact and each file costs a single GET.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class HuggingFaceModelDownloader {

  private static final Logger LOG = LoggerFactory.getLogger(HuggingFaceModelDownloader.class);

  private static final String HF_HOST = "huggingface.co";
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int IDLE_TIMEOUT_S = 300;

  /** Sentinel for "size unknown" (listing did not report one). */
  private static final long UNKNOWN_SIZE = -1L;

  private final Vertx vertx;
  private final WebClient client;
  private final String hfToken;

  public HuggingFaceModelDownloader(Vertx vertx) {
    this(vertx, (String) null);
  }

  public HuggingFaceModelDownloader(Vertx vertx, String hfToken) {
    this.vertx = vertx;
    this.client = createClient(vertx);
    this.hfToken = (hfToken != null && !hfToken.isBlank()) ? hfToken : null;
  }

  /**
   * Downloads the requested files from a HuggingFace repository into the
   * given directory.
   *
   * <p>Lists the repo once (with blob sizes), validates that every requested
   * filename exists, skips already-cached files whose size matches, and
   * downloads the rest.
   *
   * @param repository the HuggingFace repository (e.g. "Qwen/Qwen3-0.6B-GGUF")
   * @param files      the filenames to download (e.g. ["Qwen3-0.6B-Q8_0.gguf"])
   * @param targetDir  local directory to download into (created if needed)
   * @return the list of local absolute paths for all requested files
   */
  public Single<List<Path>> download(String repository, List<String> files, Path targetDir) {
    return listRepoFileSizes(repository).flatMap(sizes ->
      download(repository, files, targetDir, sizes)
    );
  }

  /**
   * Same as {@link #download(String, List, Path)} but reuses an already-fetched
   * {@link #listRepoFileSizes(String)} result, avoiding a second repo-listing call.
   */
  public Single<List<Path>> download(
    String repository,
    List<String> files,
    Path targetDir,
    Map<String, Long> repoFileSizes
  ) {
    for (String file : files) {
      if (!repoFileSizes.containsKey(file)) {
        return Single.error(
          new IllegalArgumentException(
            "File [" + file + "] not found in repository [" + repository + "]"
          )
        );
      }
    }
    return Flowable.fromIterable(files)
      // sequential: each file finishes before the next starts, so progress reads as one clean stream
      .concatMapSingle(file ->
        downloadIfAbsent(
          repository,
          file,
          targetDir,
          repoFileSizes.getOrDefault(file, UNKNOWN_SIZE)
        )
      )
      .toList();
  }

  /**
   * Lists all filenames in a HuggingFace repository.
   */
  public Single<Set<String>> listRepoFiles(String repository) {
    return listRepoFileSizes(repository).map(Map::keySet);
  }

  /**
   * Lists all files in a HuggingFace repository with their blob sizes in bytes
   * ({@code -1} when the listing does not report one).
   */
  public Single<Map<String, Long>> listRepoFileSizes(String repository) {
    return authorize(client.request(HttpMethod.GET, "/api/models/" + repository))
      .addQueryParam("blobs", "true")
      .putHeader("Accept", "application/json")
      .rxSend()
      .map(response -> {
        if (response.statusCode() >= 400) {
          throw new IllegalStateException(
            "HuggingFace API error for [" +
              repository +
              "]: HTTP " +
              response.statusCode() +
              " " +
              response.statusMessage()
          );
        }
        var body = response.body().toJsonObject();
        var siblings = body.getJsonArray("siblings", new io.vertx.core.json.JsonArray());
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < siblings.size(); i++) {
          JsonObject sibling = siblings.getJsonObject(i);
          String name = sibling.getString("rfilename");
          if (name != null) {
            result.put(name, siblingSize(sibling));
          }
        }
        LOG.info("Repository [{}] contains {} files", repository, result.size());
        return result;
      });
  }

  // ------------------------------------------------------------------
  // Internal
  // ------------------------------------------------------------------

  /**
   * Resolves a repository-supplied file name under {@code targetDir}, refusing anything that
   * would escape it.
   *
   * <p>{@code rfilename} comes from the HuggingFace API response, i.e. from whoever controls
   * the repository. A name like {@code ../../.ssh/authorized_keys} would otherwise be written
   * outside the model cache with the privileges of the server process.
   */
  static Path resolveWithinTarget(Path targetDir, String fileName) {
    Path base = targetDir.toAbsolutePath().normalize();
    Path resolved = base.resolve(fileName).normalize();
    if (!resolved.startsWith(base)) {
      throw new IllegalArgumentException(
        "Refusing model file '" + fileName + "': resolves outside the model directory"
      );
    }
    return resolved;
  }

  /** Blob size from a {@code ?blobs=true} sibling entry: LFS pointer size wins over raw size. */
  private static long siblingSize(JsonObject sibling) {
    JsonObject lfs = sibling.getJsonObject("lfs");
    if (lfs != null) {
      Long size = lfs.getLong("size");
      if (size != null) return size;
    }
    Long size = sibling.getLong("size");
    return size != null ? size : UNKNOWN_SIZE;
  }

  private Single<Path> downloadIfAbsent(
    String repository,
    String fileName,
    Path targetDir,
    long expectedSize
  ) {
    Path outputPath = resolveWithinTarget(targetDir, fileName);

    return vertx
      .fileSystem()
      .rxExists(outputPath.toString())
      .flatMap(exists -> {
        if (!exists) {
          return doDownload(repository, fileName, outputPath, expectedSize);
        }
        if (expectedSize <= 0) {
          LOG.info("Already cached: {}", outputPath);
          return Single.just(outputPath.toAbsolutePath());
        }
        return vertx
          .fileSystem()
          .rxProps(outputPath.toString())
          .flatMap(props -> {
            if (props.size() == expectedSize) {
              LOG.info("Already cached: {}", outputPath);
              return Single.just(outputPath.toAbsolutePath());
            }
            LOG.warn(
              "Cached file {} has size {} but repository reports {}; re-downloading",
              outputPath,
              props.size(),
              expectedSize
            );
            return doDownload(repository, fileName, outputPath, expectedSize);
          });
      });
  }

  private Single<Path> doDownload(
    String repository,
    String fileName,
    Path outputPath,
    long totalSize
  ) {
    LOG.info("Downloading [{}/{}] ...", repository, fileName);
    String resolvePath = "/" + repository + "/resolve/main/" + fileName;

    return ensureParentDir(outputPath)
      .andThen(
        vertx
          .fileSystem()
          .rxOpen(
            outputPath.toString(),
            new OpenOptions().setCreate(true).setWrite(true).setTruncateExisting(true)
          )
      )
      .flatMap(asyncFile -> {
        long timerId = startProgress(repository + "/" + fileName, outputPath, totalSize);
        return authorize(client.request(HttpMethod.GET, resolvePath))
          .addQueryParam("download", "true")
          .followRedirects(true)
          .as(BodyCodec.pipe(asyncFile))
          .rxSend()
          .doFinally(() -> {
            vertx.cancelTimer(timerId);
            asyncFile.close();
          })
          .flatMap(response -> {
            if (response.statusCode() >= 400) {
              return vertx
                .fileSystem()
                .rxDelete(outputPath.toString())
                .andThen(
                  Single.<Path>error(
                    new IllegalStateException(
                      "Download failed for [" +
                        fileName +
                        "] from [" +
                        repository +
                        "]: HTTP " +
                        response.statusCode()
                    )
                  )
                );
            }
            LOG.info("Downloaded [{}/{}] -> {}", repository, fileName, outputPath);
            return Single.just(outputPath.toAbsolutePath());
          });
      });
  }

  /**
   * Logs a per-file download progress bar once a second via a Vert.x periodic timer, reading the
   * partial file size on disk. Falls back to a plain byte counter when the total is unknown.
   *
   * @return the periodic timer id (cancel it when the download settles)
   */
  private long startProgress(String label, Path file, long total) {
    return vertx.setPeriodic(1_000, id ->
      vertx
        .fileSystem()
        .rxProps(file.toString())
        .subscribe(
          props -> {
            long size = props.size();
            if (total > 0) {
              int pct = (int) Math.min(100, (size * 100) / total);
              LOG.info("  {} {} {}% ({} / {} MiB)", label, bar(pct), pct, mib(size), mib(total));
            } else {
              LOG.info("  {} … {} MiB", label, mib(size));
            }
          },
          err -> {} // file may not be flushed to disk yet; ignore this tick
        )
    );
  }

  private static final int BAR_WIDTH = 24;

  /** Renders a {@code [████████░░░░░░░░]} bar for the given percentage. */
  private static String bar(int pct) {
    int filled = (pct * BAR_WIDTH) / 100;
    StringBuilder sb = new StringBuilder(BAR_WIDTH + 2).append('[');
    for (int i = 0; i < BAR_WIDTH; i++) {
      sb.append(i < filled ? '█' : '░');
    }
    return sb.append(']').toString();
  }

  private static long mib(long bytes) {
    return bytes / (1024 * 1024);
  }

  private Completable ensureParentDir(Path file) {
    Path parent = file.getParent();
    if (parent == null || Files.exists(parent)) return Completable.complete();
    return vertx.fileSystem().mkdirs(parent.toString());
  }

  private <T> HttpRequest<T> authorize(HttpRequest<T> request) {
    if (hfToken != null) {
      request.putHeader("Authorization", "Bearer " + hfToken);
    }
    return request;
  }

  private static WebClient createClient(Vertx vertx) {
    var options = new WebClientOptions()
      .setName("gio-singularitee-hf-downloader")
      .setDefaultHost(HF_HOST)
      .setDefaultPort(443)
      .setSsl(true)
      .setConnectTimeout(CONNECT_TIMEOUT_MS)
      .setIdleTimeout(IDLE_TIMEOUT_S)
      .setIdleTimeoutUnit(TimeUnit.SECONDS);

    return WebClient.create(vertx, options);
  }
}
