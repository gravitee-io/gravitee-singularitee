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
package io.gravitee.singularitee.inference.llama.cpp.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Closing the pool never strands a caller, and never frees a decider a call still holds. */
class DeciderPoolTest {

  @Test
  void callers_waiting_for_a_decider_fail_when_the_pool_closes() throws Exception {
    var pool = new DeciderPool<>(List.of("d1", "d2"));
    String first = pool.acquire();
    String second = pool.acquire();

    try (var executor = Executors.newCachedThreadPool()) {
      var waiters = new ArrayList<Future<String>>();
      for (int i = 0; i < 3; i++) waiters.add(executor.submit(pool::acquire));
      awaitWaiting(pool, 3);

      // Closing waits for the lent deciders; give them back from the scoring side.
      Future<Boolean> closing = executor.submit(() -> pool.close(Duration.ofSeconds(5)));
      for (Future<String> waiter : waiters) {
        assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasRootCauseMessage("the decision model is closed");
      }
      assertThat(closing.isDone()).as("closed under lent deciders").isFalse();

      pool.release(first);
      pool.release(second);
      assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void a_caller_that_arrives_after_the_close_fails_at_once() {
    var pool = new DeciderPool<>(List.of("d1"));
    assertThat(pool.close(Duration.ZERO)).isTrue();

    assertThatThrownBy(pool::acquire)
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("the decision model is closed");
  }

  @Test
  void a_decider_still_lent_when_the_wait_ends_keeps_its_memory() {
    var pool = new DeciderPool<>(List.of("d1", "d2"));
    pool.acquire();

    assertThat(pool.close(Duration.ofMillis(50))).isFalse();
  }

  @Test
  void an_open_pool_lends_each_decider_to_one_caller_at_a_time() throws Exception {
    var pool = new DeciderPool<>(List.of("d1"));
    String held = pool.acquire();

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<String> next = executor.submit(pool::acquire);
      awaitWaiting(pool, 1);
      assertThat(next.isDone()).isFalse();

      pool.release(held);
      assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("d1");
    }
  }

  @Test
  void many_callers_share_few_deciders_without_a_lost_wake_up() throws Exception {
    var pool = new DeciderPool<>(List.of("d1", "d2"));
    try (var executor = Executors.newFixedThreadPool(50)) {
      var callers = new ArrayList<Future<?>>();
      for (int c = 0; c < 50; c++) {
        callers.add(
          executor.submit(() -> {
            for (int i = 0; i < 200; i++) pool.release(pool.acquire());
          })
        );
      }
      for (Future<?> caller : callers) caller.get(30, TimeUnit.SECONDS);
    }
    assertThat(pool.close(Duration.ZERO)).isTrue();
  }

  private static void awaitWaiting(DeciderPool<?> pool, int callers) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (pool.waiting() < callers) {
      if (System.nanoTime() > deadline) throw new AssertionError("callers never waited");
      Thread.sleep(5);
    }
  }
}
