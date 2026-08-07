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
package io.gravitee.singularitee.inference.api.textgen;

import java.util.Arrays;
import java.util.Set;

/**
 * Cross-request KV prefix-cache bookkeeping for batch-engine slots.
 *
 * <p>Each slot is {@code COLD} (no KV worth reusing), {@code WARM} (a finished
 * sequence left its committed prompt+completion tokens KV-resident) or
 * {@code IN_USE}. {@link #acquire} picks the slot whose cached tokens share the
 * longest common prefix with the incoming prompt — preferring an exact
 * {@code cacheKey} match — and returns how many prefix tokens the new sequence
 * may reuse.
 *
 * <p><b>Busy slots are donors too.</b> A KV cell in llama.cpp carries a
 * <em>set</em> of sequence ids rather than belonging to one, so a resident
 * prefix can be published onto a second slot without moving any tensor data,
 * even while its owner is still generating. When the best match is {@code IN_USE}
 * the selection names it as {@link Selection#donorSlot()} and the destination is
 * a free slot; the caller performs the copy. Without this, N concurrent requests
 * behind one system prompt would each prefill it in full — only the first would
 * ever benefit, because it alone would leave a free warm slot behind.
 *
 * <p><b>Residency invariant.</b> A slot's cached tokens must never claim more
 * than is provably resident, or a later copy reads cells that no longer exist.
 * {@link #acquire} therefore narrows the chosen slot to the matched prefix — all
 * a copy destination holds, and all an in-place reuse keeps once the prefill
 * trims it. The caller widens it again with {@link #publish} once the prompt has
 * actually been prefilled, and must also shrink it to nothing if the copy failed.
 *
 * <p>Pure data structure: no locking, no engine calls. The caller (the batch
 * engine) holds its own lock around every method — including across
 * {@code acquire} and the copy it implies — and is responsible for actually
 * retaining/releasing KV on the backend.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SlotCache {

  /** Lifecycle of one slot's cached KV. */
  enum SlotState {
    COLD,
    WARM,
    IN_USE,
  }

  /**
   * Result of {@link #acquire}.
   *
   * @param slot              the slot the sequence will run in
   * @param reusePrefixTokens prompt-prefix tokens reusable on it
   * @param donorSlot         slot to copy that prefix from, or {@code -1} when the
   *                          prefix is already on {@code slot} (or there is none)
   */
  public record Selection(int slot, int reusePrefixTokens, int donorSlot) {
    /** Whether the caller must publish the donor's prefix onto {@link #slot()} before starting. */
    public boolean requiresCopy() {
      return donorSlot >= 0 && reusePrefixTokens > 0;
    }
  }

  private static final int[] EMPTY = new int[0];

  private final SlotState[] states;
  private final int[][] cachedTokens;
  private final String[] cacheKeys;
  private final long[] lastUsed;
  private final int minReuseTokens;
  private long tick;

  public SlotCache(int numSlots, int minReuseTokens) {
    if (numSlots <= 0) {
      throw new IllegalArgumentException("numSlots must be positive");
    }
    this.states = new SlotState[numSlots];
    this.cachedTokens = new int[numSlots][];
    this.cacheKeys = new String[numSlots];
    this.lastUsed = new long[numSlots];
    this.minReuseTokens = Math.max(0, minReuseTokens);
    for (int i = 0; i < numSlots; i++) {
      states[i] = SlotState.COLD;
      cachedTokens[i] = EMPTY;
    }
  }

  /**
   * Picks the best slot among {@code freeSlots} for a prompt and marks it IN_USE.
   *
   * <p>Priority: (1) a free warm slot whose {@code cacheKey} matches (reuse = LCP,
   * no threshold — key affinity is an explicit client hint); (2) the slot with the
   * longest token-id LCP, if it reaches {@code minReuseTokens} — busy slots
   * included, in which case the prefix is copied onto a free destination and the
   * donor is named in the selection; (3) a cold free slot, else the
   * least-recently-used warm slot — un-keyed warm slots are evicted before keyed
   * ones.
   *
   * <p>The chosen slot's cached tokens are narrowed to the matched prefix. See the
   * residency invariant on the class: the caller must {@link #publish} the truth
   * once the prompt is prefilled, and shrink to nothing if a copy failed.
   *
   * @param cacheKey     client affinity key, or {@code null}
   * @param promptTokens the prompt's token ids (never {@code null})
   * @param freeSlots    the slots currently free to start a sequence
   * @return the selection, or {@code null} if {@code freeSlots} is empty
   */
  public Selection acquire(String cacheKey, int[] promptTokens, Set<Integer> freeSlots) {
    if (freeSlots == null || freeSlots.isEmpty()) {
      return null;
    }

    // (1) explicit key affinity — only among free slots, since reusing in place
    // costs nothing and keeps the whole cached sequence rather than a prefix of it.
    if (cacheKey != null) {
      int best = -1;
      long bestUsed = Long.MIN_VALUE;
      for (int slot : freeSlots) {
        if (states[slot] == SlotState.WARM && cacheKey.equals(cacheKeys[slot])) {
          if (lastUsed[slot] > bestUsed) {
            best = slot;
            bestUsed = lastUsed[slot];
          }
        }
      }
      if (best >= 0) {
        return checkout(best, lcp(cachedTokens[best], promptTokens), promptTokens, -1);
      }
    }

    // (2) longest common prefix above the threshold, over every slot holding
    // tokens — a busy one is a legitimate donor, its cells are shared not moved.
    int bestSlot = -1;
    int bestLcp = 0;
    for (int slot = 0; slot < states.length; slot++) {
      if (states[slot] == SlotState.COLD) {
        continue;
      }
      int l = lcp(cachedTokens[slot], promptTokens);
      if (l >= minReuseTokens && l > bestLcp) {
        bestSlot = slot;
        bestLcp = l;
      }
    }
    if (bestSlot >= 0) {
      if (freeSlots.contains(bestSlot) && states[bestSlot] == SlotState.WARM) {
        // The holder is idle: run there directly, no copy needed.
        return checkout(bestSlot, bestLcp, promptTokens, -1);
      }
      int destination = pickFreeSlot(freeSlots, bestSlot);
      if (destination >= 0) {
        return checkout(destination, bestLcp, promptTokens, bestSlot);
      }
      // The donor is the only free slot — fall through and run in place.
      if (freeSlots.contains(bestSlot)) {
        return checkout(bestSlot, bestLcp, promptTokens, -1);
      }
    }

    // (3) no usable prefix: prefer a cold slot, else evict the LRU warm one
    int chosen = pickFreeSlot(freeSlots, -1);
    if (chosen < 0) {
      // Free slots exist but are all marked IN_USE — inconsistent bookkeeping;
      // fall back to any free slot with no reuse.
      chosen = freeSlots.iterator().next();
    }
    return checkout(chosen, 0, promptTokens, -1);
  }

  /**
   * A free slot to start a sequence in: coldest first, else the least recently
   * used warm one (un-keyed before keyed). {@code exclude} is never returned —
   * it names the donor, whose cells the caller is about to share.
   */
  private int pickFreeSlot(Set<Integer> freeSlots, int exclude) {
    int cold = -1;
    int lruUnkeyed = -1;
    int lruKeyed = -1;
    for (int slot : freeSlots) {
      if (slot == exclude) {
        continue;
      }
      if (states[slot] == SlotState.COLD) {
        if (cold < 0 || lastUsed[slot] < lastUsed[cold]) {
          cold = slot;
        }
      } else if (states[slot] == SlotState.WARM) {
        if (cacheKeys[slot] == null) {
          if (lruUnkeyed < 0 || lastUsed[slot] < lastUsed[lruUnkeyed]) {
            lruUnkeyed = slot;
          }
        } else if (lruKeyed < 0 || lastUsed[slot] < lastUsed[lruKeyed]) {
          lruKeyed = slot;
        }
      }
    }
    return cold >= 0 ? cold : (lruUnkeyed >= 0 ? lruUnkeyed : lruKeyed);
  }

  /**
   * Records what an IN_USE slot provably holds — narrowing it when a copy was
   * refused, widening it to the committed tokens once the prompt is prefilled so
   * later requests can copy from it while it is still generating.
   *
   * @param slot           the slot
   * @param residentTokens its KV-resident token ids, {@code null}/empty for none
   */
  public void publish(int slot, int[] residentTokens) {
    cachedTokens[slot] = (residentTokens == null || residentTokens.length == 0)
      ? EMPTY
      : residentTokens;
  }

  /** Marks a slot WARM after its sequence finished with its KV retained. */
  public void release(int slot, String cacheKey, int[] committedTokens) {
    if (committedTokens == null || committedTokens.length == 0) {
      invalidate(slot);
      return;
    }
    states[slot] = SlotState.WARM;
    cachedTokens[slot] = committedTokens;
    cacheKeys[slot] = cacheKey;
    lastUsed[slot] = ++tick;
  }

  /** Marks a slot COLD — its KV content is gone or untrustworthy. */
  public void invalidate(int slot) {
    states[slot] = SlotState.COLD;
    cachedTokens[slot] = EMPTY;
    cacheKeys[slot] = null;
    lastUsed[slot] = ++tick;
  }

  /** Visible for tests. */
  SlotState state(int slot) {
    return states[slot];
  }

  private Selection checkout(int slot, int reuse, int[] promptTokens, int donorSlot) {
    states[slot] = SlotState.IN_USE;
    lastUsed[slot] = ++tick;
    // The previous occupant's key must not survive into the new one.
    cacheKeys[slot] = null;
    // Residency invariant: advertise only the matched prefix. A copy destination
    // holds exactly that, and an in-place reuse is about to be trimmed to it by
    // the prefill. publish() widens it again once the prompt has been evaluated.
    cachedTokens[slot] = reuse > 0 ? Arrays.copyOf(promptTokens, reuse) : EMPTY;
    return new Selection(slot, reuse, donorSlot);
  }

  private static int lcp(int[] a, int[] b) {
    int n = Math.min(a.length, b.length);
    int i = 0;
    while (i < n && a[i] == b[i]) {
      i++;
    }
    return i;
  }
}
