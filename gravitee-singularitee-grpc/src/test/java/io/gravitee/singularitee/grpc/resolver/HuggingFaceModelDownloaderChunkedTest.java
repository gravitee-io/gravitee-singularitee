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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the hf_transfer-style chunked download path against a local stub of the
 * HuggingFace hub ({@code localhost}) redirecting to a "CDN" ({@code 127.0.0.1}, same
 * server, different authority — so Authorization stripping is observable).
 */
class HuggingFaceModelDownloaderChunkedTest {

  private static final String REPO = "acme/tiny-model";
  private static final String FILE = "model.bin";
  private static final int PAYLOAD_SIZE = 5 * 1024 * 1024;
  private static final HuggingFaceModelDownloader.Options OPTIONS =
    new HuggingFaceModelDownloader.Options(1024 * 1024, 4, 2L * 1024 * 1024);

  /** One record per request the stub served. */
  private record Seen(String path, String range, String auth) {}

  private Vertx vertx;
  private HttpServer server;
  private int port;
  private byte[] payload;

  private final List<Seen> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger resolveCount = new AtomicInteger();
  private final Map<String, AtomicInteger> rangeFailures = new ConcurrentHashMap<>();

  private volatile boolean ignoreRange;
  private volatile boolean slowProbeBody;
  private volatile int failuresPerChunk;
  private volatile boolean expireAllTokens;
  private volatile int shortBodiesPerChunk;
  private volatile boolean alwaysShortChunks;

