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
package io.gravitee.singularitee.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.protocol.ClassifyRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Metadata added with {@link SingulariteeClient#withMetadata} reaches the server as headers. */
class SingulariteeClientMetadataTest {

  private static final String TRACEPARENT =
    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private final List<MultiMap> received = new CopyOnWriteArrayList<>();
  private Vertx vertx;
  private HttpServer server;

  /** An HTTP/2 server that answers every call UNIMPLEMENTED and keeps its request headers. */
  @BeforeEach
  void startServer() throws Exception {
    vertx = Vertx.vertx();
    server = vertx
      .createHttpServer()
      .requestHandler(request -> {
        received.add(MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
        request
          .response()
          .putHeader("content-type", "application/grpc")
          .putHeader("grpc-status", "12")
          .end();
      })
      .listen(0, "127.0.0.1")
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stopServer() {
    vertx.close();
  }

  private SingulariteeClient client(String username, String password) {
    return new SingulariteeClient(vertx, "127.0.0.1", server.actualPort(), username, password);
  }

  private static void call(SingulariteeClient client) {
    assertThatThrownBy(() ->
      client
        .classify(ClassifyRequest.newBuilder().setModelId("pii").setText("hi").build())
        .blockingGet()
    ).isNotNull();
  }

  @Test
  void the_metadata_travels_with_the_call() {
    call(client(null, null).withMetadata(Map.of("traceparent", TRACEPARENT)));

    assertThat(received).hasSize(1);
    assertThat(received.getFirst().get("traceparent")).isEqualTo(TRACEPARENT);
  }

  @Test
  void the_metadata_joins_the_credentials() {
    call(client("svc", "secret").withMetadata(Map.of("traceparent", TRACEPARENT)));

    assertThat(received.getFirst().get("traceparent")).isEqualTo(TRACEPARENT);
    assertThat(received.getFirst().get("authorization")).startsWith("Basic ");
  }

  @Test
  void the_client_it_came_from_sends_none_and_survives_it() {
    var origin = client(null, null);

    origin.withMetadata(Map.of("traceparent", TRACEPARENT)).close();
    call(origin);

    assertThat(received).hasSize(1);
    assertThat(received.getFirst().get("traceparent")).isNull();
  }

  @Test
  void no_metadata_is_the_same_client() {
    var origin = client(null, null);

    assertThat(origin.withMetadata(Map.of())).isSameAs(origin);
  }
}
