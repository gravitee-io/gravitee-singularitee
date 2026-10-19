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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The idle deciders of a model, lent to one call at a time.
 *
 * <p>Closing wakes every waiting caller, which then fails instead of waiting on a pool nothing
 * will refill, and waits for the borrowed deciders to come back, so their native memory is never
 * freed under a running call.
 *
 * @param <T> the decider type
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class DeciderPool<T> {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final ArrayDeque<T> idle;
  private final int size;
  private boolean closed;

  DeciderPool(List<T> deciders) {
    this.idle = new ArrayDeque<>(deciders);
    this.size = deciders.size();
  }

  /**
   * An idle decider, waiting for one when all are lent.
   *
   * @throws IllegalStateException when the pool is closed, before or while waiting
   */
  T acquire() {
    try {
      lock.lockInterruptibly();
    } catch (InterruptedException e) {
      throw interrupted(e);
    }
    try {
      while (!closed && idle.isEmpty()) changed.await();
      if (closed) throw new IllegalStateException("the decision model is closed");
      return idle.poll();
    } catch (InterruptedException e) {
      throw interrupted(e);
    } finally {
      lock.unlock();
    }
  }

  /** Gives back a decider {@link #acquire()} lent. */
  void release(T decider) {
    lock.lock();
    try {
      idle.add(decider);
      // One decider frees one waiter. Once closed, no caller waits any more (close() woke them
      // all and later ones fail at once), so this wakes close() itself.
      changed.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes the pool: waiting and later callers fail, and the call waits up to {@code wait} for
   * the lent deciders to come back.
   *
   * @return whether every decider is back, so their memory can be freed
   */
  boolean close(Duration wait) {
    lock.lock();
    try {
      closed = true;
      changed.signalAll();
      long left = wait.toNanos();
      while (idle.size() < size && left > 0) left = changed.awaitNanos(left);
      return idle.size() == size;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return idle.size() == size;
    } finally {
      lock.unlock();
    }
  }

  /** Callers waiting for a decider; for tests. */
  int waiting() {
    lock.lock();
    try {
      return lock.getWaitQueueLength(changed);
    } finally {
      lock.unlock();
    }
  }

  private static IllegalStateException interrupted(InterruptedException e) {
    Thread.currentThread().interrupt();
    return new IllegalStateException("interrupted while waiting for a decider", e);
  }
}
