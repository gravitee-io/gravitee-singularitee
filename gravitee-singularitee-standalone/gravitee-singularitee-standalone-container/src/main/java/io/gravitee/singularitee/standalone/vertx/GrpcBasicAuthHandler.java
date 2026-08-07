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

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.grpc.server.GrpcServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP/2 request handler that enforces HTTP Basic authentication in front of the
 * Vert.x {@link GrpcServer}.
 *
 * <p>gRPC carries call metadata as HTTP/2 headers, so a client authenticates by
 * sending an {@code authorization: Basic base64(username:password)} entry in its
 * call metadata. This handler validates that header against the configured users
 * before delegating the request to the wrapped {@link GrpcServer}; otherwise it
 * replies with a gRPC {@code UNAUTHENTICATED} (status 16) trailers-only response.
 *
 * <p>Credentials are compared in constant time to avoid leaking which part of a
 * guess was correct through response-timing differences.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GrpcBasicAuthHandler implements Handler<HttpServerRequest> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcBasicAuthHandler.class);

  /** gRPC status code for an unauthenticated call (see grpc-status numeric codes). */
  private static final String GRPC_STATUS_UNAUTHENTICATED = "16";

  private static final String AUTHORIZATION_HEADER = "authorization";
  private static final String BASIC_PREFIX = "Basic ";

  private final GrpcServer delegate;
  private final Map<String, String> users;

  /**
   * @param delegate the gRPC server to forward authenticated requests to
   * @param users    immutable map of username to plaintext password; must be non-empty
   */
  public GrpcBasicAuthHandler(GrpcServer delegate, Map<String, String> users) {
    if (users == null || users.isEmpty()) {
      throw new IllegalArgumentException("gRPC basic auth enabled but no users configured");
    }
    this.delegate = delegate;
    this.users = Map.copyOf(users);
  }

  @Override
  public void handle(HttpServerRequest request) {
    if (isAuthenticated(request)) {
      delegate.handle(request);
    } else {
      rejectUnauthenticated(request);
    }
  }

  private boolean isAuthenticated(HttpServerRequest request) {
    String header = request.getHeader(AUTHORIZATION_HEADER);
    if (header == null || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
      return false;
    }
    final String decoded;
    try {
      decoded = new String(
        Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).trim()),
        StandardCharsets.UTF_8
      );
    } catch (IllegalArgumentException e) {
      return false;
    }
    int sep = decoded.indexOf(':');
    if (sep < 0) {
      return false;
    }
    String username = decoded.substring(0, sep);
    String password = decoded.substring(sep + 1);

    String expected = users.get(username);
    // Always run the comparison (against an empty string for unknown users) so the
    // response time does not reveal whether the username exists.
    return constantTimeEquals(expected == null ? "" : expected, password) && expected != null;
  }

  private void rejectUnauthenticated(HttpServerRequest request) {
    LOGGER.debug("Rejecting unauthenticated gRPC call to {}", request.path());
    request
      .response()
      .putHeader("content-type", "application/grpc")
      .putHeader("grpc-status", GRPC_STATUS_UNAUTHENTICATED)
      .putHeader("grpc-message", "Invalid or missing credentials")
      .end();
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    );
  }
}
