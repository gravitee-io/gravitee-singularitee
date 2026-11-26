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
package io.gravitee.singularitee.standalone.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseSyncClientTest {

  private static final String YAML = "workspace:\n  name: demo\n";

  @TempDir
  Path cache;

  private HttpServer server;
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastQuery = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String checksum = ReleaseSyncClient.sha256(YAML);

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/_sync/release", exchange -> {
      lastAuth.set(exchange.getRequestHeaders().getFirst("X-Singularitee-Token"));
      lastQuery.set(exchange.getRequestURI().getQuery());
      if (status == 200) {
        byte[] body = ("{\"revision\":7,\"checksum\":\"" +
          checksum +
          "\",\"yaml\":\"workspace:\\n  name: demo\\n\"}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } else {
        exchange.sendResponseHeaders(status, -1);
      }
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private ReleaseSyncClient client() {
    return new ReleaseSyncClient(
      "http://127.0.0.1:" + server.getAddress().getPort(),
      "secret",
      cache,
      Duration.ofSeconds(5)
    );
  }

  @Test
  void fetch_returns_release_and_sends_token_and_known_revision() throws Exception {
    var release = client().fetch(3).orElseThrow();

    assertThat(release.revision()).isEqualTo(7);
    assertThat(release.yaml()).isEqualTo(YAML);
    assertThat(lastAuth.get()).isEqualTo("secret");
    assertThat(lastQuery.get()).isEqualTo("known=3");
  }

  @Test
  void fetch_returns_empty_when_unchanged() throws Exception {
    status = 304;

    assertThat(client().fetch(7)).isEmpty();
  }

  @Test
  void fetch_returns_empty_when_group_has_no_release_yet() throws Exception {
    status = 204;

    assertThat(client().fetch(0)).isEmpty();
  }

  @Test
  void fetch_fails_on_checksum_mismatch() {
    checksum = "deadbeef";

    assertThatThrownBy(() -> client().fetch(0))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("checksum mismatch");
  }

  @Test
  void fetch_fails_on_unexpected_status() {
    status = 401;

    assertThatThrownBy(() -> client().fetch(0))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("HTTP 401");
  }

  @Test
  void store_persists_release_and_cache_survives_for_offline_restart() throws Exception {
    var client = client();
    assertThat(client.cachedWorkspace()).isEmpty();
    assertThat(client.cachedRevision()).isZero();

    Path file = client.store(client.fetch(0).orElseThrow());

    assertThat(Files.readString(file)).isEqualTo(YAML);
    assertThat(client.cachedWorkspace()).contains(file);
    assertThat(client.cachedRevision()).isEqualTo(7);
  }
}
