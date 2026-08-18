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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.file.AsyncFile;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientResponse;
import io.vertx.rxjava3.ext.web.client.HttpRequest;
import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.codec.BodyCodec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 *   <li>Downloads the remaining files, using chunked parallel HTTP Range requests
 *       (hf_transfer-style) for large blobs and a plain streaming GET otherwise</li>
 * </ol>
 *
 * <p>File sizes come from the {@code ?blobs=true} repo listing, so no per-file HEAD
 * request is needed: progress totals are exact and each file costs a single GET.
 *
 * <p>Large files (≥ {@link Options#chunkedThresholdBytes()}) are fetched the way
 * hf_transfer does it: the {@code resolve} redirect is followed once by hand to the
 * signed CDN URL, then {@link Options#parallelism()} concurrent Range requests pull
 * {@link Options#chunkSizeBytes()}-sized chunks that are written at their offset into a
 * preallocated file. Peak buffered memory is {@code parallelism × chunkSize}. Servers
 * that ignore {@code Range} — and any chunked attempt that exhausts its retries — fall
 * back to the single-stream path.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class HuggingFaceModelDownloader implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(HuggingFaceModelDownloader.class);

  private static final String HF_HOST = "huggingface.co";
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int IDLE_TIMEOUT_S = 300;
  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_CHUNK_RETRIES = 5;
  private static final int MAX_FILE_RETRIES = 2;
  private static final long RETRY_BASE_DELAY_MS = 500;
  private static final long RETRY_MAX_DELAY_MS = 10_000;

  /** Sentinel for "size unknown" (listing did not report one). */
  private static final long UNKNOWN_SIZE = -1L;

  /**
   * Tuning knobs for the chunked download path.
   *
   * <p>Peak buffered memory is {@code parallelism × chunkSizeBytes} (80 MiB with the
   * defaults). Files smaller than {@code chunkedThresholdBytes} use the single-stream
   * path.
   */
  public record Options(long chunkSizeBytes, int parallelism, long chunkedThresholdBytes) {
    private static final long DEFAULT_CHUNK_SIZE = 10L * 1024 * 1024;

    public Options {
      if (chunkSizeBytes <= 0) throw new IllegalArgumentException("chunkSizeBytes must be > 0");
      if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be > 0");
      if (chunkedThresholdBytes <= 0) {
        throw new IllegalArgumentException("chunkedThresholdBytes must be > 0");
      }
    }

    public static Options defaults() {
      return new Options(DEFAULT_CHUNK_SIZE, 8, 2 * DEFAULT_CHUNK_SIZE);
    }
  }

  /** Where the {@code resolve} redirect chain ends, and whether Range requests work there. */
  private record ResolvedSource(String url, boolean rangeSupported, long totalSize) {}

  /** Marker for failures that must not be retried at any level. */
  private static class NonRetryableException extends RuntimeException {

    NonRetryableException(String message) {
      super(message);
    }
  }

  /**
   * Expired signed CDN URL (HTTP 403 on a chunk). Pointless to retry at chunk level —
   * the URL stays expired — but retryable at file level, where a fresh attempt
   * re-resolves the redirect to a new signed URL.
   */
  private static final class UrlExpiredException extends NonRetryableException {

    UrlExpiredException(String message) {
      super(message);
    }
  }

  private final Vertx vertx;
  private final WebClient client;
  private final WebClient absClient;
  private final HttpClient probeClient;
  private final String hfToken;
  private final Options options;
  private final String hubHost;
  private final int hubPort;
  private final boolean ssl;

  public HuggingFaceModelDownloader(Vertx vertx) {
    this(vertx, null, Options.defaults());
  }

  public HuggingFaceModelDownloader(Vertx vertx, String hfToken) {
    this(vertx, hfToken, Options.defaults());
  }

  public HuggingFaceModelDownloader(Vertx vertx, String hfToken, Options options) {
    this(vertx, hfToken, options, HF_HOST, 443, true);
  }

  /** Test hook: points the downloader at a local stub hub instead of huggingface.co. */
  HuggingFaceModelDownloader(
    Vertx vertx,
    String hfToken,
    Options options,
    String hubHost,
    int hubPort,
    boolean ssl
  ) {
    this.vertx = vertx;
    this.options = options;
    this.hubHost = hubHost;
    this.hubPort = hubPort;
    this.ssl = ssl;
    this.client = createClient(vertx, hubHost, hubPort, ssl);
    this.absClient = createAbsClient(vertx, options.parallelism());
    this.probeClient = createProbeClient(vertx);
    this.hfToken = (hfToken != null && !hfToken.isBlank()) ? hfToken : null;
  }

  /** Releases all HTTP clients (and their connection pools). */
  @Override
  public void close() {
    client.close();
    absClient.close();
    probeClient.close().onErrorComplete().subscribe();
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
        var siblings = body.getJsonArray("siblings", new JsonArray());
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
    cleanStaleParts(outputPath);
    if (totalSize < options.chunkedThresholdBytes()) {
      return doStreamDownload(repository, fileName, outputPath, totalSize);
    }
    return Single.defer(() ->
      resolveDownloadUrl(repository, fileName).flatMap(source -> {
        if (!source.rangeSupported() || source.totalSize() <= 0) {
          return Single.<Path>error(
            new NonRetryableException("Range requests unsupported for [" + fileName + "]")
          );
        }
        return doChunkedDownload(repository + "/" + fileName, source, outputPath);
      })
    )
      // a fresh attempt re-resolves the redirect, so an expired signed URL heals here
      .retryWhen(errors ->
        backoff(
          errors,
          MAX_FILE_RETRIES,
          err -> err instanceof NonRetryableException && !(err instanceof UrlExpiredException)
        )
      )
      .onErrorResumeNext(err -> {
        LOG.warn(
          "Chunked download of [{}/{}] unavailable ({}); falling back to single stream",
          repository,
          fileName,
          err.getMessage()
        );
        return doStreamDownload(repository, fileName, outputPath, totalSize);
      });
  }

  // ------------------------------------------------------------------
  // Chunked (hf_transfer-style) path
  // ------------------------------------------------------------------

  /**
   * Follows the {@code /resolve/...} redirect chain by hand (Vert.x's automatic redirect
   * handling would replay the {@code Authorization} header to the CDN, which signed S3-style
   * URLs reject) and probes Range support with a 1-byte request.
   */
  private Single<ResolvedSource> resolveDownloadUrl(String repository, String fileName) {
    String url =
      (ssl ? "https://" : "http://") +
      hubHost +
      ":" +
      hubPort +
      "/" +
      repository +
      "/resolve/main/" +
      fileName +
      "?download=true";
    return probe(url, 0);
  }

  /**
   * The probe only needs the status line and headers: the request is made on a raw
   * {@link HttpClient} (no body aggregation) and the connection is torn down as soon as the
   * status is known, so a server that ignores {@code Range} and answers 200 with the whole
   * blob never gets to transfer — or buffer — that body here.
   */
  private Single<ResolvedSource> probe(String url, int hops) {
    if (hops > MAX_REDIRECTS) {
      return Single.error(new NonRetryableException("Too many redirects resolving [" + url + "]"));
    }
    return probeClient
      .rxRequest(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI(url))
      .flatMap(request -> {
        request.putHeader("Range", "bytes=0-0");
        if (hfToken != null && isHubUrl(url)) {
          request.putHeader("Authorization", "Bearer " + hfToken);
        }
        return request.rxSend();
      })
      .flatMap(response -> {
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
          String location = response.getHeader("Location");
          abortProbe(response);
          if (location == null) {
            return Single.error(
              new IllegalStateException("Redirect without Location resolving [" + url + "]")
            );
          }
          return probe(URI.create(url).resolve(location).toString(), hops + 1);
        }
        if (status == 206) {
          long total = contentRangeTotal(response.getHeader("Content-Range"));
          abortProbe(response);
          return Single.just(new ResolvedSource(url, true, total));
        }
        if (status == 200) {
          abortProbe(response);
          return Single.just(new ResolvedSource(url, false, UNKNOWN_SIZE));
        }
        abortProbe(response);
        return Single.error(
          new IllegalStateException("Probe of [" + url + "] failed: HTTP " + status)
        );
      });
  }

  /** Closes the probe connection before the body transfers. */
  private static void abortProbe(HttpClientResponse response) {
    response.request().connection().close().onErrorComplete().subscribe();
  }

  /** Parses the total from a {@code Content-Range: bytes 0-0/12345} header. */
  private static long contentRangeTotal(String contentRange) {
    if (contentRange == null) return UNKNOWN_SIZE;
    int slash = contentRange.lastIndexOf('/');
    if (slash < 0) return UNKNOWN_SIZE;
    try {
      return Long.parseLong(contentRange.substring(slash + 1).trim());
    } catch (NumberFormatException e) {
      return UNKNOWN_SIZE;
    }
  }

  private boolean isHubUrl(String url) {
    URI uri = URI.create(url);
    int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    return hubHost.equalsIgnoreCase(uri.getHost()) && port == hubPort;
  }

  private Single<Path> doChunkedDownload(String label, ResolvedSource source, Path outputPath) {
    long total = source.totalSize();
    long chunkSize = options.chunkSizeBytes();
    int chunkCount = (int) ((total + chunkSize - 1) / chunkSize);
    LOG.info(
      "Downloading [{}] in {} chunks of {} MiB ({} parallel) ...",
      label,
      chunkCount,
      mib(chunkSize),
      options.parallelism()
    );

    // written to a temp sibling and published atomically, so the final path never
    // exists in a preallocated/partial state
    Path tempPath = tempPathFor(outputPath);
    return ensureParentDir(outputPath)
      .andThen(
        vertx
          .fileSystem()
          .rxOpen(
            tempPath.toString(),
            new OpenOptions().setCreate(true).setWrite(true).setTruncateExisting(true)
          )
      )
      .flatMap(asyncFile -> {
        AtomicLong written = new AtomicLong();
        long timerId = startProgress(label, written::get, total);
        // preallocate so every chunk writes within the file bounds
        return asyncFile
          .rxWrite(Buffer.buffer(new byte[] { 0 }), total - 1)
          .andThen(
            Flowable.range(0, chunkCount).flatMapCompletable(
              index -> {
                long start = index * chunkSize;
                long end = Math.min(total, start + chunkSize) - 1;
                // detached both inside and around retryWhen: a late error can surface on
                // either side of the resubscription boundary after fail-fast disposal
                return detachOnDispose(
                  detachOnDispose(
                    downloadChunk(source.url(), start, end, asyncFile, written)
                  ).retryWhen(errors -> backoff(errors, MAX_CHUNK_RETRIES))
                );
              },
              false,
              options.parallelism()
            )
          )
          .doFinally(() -> vertx.cancelTimer(timerId))
          // close before verifying, and close (best-effort) on the error path too
          .andThen(Completable.defer(asyncFile::rxClose))
          .onErrorResumeNext(err ->
            asyncFile.rxClose().onErrorComplete().andThen(Completable.error(err))
          )
          .andThen(
            Single.defer(() -> {
              // The file is preallocated to `total`, so its on-disk size proves nothing;
              // the byte counter is the real integrity signal.
              long got = written.get();
              if (got != total) {
                return Single.<Path>error(
                  new IllegalStateException(
                    "Downloaded [" + label + "] wrote " + got + " bytes, expected " + total
                  )
                );
              }
              return publish(tempPath, outputPath).andThen(
                Single.fromCallable(() -> {
                  LOG.info("Downloaded [{}] -> {}", label, outputPath);
                  return outputPath.toAbsolutePath();
                })
              );
            })
          );
      })
      .onErrorResumeNext(err ->
        vertx
          .fileSystem()
          .rxDelete(tempPath.toString())
          .onErrorComplete()
          .andThen(Single.<Path>error(err))
      );
  }

  /**
   * Wraps a chunk chain so that errors arriving after fail-fast disposal (a sibling chunk
   * failed permanently and cancelled this one mid-flight) are dropped instead of reaching
   * {@code RxJavaPlugins.onError} as uncaught exceptions.
   */
  private static Completable detachOnDispose(Completable chain) {
    return Completable.create(emitter -> {
      var disposable = chain.subscribe(emitter::onComplete, emitter::tryOnError);
      emitter.setCancellable(disposable::dispose);
    });
  }

  private Completable downloadChunk(
    String url,
    long start,
    long end,
    AsyncFile file,
    AtomicLong written
  ) {
    HttpRequest<Buffer> request = absClient
      .requestAbs(HttpMethod.GET, url)
      .putHeader("Range", "bytes=" + start + "-" + end)
      .followRedirects(false);
    if (isHubUrl(url)) {
      authorize(request);
    }
    long expectedLength = end - start + 1;
    return request
      .rxSend()
      .flatMapCompletable(response -> {
        if (response.statusCode() == 403) {
          // signed CDN URL expired: retrying this chunk is pointless, re-resolve the file
          return Completable.error(
            new UrlExpiredException("HTTP 403 for chunk " + start + "-" + end + " (URL expired?)")
          );
        }
        if (response.statusCode() != 206) {
          return Completable.error(
            new IllegalStateException(
              "Chunk " + start + "-" + end + " failed: HTTP " + response.statusCode()
            )
          );
        }
        var body = response.body();
        if (body == null || body.length() != expectedLength) {
          return Completable.error(
            new IllegalStateException(
              "Chunk " +
                start +
                "-" +
                end +
                " returned " +
                (body == null ? 0 : body.length()) +
                " bytes, expected " +
                expectedLength
            )
          );
        }
        return file.rxWrite(body, start).doOnComplete(() -> written.addAndGet(expectedLength));
      });
  }

  /**
   * Exponential backoff for {@code retryWhen}: 500 ms base, doubling, capped at 10 s.
   * {@link NonRetryableException}s propagate immediately.
   */
  private static Flowable<?> backoff(Flowable<Throwable> errors, int maxRetries) {
    return backoff(errors, maxRetries, NonRetryableException.class::isInstance);
  }

  /**
   * Backoff with a caller-supplied non-retryable predicate, for levels that treat
   * some marker exceptions differently (see {@link UrlExpiredException}).
   *
   * <p>The attempt index is tracked explicitly (not via {@code zipWith(range)}) so
   * exhausting the retries always rethrows the error — a completed inner stream
   * would otherwise terminate the retried operation as a silent success.
   */
  private static Flowable<?> backoff(
    Flowable<Throwable> errors,
    int maxRetries,
    Predicate<Throwable> nonRetryable
  ) {
    AtomicInteger attempt = new AtomicInteger();
    return errors.flatMap(error -> {
      int retry = attempt.incrementAndGet();
      if (retry > maxRetries || nonRetryable.test(error)) {
        return Flowable.error(error);
      }
      long delay = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS << (retry - 1));
      LOG.debug("Retry {}/{} in {} ms after: {}", retry, maxRetries, delay, error.getMessage());
      return Flowable.timer(delay, TimeUnit.MILLISECONDS);
    });
  }

  // ------------------------------------------------------------------
  // Single-stream path
  // ------------------------------------------------------------------

  private Single<Path> doStreamDownload(
    String repository,
    String fileName,
    Path outputPath,
    long totalSize
  ) {
    LOG.info("Downloading [{}/{}] ...", repository, fileName);
    String resolvePath = "/" + repository + "/resolve/main/" + fileName;

    // written to a temp sibling and published atomically, so the final path never
    // exists in a partial state
    Path tempPath = tempPathFor(outputPath);
    return ensureParentDir(outputPath)
      .andThen(
        vertx
          .fileSystem()
          .rxOpen(
            tempPath.toString(),
            new OpenOptions().setCreate(true).setWrite(true).setTruncateExisting(true)
          )
      )
      .flatMap(asyncFile -> {
        long timerId = startProgress(
          repository + "/" + fileName,
          () -> statSize(tempPath),
          totalSize
        );
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
              return Single.<Path>error(
                new IllegalStateException(
                  "Download failed for [" +
                    fileName +
                    "] from [" +
                    repository +
                    "]: HTTP " +
                    response.statusCode()
                )
              );
            }
            return publish(tempPath, outputPath).andThen(
              Single.fromCallable(() -> {
                LOG.info("Downloaded [{}/{}] -> {}", repository, fileName, outputPath);
                return outputPath.toAbsolutePath();
              })
            );
          });
      })
      .onErrorResumeNext(err ->
        vertx
          .fileSystem()
          .rxDelete(tempPath.toString())
          .onErrorComplete()
          .andThen(Single.<Path>error(err))
      );
  }

  /**
   * Unique temp sibling of the final path — same directory, hence same filesystem, so the
   * publishing move can be atomic. The name never collides with a requested file name.
   */
  private static Path tempPathFor(Path outputPath) {
    return outputPath.resolveSibling(
      outputPath.getFileName() + ".part-" + ProcessHandle.current().pid() + "-" + System.nanoTime()
    );
  }

  /** Publishes a fully verified temp file at the final path with an atomic move. */
  private Completable publish(Path tempPath, Path outputPath) {
    var fs = vertx.fileSystem();
    var atomic = new CopyOptions().setAtomicMove(true).setReplaceExisting(true);
    var replace = new CopyOptions().setReplaceExisting(true);
    return fs
      .rxMove(tempPath.toString(), outputPath.toString(), atomic)
      .onErrorResumeNext(err -> fs.rxMove(tempPath.toString(), outputPath.toString(), replace));
  }

  /** Best-effort removal of {@code .part-*} leftovers from crashed downloads of this file. */
  private static void cleanStaleParts(Path outputPath) {
    Path parent = outputPath.getParent();
    if (parent == null || !Files.isDirectory(parent)) return;
    String prefix = outputPath.getFileName() + ".part-";
    try (var siblings = Files.list(parent)) {
      siblings
        .filter(p -> p.getFileName().toString().startsWith(prefix))
        .forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException e) {
            LOG.debug("Could not delete stale part file {}: {}", p, e.getMessage());
          }
        });
    } catch (IOException e) {
      LOG.debug("Could not scan for stale part files of {}: {}", outputPath, e.getMessage());
    }
  }

  /** Best-effort on-disk size for stream-download progress (0 until the file appears). */
  private static long statSize(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }

  /**
   * Logs a per-file download progress bar once a second via a Vert.x periodic timer. The
   * supplier is the on-disk size for stream downloads and a byte counter for chunked ones
   * (chunked files are preallocated, so their on-disk size is meaningless). Falls back to a
   * plain byte counter when the total is unknown.
   *
   * @return the periodic timer id (cancel it when the download settles)
   */
  private long startProgress(String label, LongSupplier downloaded, long total) {
    return vertx.setPeriodic(1_000, id -> {
      long size = downloaded.getAsLong();
      if (total > 0) {
        int pct = (int) Math.min(100, (size * 100) / total);
        LOG.info("  {} {} {}% ({} / {} MiB)", label, bar(pct), pct, mib(size), mib(total));
      } else {
        LOG.info("  {} … {} MiB", label, mib(size));
      }
    });
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

  private static WebClient createClient(Vertx vertx, String host, int port, boolean ssl) {
    var options = new WebClientOptions()
      .setName("gio-singularitee-hf-downloader")
      .setDefaultHost(host)
      .setDefaultPort(port)
      .setSsl(ssl)
      .setConnectTimeout(CONNECT_TIMEOUT_MS)
      .setIdleTimeout(IDLE_TIMEOUT_S)
      .setIdleTimeoutUnit(TimeUnit.SECONDS);

    return WebClient.create(vertx, options);
  }

  /** Client for absolute (post-redirect, possibly CDN) URLs, pooled for chunk parallelism. */
  private static WebClient createAbsClient(Vertx vertx, int parallelism) {
    var options = new WebClientOptions()
      .setName("gio-singularitee-hf-chunks")
      .setConnectTimeout(CONNECT_TIMEOUT_MS)
      .setIdleTimeout(IDLE_TIMEOUT_S)
      .setIdleTimeoutUnit(TimeUnit.SECONDS);
    var poolOptions = new PoolOptions().setHttp1MaxSize(Math.max(parallelism, 5));

    return WebClient.create(vertx, options, poolOptions);
  }

  /**
   * Raw client for Range probes: no body aggregation, so a probe can read the status line
   * and headers and abort the connection before any body transfers.
   */
  private static HttpClient createProbeClient(Vertx vertx) {
    var options = new HttpClientOptions()
      .setConnectTimeout(CONNECT_TIMEOUT_MS)
      .setIdleTimeout(IDLE_TIMEOUT_S)
      .setIdleTimeoutUnit(TimeUnit.SECONDS);
    return vertx.createHttpClient(options);
  }
}
