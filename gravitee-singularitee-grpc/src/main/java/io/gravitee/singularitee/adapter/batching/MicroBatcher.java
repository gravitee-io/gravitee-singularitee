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
package io.gravitee.singularitee.adapter.batching;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-lane micro-batcher (Nagle-style) for coalescing many small blocking calls into batched calls.
 *
 * <p>Concurrent producers on any thread {@link #submit(Object, int)} an input with a cost weight
 * (estimated tokens) and get a {@link CompletableFuture} for its result. Items are routed by weight
 * into one of two independent lanes — <em>short</em> ({@code weight <= bucketWeight}) and
 * <em>long</em> — each with its own FIFO queue and daemon worker thread. A lane's worker takes the
 * first pending item, lingers up to {@code lingerMillis} to let more accumulate, then invokes
 * {@code batchFn} <em>once</em> for the whole group and completes each item's future with the
 * matching output (by position).
 *
 * <p>Batch composition is shaped for encoders that pad every batch item to the longest sequence in
 * the batch (quadratic-attention cost):
 * <ul>
 *   <li><strong>Token cap</strong> — a batch closes once its summed weight reaches
 *       {@code maxBatchWeight}, bounding worst-case batch wall time regardless of item count.</li>
 *   <li><strong>Length bucketing</strong> — short and long items never share a batch, so a single
 *       long chunk can't inflate the padded cost of many short ones.</li>
 *   <li><strong>Lane isolation</strong> — because each lane dispatches on its own thread, an
 *       expensive long batch inside {@code batchFn} cannot head-of-line block short requests
 *       (convoy effect); the short lane keeps flowing while a long batch runs.</li>
 * </ul>
 *
 * <p>When a lane is idle its first item is dispatched after at most {@code lingerMillis} as a batch
 * of one, so the latency cost of batching is bounded by the linger window.
 *
 * <p><strong>batchFn must be thread-safe:</strong> the two lanes may invoke it concurrently (ONNX
 * Runtime sessions support concurrent {@code Run()} calls).
 *
 * @param <I> batch input element type
 * @param <O> batch output element type (one per input, same order)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class MicroBatcher<I, O> implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(MicroBatcher.class);

  private final long bucketWeight;
  private final Lane shortLane;
  private final Lane longLane;
  private volatile boolean running = true;

  public MicroBatcher(
    String name,
    int maxBatchSize,
    long maxBatchWeight,
    long bucketWeight,
    long lingerMillis,
    Function<List<I>, List<O>> batchFn
  ) {
    this.bucketWeight = Math.max(1, bucketWeight);
    int size = Math.max(1, maxBatchSize);
    long weight = Math.max(1, maxBatchWeight);
    long lingerNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, lingerMillis));
    this.shortLane = new Lane(name + "-short", size, weight, lingerNanos, batchFn);
    this.longLane = new Lane(name + "-long", size, weight, lingerNanos, batchFn);
  }

  /**
   * Enqueue {@code item} for a future batch. {@code weight} is the item's estimated cost in tokens;
   * it selects the lane (short vs long) and drives the per-batch token cap. The returned future
   * completes on the owning lane's worker thread with this item's output, or completes
   * exceptionally if the batch call throws (all items in a failed batch fail together).
   */
  public CompletableFuture<O> submit(I item, int weight) {
    Job<I, O> job = new Job<>(item, Math.max(1, weight));
    if (!running) {
      job.future.completeExceptionally(new IllegalStateException("micro-batcher is closed"));
      return job.future;
    }
    (job.weight <= bucketWeight ? shortLane : longLane).queue.add(job);
    return job.future;
  }

  @Override
  public void close() {
    running = false;
    shortLane.close();
    longLane.close();
  }

  /** One FIFO queue + worker thread; composes and dispatches batches independently of the other lane. */
  private final class Lane {

    private final BlockingQueue<Job<I, O>> queue = new LinkedBlockingQueue<>();
    private final String name;
    private final int maxBatchSize;
    private final long maxBatchWeight;
    private final long lingerNanos;
    private final Function<List<I>, List<O>> batchFn;
    private final Thread worker;

    /** Job pulled during linger that would overflow the token cap; leads the next batch. */
    private Job<I, O> carry;

    private Lane(
      String name,
      int maxBatchSize,
      long maxBatchWeight,
      long lingerNanos,
      Function<List<I>, List<O>> batchFn
    ) {
      this.name = name;
      this.maxBatchSize = maxBatchSize;
      this.maxBatchWeight = maxBatchWeight;
      this.lingerNanos = lingerNanos;
      this.batchFn = batchFn;
      this.worker = new Thread(this::runLoop, "microbatch-" + name);
      this.worker.setDaemon(true);
      this.worker.start();
    }

    private void runLoop() {
      while (running) {
        try {
          // Head of the next batch: the carry-over from the previous linger, or block (with a
          // periodic wake so shutdown is observed) until a job arrives.
          Job<I, O> head = carry;
          carry = null;
          if (head == null) {
            head = queue.poll(1, TimeUnit.SECONDS);
            if (head == null) {
              continue;
            }
          }
          runBatch(fillBatch(head));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      failRemaining();
    }

    /**
     * Builds a batch led by {@code head} (always included, even if it alone exceeds the token
     * cap), lingering up to the window for more jobs until the count or token cap is reached. A
     * polled job that would overflow the cap is kept as {@link #carry} to lead the next batch.
     */
    private List<Job<I, O>> fillBatch(Job<I, O> head) throws InterruptedException {
      List<Job<I, O>> batch = new ArrayList<>(maxBatchSize);
      batch.add(head);
      long weight = head.weight;
      long deadline = System.nanoTime() + lingerNanos;
      while (batch.size() < maxBatchSize && weight < maxBatchWeight) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          break;
        }
        Job<I, O> next = queue.poll(remaining, TimeUnit.NANOSECONDS);
        if (next == null) {
          break;
        }
        if (weight + next.weight > maxBatchWeight) {
          carry = next;
          break;
        }
        batch.add(next);
        weight += next.weight;
      }
      return batch;
    }

    private void runBatch(List<Job<I, O>> batch) {
      List<I> inputs = new ArrayList<>(batch.size());
      for (Job<I, O> job : batch) {
        inputs.add(job.item);
      }
      if (LOGGER.isDebugEnabled()) {
        long totalWeight = 0;
        int minWeight = Integer.MAX_VALUE;
        int maxWeight = 0;
        for (Job<I, O> job : batch) {
          totalWeight += job.weight;
          minWeight = Math.min(minWeight, job.weight);
          maxWeight = Math.max(maxWeight, job.weight);
        }
        LOGGER.debug(
          "dispatching {} batch: {} item(s), weights {}..{} (sum {}), {} still pending",
          name,
          batch.size(),
          minWeight,
          maxWeight,
          totalWeight,
          queue.size() + (carry != null ? 1 : 0)
        );
      }
      try {
        List<O> outputs = batchFn.apply(inputs);
        if (outputs == null || outputs.size() != inputs.size()) {
          throw new IllegalStateException(
            "batch function returned " +
              (outputs == null ? "null" : outputs.size()) +
              " results for " +
              inputs.size() +
              " inputs"
          );
        }
        for (int i = 0; i < batch.size(); i++) {
          batch.get(i).future.complete(outputs.get(i));
        }
      } catch (Throwable t) {
        LOGGER.warn("Micro-batch of {} item(s) failed; failing all in the batch", batch.size(), t);
        for (Job<I, O> job : batch) {
          job.future.completeExceptionally(t);
        }
      }
    }

    private void failRemaining() {
      IllegalStateException closed = new IllegalStateException("micro-batcher is closed");
      if (carry != null) {
        carry.future.completeExceptionally(closed);
        carry = null;
      }
      Job<I, O> job;
      while ((job = queue.poll()) != null) {
        job.future.completeExceptionally(closed);
      }
    }

    private void close() {
      worker.interrupt();
    }
  }

  private static final class Job<I, O> {

    private final I item;
    private final int weight;
    private final CompletableFuture<O> future = new CompletableFuture<>();

    private Job(I item, int weight) {
      this.item = item;
      this.weight = weight;
    }
  }
}
