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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared readiness flag for Singularitee. The gRPC and HTTP servers bind and answer
 * {@code GET /health} (liveness) as soon as they start, but they are not <em>ready</em> to serve
 * inference until the workspace has finished loading its models and pipelines.
 *
 * <p>While {@link #isReady()} is {@code false}, the servers answer service requests with HTTP
 * {@code 503 Service Unavailable} (gRPC clients map this to {@code UNAVAILABLE}). The workspace
 * loader calls {@link #markReady()} once loading completes.
 */
public final class ReadinessState {

  private final AtomicBoolean ready = new AtomicBoolean(false);

  /** @return {@code true} once the workspace has finished loading. */
  public boolean isReady() {
    return ready.get();
  }

  /** Marks the server ready to serve inference (idempotent). */
  public void markReady() {
    ready.set(true);
  }
}
