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
package io.gravitee.singularitee.pipeline.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.WriteStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link TokenStreamWriter#subscribe} through a controllable {@link UnicastProcessor}
 * (the engine side) and a fake {@link WriteStream} (the client side), asserting the reactive
 * streaming behaviour shared by the direct and pipeline paths: in-order CREATED / DELTA /
 * COMPLETED events written on the Vert.x context, real write-queue backpressure (no progress
 * while the queue is full), and overflow → cancel-sequence + terminal FAILED.
 */
class TokenStreamWriterTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  @Test
  void streams_created_deltas_then_completed() throws Exception {
    var context = vertx.getOrCreateContext();
    var stream = new FakeWriteStream();
    var engine = new RecordingEngine();
    TokenStreamWriter.subscribe(engine, 7, stream, context, "req-1", "model-x");

    context.runOnContext(v -> {
      engine.processor.onNext(token(7, "he", false, null));
      engine.processor.onNext(token(7, "llo", false, null));
      engine.processor.onNext(token(7, null, true, "stop"));
      engine.processor.onComplete();
    });

    assertTrue(stream.endLatch.await(5, TimeUnit.SECONDS), "stream should end");

    // CREATED once, then the two deltas in order.
    assertEquals(3, stream.written.size());
    assertEquals(
      ResponseEventType.RESPONSE_EVENT_TYPE_CREATED,
      stream.written.get(0).getEventType()
    );
    assertEquals("he", stream.written.get(1).getResponseOutputTextDelta().getDelta());
    assertEquals("llo", stream.written.get(2).getResponseOutputTextDelta().getDelta());

    // Final token ends the stream with COMPLETED + finish reason.
    assertEquals(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED, stream.ended.getEventType());
    assertEquals(
      FinishReason.FINISH_REASON_STOP,
      stream.ended.getResponseCompleted().getFinishReason()
    );
    assertTrue(engine.cancelled.isEmpty(), "no cancellation on a clean completion");
  }

  @Test
  void overflow_cancels_sequence_and_sends_failed() throws Exception {
    var context = vertx.getOrCreateContext();
    var stream = new FakeWriteStream();
    var engine = new RecordingEngine();
    TokenStreamWriter.subscribe(engine, 9, stream, context, "req-2", "model-x");

    context.runOnContext(v -> {
      engine.processor.onNext(token(9, "x", false, null));
      // Simulate the bounded-buffer overflow surfaced by onBackpressureBuffer(ERROR).
      engine.processor.onError(new MissingBackpressureException("overflow"));
    });

    assertTrue(stream.endLatch.await(5, TimeUnit.SECONDS), "stream should end on overflow");
    assertEquals(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED, stream.ended.getEventType());
    assertEquals("stream_overflow", stream.ended.getResponseFailed().getErrorCode());
    assertEquals(List.of(9), engine.cancelled, "overflow must cancel the sequence");
  }

  @Test
  void full_write_queue_pauses_until_drained() throws Exception {
    var context = vertx.getOrCreateContext();
    var stream = new FakeWriteStream();
    stream.queueFull = true; // client cannot accept more right now
    var engine = new RecordingEngine();
    TokenStreamWriter.subscribe(engine, 5, stream, context, "req-3", "model-x");

    context.runOnContext(v -> {
      engine.processor.onNext(token(5, "a", false, null)); // first delta
      engine.processor.onNext(token(5, null, true, "stop")); // final — must NOT be pulled while full
      engine.processor.onComplete();
    });

    // While the queue is full, exactly CREATED + first delta are written and the stream
    // is NOT ended — the subscriber stops pulling instead of buffering without bound.
    awaitUntil(() -> stream.written.size() == 2);
    Thread.sleep(150);
    assertEquals(2, stream.written.size(), "no further writes while the queue is full");
    assertNull(stream.ended, "stream must not end while back-pressured");

    // Draining resumes demand → the final token flows and the stream completes.
    context.runOnContext(v -> stream.drain());
    assertTrue(stream.endLatch.await(5, TimeUnit.SECONDS), "stream should end after drain");
    assertEquals(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED, stream.ended.getEventType());
  }

  // ---- helpers ----------------------------------------------------------------

  private static ModelEngineToken token(int seqId, String text, boolean isFinal, String finish) {
    return new ModelEngineToken(seqId, text, 0, isFinal, finish, 0, 0, 0, 0, null);
  }

  private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(2);
    }
    throw new AssertionError("condition not met within 5s");
  }

  /** Records the InferResponses written / ended, with a controllable write queue. */
  private static final class FakeWriteStream implements WriteStream<InferResponse> {

    final List<InferResponse> written = new CopyOnWriteArrayList<>();
    volatile InferResponse ended;
    final CountDownLatch endLatch = new CountDownLatch(1);
    volatile boolean queueFull = false;
    private volatile Handler<Void> drainHandler;

    void drain() {
      queueFull = false;
      Handler<Void> h = drainHandler;
      if (h != null) {
        h.handle(null);
      }
    }

    @Override
    public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public Future<Void> write(InferResponse data) {
      written.add(data);
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      endLatch.countDown();
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(InferResponse data) {
      ended = data;
      endLatch.countDown();
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return queueFull;
    }

    @Override
    public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
      this.drainHandler = handler;
      return this;
    }
  }

  /** Minimal engine whose rxStream is driven by the test; records cancellations. */
  private static final class RecordingEngine implements TextGenEngine {

    final UnicastProcessor<ModelEngineToken> processor = UnicastProcessor.create();
    final List<Integer> cancelled = new CopyOnWriteArrayList<>();

    @Override
    public Flowable<ModelEngineToken> rxStream(int seqId) {
      return processor.doOnCancel(() -> cancelled.add(seqId));
    }

    @Override
    public void cancelSequence(int seqId) {
      cancelled.add(seqId);
    }

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      return Completable.complete();
    }

    @Override
    public void close() {}
  }
}
