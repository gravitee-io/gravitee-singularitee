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
package io.gravitee.singularitee.http.sse;

import io.gravitee.singularitee.http.translation.ServerEvent;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.http.HttpServerResponse;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams a {@code Flowable} of SSE events to a Vert.x {@link HttpServerResponse} as
 * {@code text/event-stream}, coupling RxJava demand to the HTTP write queue (one event requested at
 * a time, resumed via {@code drainHandler} when the queue is full) so a slow client throttles the
 * producer rather than buffering unboundedly.
 */
public final class VertxSseWriter {

  private static final Logger log = LoggerFactory.getLogger(VertxSseWriter.class);

  private VertxSseWriter() {}

  /** Writes {@link ServerEvent}s as {@code data: <payload>\n\n} frames. */
  public static void write(HttpServerResponse response, Flowable<ServerEvent> events) {
    writeRaw(response, events.map(e -> "data: " + e.data() + "\n\n"));
  }

  /** Writes already-formatted SSE frames (used by denormalizers that emit full {@code event:} frames). */
  public static void writeRaw(HttpServerResponse response, Flowable<String> frames) {
    if (!response.headWritten()) {
      response.setChunked(true);
      response.putHeader("content-type", "text/event-stream");
      response.putHeader("cache-control", "no-cache");
      response.putHeader("connection", "keep-alive");
    }
    frames.subscribe(
      new Subscriber<String>() {
        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
          this.subscription = s;
          s.request(1);
        }

        @Override
        public void onNext(String frame) {
          response
            .write(frame)
            .onComplete(ar -> {
              if (ar.failed()) {
                subscription.cancel();
                return;
              }
              if (response.writeQueueFull()) {
                response.drainHandler(v -> {
                  response.drainHandler(null);
                  subscription.request(1);
                });
              } else {
                subscription.request(1);
              }
            });
        }

        @Override
        public void onError(Throwable t) {
          log.warn("SSE stream terminated with error", t);
          if (!response.ended()) {
            response.end();
          }
        }

        @Override
        public void onComplete() {
          if (!response.ended()) {
            response.end();
          }
        }
      }
    );
  }
}
