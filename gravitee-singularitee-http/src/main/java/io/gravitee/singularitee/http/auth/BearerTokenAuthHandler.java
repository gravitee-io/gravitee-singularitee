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
package io.gravitee.singularitee.http.auth;

import io.gravitee.singularitee.http.json.OpenAiError;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;

/**
 * Validates {@code Authorization: Bearer <key>} against a configured set of API keys. Comparison is
 * constant-time over fixed-length SHA-256 digests (so neither key length nor content leaks via
 * timing), and all configured keys are always checked. Rejects with a {@code 401} OpenAI error.
 */
public final class BearerTokenAuthHandler implements Handler<RoutingContext> {

  private static final String BEARER = "Bearer ";

  private final List<byte[]> tokenDigests;

  public BearerTokenAuthHandler(Collection<String> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      throw new IllegalArgumentException(
        "http.auth.enabled is true but no http.auth.tokens configured"
      );
    }
    this.tokenDigests = tokens
      .stream()
      .filter(t -> t != null && !t.isBlank())
      .map(BearerTokenAuthHandler::sha256)
      .toList();
  }

  @Override
  public void handle(RoutingContext rc) {
    String header = rc.request().getHeader("Authorization");
    if (
      header != null &&
      header.regionMatches(true, 0, BEARER, 0, BEARER.length()) &&
      matches(header.substring(BEARER.length()).trim())
    ) {
      rc.next();
      return;
    }
    rc
      .response()
      .setStatusCode(401)
      .putHeader("content-type", "application/json")
      .putHeader("WWW-Authenticate", "Bearer")
      .end(
        OpenAiError.json(
          "Invalid API key provided",
          "invalid_request_error",
          null,
          "invalid_api_key"
        )
      );
  }

  private boolean matches(String presented) {
    byte[] candidate = sha256(presented);
    boolean ok = false;
    for (byte[] digest : tokenDigests) {
      // No early-exit: OR keeps the loop constant-time across the configured keys.
      ok |= MessageDigest.isEqual(digest, candidate);
    }
    return ok;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
