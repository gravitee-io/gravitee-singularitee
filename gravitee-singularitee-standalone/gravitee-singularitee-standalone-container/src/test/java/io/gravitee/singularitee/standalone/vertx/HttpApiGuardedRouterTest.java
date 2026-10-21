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
package io.gravitee.singularitee.standalone.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.http.auth.BearerTokenAuthHandler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The handler chain in front of every HTTP API route: auth before body, capped, no uploads. */
class HttpApiGuardedRouterTest {

  private static final String TOKEN = "secret-token";
  private static final long MAX_BODY = 1024;

  private Vertx vertx;
  private HttpServer server;
  private HttpClient client;
  private final AtomicBoolean uploadSeen = new AtomicBoolean();
  private String lastBody;

  @BeforeEach
  void start() throws Exception {
    vertx = Vertx.vertx();
    var readiness = new ReadinessState();
    readiness.markReady();
    var router = HttpApiServerComponent.guardedRouter(
      vertx,
      readiness,
      new BearerTokenAuthHandler(List.of(TOKEN)),
      MAX_BODY
    );
    router
      .post("/v1/echo")
      .handler(rc -> {
        if (!rc.fileUploads().isEmpty()) uploadSeen.set(true);
        rc.response().end(String.valueOf(rc.body().length()));
      });
    server = vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(0, "127.0.0.1")
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);
    client = vertx.createHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void unauthenticated_request_is_rejected() throws Exception {
    assertThat(post(null, "application/json", Buffer.buffer("{}"))).isEqualTo(401);
  }

  @Test
  void authenticated_request_within_limit_is_served() throws Exception {
    assertThat(post(TOKEN, "application/json", Buffer.buffer("{}"))).isEqualTo(200);
  }

  @Test
  void body_over_the_limit_is_rejected() throws Exception {
    var body = Buffer.buffer("x".repeat((int) MAX_BODY + 1));
    assertThat(post(TOKEN, "application/json", body)).isEqualTo(413);
    assertThat(lastBody).contains("request_too_large");
  }

  @Test
  void unauthenticated_oversized_body_is_refused_by_auth_not_read() throws Exception {
    var body = Buffer.buffer("x".repeat((int) MAX_BODY + 1));
    assertThat(post(null, "application/json", body)).isEqualTo(401);
  }

  @Test
  void multipart_uploads_are_not_handled() throws Exception {
    var boundary = "b0undary";
    var body = Buffer.buffer(
      "--" +
        boundary +
        "\r\n" +
        "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n" +
        "payload\r\n" +
        "--" +
        boundary +
        "--\r\n"
    );
    post(TOKEN, "multipart/form-data; boundary=" + boundary, body);
    assertThat(uploadSeen).isFalse();
  }

  private int post(String token, String contentType, Buffer body) throws Exception {
    var options = new RequestOptions()
      .setMethod(HttpMethod.POST)
      .setHost("127.0.0.1")
      .setPort(server.actualPort())
      .setURI("/v1/echo")
      .putHeader("content-type", contentType);
    if (token != null) options.putHeader("authorization", "Bearer " + token);
    return client
      .request(options)
      .compose(req -> req.send(body))
      .compose(resp ->
        resp
          .body()
          .map(b -> {
            lastBody = b.toString();
            return resp.statusCode();
          })
      )
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);
  }
}