  private final Map<String, AtomicInteger> shortBodiesServed = new ConcurrentHashMap<>();
  private final AtomicInteger probeBytesSent = new AtomicInteger();

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    payload = new byte[PAYLOAD_SIZE];
    for (int i = 0; i < PAYLOAD_SIZE; i++) {
      payload[i] = (byte) (i * 31 + 7);
    }
    server = vertx
      .createHttpServer()
      .requestHandler(req -> handle(req.getDelegate()))
      .rxListen(0, "0.0.0.0")
      .blockingGet();
    port = server.actualPort();
  }

  @AfterEach
  void tearDown() {
    vertx.close().blockingAwait();
  }

  private void handle(HttpServerRequest req) {
    requests.add(new Seen(req.path(), req.getHeader("Range"), req.getHeader("Authorization")));

    if (req.path().equals("/api/models/" + REPO)) {
      var sibling = new JsonObject()
        .put("rfilename", FILE)
        .put("lfs", new JsonObject().put("size", PAYLOAD_SIZE));
      var body = new JsonObject().put("siblings", new JsonArray().add(sibling));
      req.response().putHeader("Content-Type", "application/json").end(body.encode());
      return;
    }

    if (req.path().equals("/" + REPO + "/resolve/main/" + FILE)) {
      int token = resolveCount.incrementAndGet();
      req
        .response()
        .setStatusCode(302)
        .putHeader("Location", "http://127.0.0.1:" + port + "/cdn/" + FILE + "?token=" + token)
        .end();
      return;
    }

    if (req.path().equals("/cdn/" + FILE)) {
      serveCdn(req);
      return;
    }

    req.response().setStatusCode(404).end();
  }

  private void serveCdn(HttpServerRequest req) {
    if (expireAllTokens) {
      req.response().setStatusCode(403).end();
      return;
    }
    String range = req.getHeader("Range");
    if (range == null || ignoreRange) {
      if (range != null && slowProbeBody) {
        // A Range-carrying request (the probe) answered 200 with the full body, drip-fed
        // so a client that aborts after the status line stops the transfer early.
        serveSlowFullBody(req.response());
        return;
      }
      req.response().setStatusCode(200).end(Buffer.buffer(payload));
      return;
    }
    String[] bounds = range.substring("bytes=".length()).split("-");
    long start = Long.parseLong(bounds[0]);
    long end = Long.parseLong(bounds[1]);
    boolean isProbe = start == 0 && end == 0;
    if (!isProbe && failuresPerChunk > 0) {
      int failed = rangeFailures.computeIfAbsent(range, k -> new AtomicInteger()).incrementAndGet();
      if (failed <= failuresPerChunk) {
        req.response().setStatusCode(500).end();
        return;
      }
    }
    boolean serveShort =
      !isProbe &&
      (alwaysShortChunks ||
        (shortBodiesPerChunk > 0 &&
          shortBodiesServed.computeIfAbsent(range, k -> new AtomicInteger()).incrementAndGet() <=
          shortBodiesPerChunk));
    if (serveShort) {
      // A 206 whose body carries fewer bytes than the Range asked for — a
      // truncated connection the client can only detect by counting bytes.
      byte[] truncated = new byte[(int) (end - start + 1) / 2];
      System.arraycopy(payload, (int) start, truncated, 0, truncated.length);
      req
        .response()
        .setStatusCode(206)
        .putHeader("Content-Range", "bytes " + start + "-" + end + "/" + PAYLOAD_SIZE)
        .end(Buffer.buffer(truncated));
      return;
    }
    byte[] slice = new byte[(int) (end - start + 1)];
    System.arraycopy(payload, (int) start, slice, 0, slice.length);
    req
      .response()
      .setStatusCode(206)
      .putHeader("Content-Range", "bytes " + start + "-" + end + "/" + PAYLOAD_SIZE)
      .end(Buffer.buffer(slice));
  }

  /** Streams the full payload as a 200 in small timed pieces, counting the bytes written. */
  private void serveSlowFullBody(HttpServerResponse resp) {
    int piece = 64 * 1024;
    resp.setStatusCode(200).putHeader("Content-Length", String.valueOf(payload.length));
    AtomicInteger offset = new AtomicInteger();
    vertx.setPeriodic(20, timerId -> {
      int start = offset.get();
      if (resp.closed() || start >= payload.length) {
        vertx.cancelTimer(timerId);
        if (!resp.closed() && start >= payload.length) {
          resp.end();
        }
        return;
      }
      int len = Math.min(piece, payload.length - start);
      byte[] slice = new byte[len];
      System.arraycopy(payload, start, slice, 0, len);
      resp.write(Buffer.buffer(slice));
      offset.addAndGet(len);
      probeBytesSent.addAndGet(len);
    });
  }

  private HuggingFaceModelDownloader downloader() {
    return new HuggingFaceModelDownloader(vertx, "secret-token", OPTIONS, "localhost", port, false);
  }

  private Path download(Path dir) {
    return downloader()
      .download(REPO, List.of(FILE), dir)
      .timeout(60, TimeUnit.SECONDS)
      .blockingGet()
      .get(0);
  }

  private List<Seen> cdnRequests() {
    return requests
      .stream()
      .filter(s -> s.path().startsWith("/cdn/"))
      .toList();
  }

  @Test
  void chunked_download_is_byte_identical_to_the_source(@TempDir Path tmp) throws Exception {
    Path result = download(tmp);

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    // real chunk ranges beyond the 1-byte probe prove the parallel Range path ran
    assertThat(cdnRequests()).anyMatch(s -> s.range() != null && !s.range().equals("bytes=0-0"));
  }

  @Test
  void authorization_goes_to_the_hub_but_never_to_the_cdn(@TempDir Path tmp) {
    download(tmp);

    assertThat(requests)
      .filteredOn(s -> !s.path().startsWith("/cdn/"))
      .allMatch(s -> "Bearer secret-token".equals(s.auth()));
    assertThat(cdnRequests()).allMatch(s -> s.auth() == null);
  }

  @Test
  void falls_back_to_single_stream_when_the_server_ignores_range(@TempDir Path tmp)
    throws Exception {
    ignoreRange = true;

    Path result = download(tmp);

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
  }

  @Test
  void transient_chunk_failures_are_retried(@TempDir Path tmp) throws Exception {
    failuresPerChunk = 2;

    Path result = download(tmp);

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    assertThat(rangeFailures).isNotEmpty();
  }

  @Test
  void persistent_403_fails_after_retries_and_deletes_the_partial_file(@TempDir Path tmp)
    throws Exception {
    expireAllTokens = true;

    assertThatThrownBy(() -> download(tmp)).isInstanceOf(RuntimeException.class);
    // chunked attempt re-resolved the signed URL on each file-level retry, then the
    // single-stream fallback resolved once more
    assertThat(resolveCount.get()).isGreaterThanOrEqualTo(3);
    // the final path never existed (writes go to a temp sibling) and no temp leftovers remain
    assertThat(Files.exists(tmp.resolve(FILE))).isFalse();
    try (var files = Files.list(tmp)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void stale_part_files_are_cleaned_before_downloading(@TempDir Path tmp) throws Exception {
    Path stale = tmp.resolve(FILE + ".part-123-456");
    Files.write(stale, new byte[] { 1, 2, 3 });

    Path result = download(tmp);

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    assertThat(Files.exists(stale)).isFalse();
    try (var files = Files.list(tmp)) {
      assertThat(files).containsExactly(result);
    }
  }

  @Test
  void probe_aborts_the_body_when_the_server_ignores_range(@TempDir Path tmp) throws Exception {
    // the Range probe gets a 200 with the entire payload drip-fed; a probe that
    // aggregates the body would pull all of it into memory before seeing the status
    ignoreRange = true;
    slowProbeBody = true;

    Path result = download(tmp);

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    assertThat(probeBytesSent.get()).isLessThan(PAYLOAD_SIZE / 2);
  }

  @Test
  void truncated_chunk_bodies_are_retried_until_complete(@TempDir Path tmp) throws Exception {
    // 206 with a short body: status says success, only the byte count betrays it.
    shortBodiesPerChunk = 2;

    Path result = withUncaughtCapture(() -> download(tmp));

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    // the chunk-level retry re-requested the truncated ranges
    assertThat(shortBodiesServed).isNotEmpty();
    shortBodiesServed.forEach((range, served) ->
      assertThat(requests)
        .filteredOn(s -> range.equals(s.range()))
        .as("range %s must be re-requested after truncated responses", range)
        .hasSizeGreaterThan(2)
    );
  }

  @Test
  void permanently_truncated_chunks_fall_back_to_single_stream(@TempDir Path tmp) throws Exception {
    // Every ranged response is short, so the chunked path can never complete:
    // it must exhaust its retries and fall back to the un-ranged stream, which
    // this stub serves correctly.
    alwaysShortChunks = true;

    Path result = withUncaughtCapture(() ->
      downloader()
        .download(REPO, List.of(FILE), tmp)
        .timeout(180, TimeUnit.SECONDS)
        .blockingGet()
        .get(0)
    );

    assertThat(Files.readAllBytes(result)).isEqualTo(payload);
    // the fallback is the request with no Range header at all
    assertThat(cdnRequests()).anyMatch(s -> s.range() == null);
  }

  /**
   * Runs a scenario with an {@code RxJavaPlugins} error handler installed and asserts no
   * uncaught error reached it — chunk chains disposed by a sibling's fail-fast failure must
   * drop their late errors, not throw them at the global handler.
   */
  private <T> T withUncaughtCapture(Supplier<T> scenario) {
    List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    var previous = RxJavaPlugins.getErrorHandler();
    RxJavaPlugins.setErrorHandler(uncaught::add);
    T result;
    try {
      result = scenario.get();
      // give disposed in-flight chunk chains time to observe their late responses
      Thread.sleep(250);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } finally {
      RxJavaPlugins.setErrorHandler(previous);
    }
    assertThat(uncaught).as("uncaught RxJava errors").isEmpty();
    return result;
  }

  @Test
  void small_files_skip_the_ranged_path(@TempDir Path tmp) throws Exception {
    byte[] small = new byte[1024];
    for (int i = 0; i < small.length; i++) {
      small[i] = (byte) i;
    }
    payload = small;

    Path result = downloader()
      .download(REPO, List.of(FILE), tmp, Map.of(FILE, 1024L))
      .timeout(60, TimeUnit.SECONDS)
      .blockingGet()
      .get(0);

    assertThat(Files.readAllBytes(result)).isEqualTo(small);
    assertThat(requests).allMatch(s -> s.range() == null);
  }
}
