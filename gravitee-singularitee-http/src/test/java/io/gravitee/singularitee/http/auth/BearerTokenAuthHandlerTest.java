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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BearerTokenAuthHandlerTest {

  private RoutingContext rc;
  private HttpServerRequest req;
  private HttpServerResponse resp;
  private BearerTokenAuthHandler handler;

  @BeforeEach
  void setUp() {
    rc = mock(RoutingContext.class);
    req = mock(HttpServerRequest.class);
    resp = mock(HttpServerResponse.class);
    when(rc.request()).thenReturn(req);
    when(rc.response()).thenReturn(resp);
    when(resp.setStatusCode(anyInt())).thenReturn(resp);
    when(resp.putHeader(anyString(), anyString())).thenReturn(resp);
    handler = new BearerTokenAuthHandler(List.of("secret-key", "another-key"));
  }

  @Test
  void validTokenPassesThrough() {
    when(req.getHeader("Authorization")).thenReturn("Bearer secret-key");
    handler.handle(rc);
    verify(rc).next();
    verify(resp, never()).setStatusCode(401);
  }

  @Test
  void wrongTokenIsRejected() {
    when(req.getHeader("Authorization")).thenReturn("Bearer nope");
    handler.handle(rc);
    verify(rc, never()).next();
    verify(resp).setStatusCode(401);
    verify(resp).end(anyString());
  }

  @Test
  void missingHeaderIsRejected() {
    when(req.getHeader("Authorization")).thenReturn(null);
    handler.handle(rc);
    verify(rc, never()).next();
    verify(resp).setStatusCode(401);
  }

  @Test
  void noTokensConfiguredThrows() {
    assertThatThrownBy(() -> new BearerTokenAuthHandler(List.of())).isInstanceOf(
      IllegalArgumentException.class
    );
  }
}
