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
package io.gravitee.singularitee.adapter;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import java.util.concurrent.Callable;

/**
 * Base adapter for engine implementations that wrap a blocking (CPU-bound) delegate.
 *
 * <p>Provides the idiomatic Vert.x RxJava3 scheduling pattern:
 * <ul>
 *   <li>{@code subscribeOn(blockingScheduler)}: offloads to a Vert.x worker thread</li>
 *   <li>{@code observeOn(eventLoopScheduler)}: delivers results back on the event loop</li>
 * </ul>
 *
 * <p>Subclasses call {@link #rxInfer(Callable)} to wrap any blocking inference call.
 *
 * @param <D> the blocking delegate type (e.g. {@code OnnxBertClassifierModel})
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class BlockingEngineAdapter<D> {

  protected final D delegate;
  private final Scheduler workerScheduler;
  private final Scheduler eventLoopScheduler;

  /**
   * Binds the blocking delegate with an <em>ordered</em> worker scheduler: every blocking call of
   * this engine runs one after another on one context, which is what a delegate that is not
   * thread-safe (a llama.cpp context) needs.
   */
  protected BlockingEngineAdapter(D delegate, Vertx vertx) {
    this(delegate, vertx, true);
  }

  /**
   * Binds the blocking delegate and derives the worker and event-loop schedulers from
   * {@code vertx}. {@code ordered = false} lets the calls run concurrently on the worker pool:
   * the one-argument {@code RxHelper.blockingScheduler(vertx)} is ordered, which caps an engine
   * at one request in flight and starves a micro-batcher behind it (it only ever sees a batch of
   * one). Only engines whose delegate tolerates concurrent calls (or serialises them itself, like
   * the GLiNER micro-batcher) pass {@code false}.
   */
  protected BlockingEngineAdapter(D delegate, Vertx vertx, boolean ordered) {
    this.delegate = delegate;
    this.workerScheduler = RxHelper.blockingScheduler(vertx, ordered);
    this.eventLoopScheduler = RxHelper.scheduler(vertx);
  }

  /**
   * Wraps a blocking inference call with the correct scheduling:
   * subscribes on a Vert.x worker thread, observes on the event loop.
   *
   * @param blockingCall the CPU-bound call to offload
   * @param <T>          the result type
   * @return a {@link Single} that emits the result on the event loop
   */
  protected <T> Single<T> rxInfer(Callable<T> blockingCall) {
    return Single.fromCallable(blockingCall)
      .subscribeOn(workerScheduler)
      .observeOn(eventLoopScheduler);
  }

  /**
   * The Vert.x event-loop scheduler, for delivering results produced off-Vert.x (e.g. by a
   * {@link Single} whose value is completed on a foreign thread) back onto
   * the event loop.
   */
  protected Scheduler eventLoopScheduler() {
    return eventLoopScheduler;
  }

  /**
   * The Vert.x worker scheduler, for {@code subscribeOn} in chains whose subscription-time work
   * (e.g. tokenization inside a {@code Single.defer}) is CPU-bound and must not run on the
   * event-loop thread the caller subscribes from.
   */
  protected Scheduler workerScheduler() {
    return workerScheduler;
  }
}
