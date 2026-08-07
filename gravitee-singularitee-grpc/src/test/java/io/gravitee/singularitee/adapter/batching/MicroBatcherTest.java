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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MicroBatcherTest {

  private static final long TOKEN_CAP = 100;
  private static final long BUCKET = 10;

  /**
   * Records each dispatched batch; optionally blocks batches whose first item matches
   * {@code blockPrefix} until released. May be invoked concurrently by the two lanes.
   */
  private static final class RecordingBatchFn implements Function<List<String>, List<String>> {

    final List<List<String>> batches = new CopyOnWriteArrayList<>();
    final CountDownLatch blockedBatchRunning = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final String blockPrefix;

    RecordingBatchFn(String blockPrefix) {
      this.blockPrefix = blockPrefix;
    }

    @Override
    public List<String> apply(List<String> inputs) {
      if (blockPrefix != null && inputs.getFirst().startsWith(blockPrefix)) {
        blockedBatchRunning.countDown();
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      batches.add(List.copyOf(inputs));
      return inputs
        .stream()
        .map(s -> "out:" + s)
        .toList();
    }
  }

  @Test
  void completes_each_item_with_its_own_output() throws Exception {
    var fn = new RecordingBatchFn(null);
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      var f1 = batcher.submit("a", 1);
      var f2 = batcher.submit("b", 1);
      assertThat(f1.get(2, TimeUnit.SECONDS)).isEqualTo("out:a");
      assertThat(f2.get(2, TimeUnit.SECONDS)).isEqualTo("out:b");
    }
  }

  @Test
  void short_and_long_items_never_share_a_batch() throws Exception {
    // Block the first short batch so later shorts queue up in the short lane while longs keep
    // flowing: no dispatched batch may mix the two buckets.
    var fn = new RecordingBatchFn("plug");
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      List<CompletableFuture<String>> futures = new ArrayList<>();
      futures.add(batcher.submit("plug", 1)); // occupies the short lane
      assertThat(fn.blockedBatchRunning.await(2, TimeUnit.SECONDS)).isTrue();

      futures.add(batcher.submit("short1", 5));
      futures.add(batcher.submit("long1", 50));
      futures.add(batcher.submit("short2", 5));
      futures.add(batcher.submit("long2", 50));
      fn.release.countDown();

      for (var f : futures) {
        f.get(2, TimeUnit.SECONDS);
      }
      for (List<String> batch : fn.batches) {
        boolean hasShort = batch.stream().anyMatch(s -> s.startsWith("short") || s.equals("plug"));
        boolean hasLong = batch.stream().anyMatch(s -> s.startsWith("long"));
        assertThat(hasShort && hasLong).as("mixed short/long batch: %s", batch).isFalse();
      }
    }
  }

  @Test
  void long_batch_does_not_block_the_short_lane() throws Exception {
    // Convoy regression: while a long batch is stuck inside batchFn, short submissions must
    // still dispatch and complete on the short lane.
    var fn = new RecordingBatchFn("long");
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      var longFuture = batcher.submit("long-stall", 50);
      assertThat(fn.blockedBatchRunning.await(2, TimeUnit.SECONDS)).isTrue();

      var shortFuture = batcher.submit("quick", 1);
      // Must complete while the long batch is still blocked.
      assertThat(shortFuture.get(2, TimeUnit.SECONDS)).isEqualTo("out:quick");
      assertThat(longFuture).isNotDone();

      fn.release.countDown();
      assertThat(longFuture.get(2, TimeUnit.SECONDS)).isEqualTo("out:long-stall");
    }
  }

  @Test
  void batch_closes_at_the_token_cap() throws Exception {
    // Block the first long batch so the remaining long items queue up, then release: the
    // backlog must split into batches of at most 2 (cap 100, items of 40).
    var fn = new RecordingBatchFn("plug");
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      List<CompletableFuture<String>> futures = new ArrayList<>();
      futures.add(batcher.submit("plug-long", 40)); // occupies the long lane
      assertThat(fn.blockedBatchRunning.await(2, TimeUnit.SECONDS)).isTrue();

      for (int i = 0; i < 4; i++) {
        futures.add(batcher.submit("long" + i, 40));
      }
      fn.release.countDown();

      for (var f : futures) {
        f.get(2, TimeUnit.SECONDS);
      }
      for (List<String> batch : fn.batches) {
        if (batch.getFirst().startsWith("long")) {
          assertThat(batch.size()).as("token cap exceeded: %s", batch).isLessThanOrEqualTo(2);
        }
      }
    }
  }

  @Test
  void oversized_single_item_still_dispatches_alone() throws Exception {
    var fn = new RecordingBatchFn(null);
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      var f = batcher.submit("huge", (int) TOKEN_CAP * 3);
      assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("out:huge");
    }
  }

  @Test
  void failed_batch_fails_all_its_items() {
    Function<List<String>, List<String>> fn = inputs -> {
      throw new IllegalStateException("boom");
    };
    try (var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn)) {
      var f = batcher.submit("a", 1);
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void close_fails_pending_submissions() {
    var fn = new RecordingBatchFn(null);
    var batcher = new MicroBatcher<String, String>("t", 16, TOKEN_CAP, BUCKET, 5, fn);
    batcher.close();
    var f = batcher.submit("late", 1);
    assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
      .isInstanceOf(ExecutionException.class)
      .hasCauseInstanceOf(IllegalStateException.class);
  }
}
