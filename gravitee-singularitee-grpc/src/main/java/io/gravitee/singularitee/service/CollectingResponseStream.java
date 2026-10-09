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
package io.gravitee.singularitee.service;

import io.gravitee.singularitee.protocol.InferResponse;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;

/**
 * A response stream that keeps only the event that ended it, for callers that run a pipeline for
 * its side effects on the context rather than for its token stream.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class CollectingResponseStream implements WriteStream<InferResponse> {

  private volatile InferResponse last;

  /** The event that ended the stream, or {@code null} while it is open. */
  InferResponse last() {
    return last;
  }

  @Override
  public Future<Void> write(InferResponse data) {
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> end() {
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> end(InferResponse data) {
    last = data;
    return Future.succeededFuture();
  }

  @Override
  public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return false;
  }

  @Override
  public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
    return this;
  }
}
