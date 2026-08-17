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

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
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
  private volatile int failuresPerChunk;
  private volatile boolean expireAllTokens;

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
    byte[] slice = new byte[(int) (end - start + 1)];
    System.arraycopy(payload, (int) start, slice, 0, slice.length);
    req
      .response()
      .setStatusCode(206)
      .putHeader("Content-Range", "bytes " + start + "-" + end + "/" + PAYLOAD_SIZE)
      .end(Buffer.buffer(slice));
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
  void persistent_403_fails_after_retries_and_deletes_the_partial_file(@TempDir Path tmp) {
    expireAllTokens = true;

    assertThatThrownBy(() -> download(tmp)).isInstanceOf(RuntimeException.class);
    // chunked attempt re-resolved the signed URL on each file-level retry, then the
    // single-stream fallback resolved once more
    assertThat(resolveCount.get()).isGreaterThanOrEqualTo(3);
    assertThat(Files.exists(tmp.resolve(FILE))).isFalse();
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
