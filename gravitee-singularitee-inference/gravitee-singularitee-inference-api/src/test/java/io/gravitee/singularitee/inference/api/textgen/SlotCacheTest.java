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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SlotCacheTest {

  private static int[] tokens(int... ids) {
    return ids;
  }

  private static int[] range(int from, int count) {
    return IntStream.range(from, from + count).toArray();
  }

  @Test
  void cold_start_returns_zero_reuse_and_marks_in_use() {
    var cache = new SlotCache(2, 4);
    var sel = cache.acquire(null, tokens(1, 2, 3), Set.of(0, 1));

    assertThat(sel).isNotNull();
    assertThat(sel.reusePrefixTokens()).isZero();
    assertThat(cache.state(sel.slot())).isEqualTo(SlotCache.SlotState.IN_USE);
  }

  @Test
  void empty_free_set_returns_null() {
    var cache = new SlotCache(2, 4);
    assertThat(cache.acquire(null, tokens(1, 2), Set.of())).isNull();
  }

  @Test
  void warm_slot_with_longest_lcp_wins_when_above_threshold() {
    var cache = new SlotCache(3, 4);
    cache.release(0, null, range(0, 10)); // LCP 10 with prompt
    cache.release(1, null, tokens(0, 1, 2, 3, 99, 98)); // LCP 4
    cache.release(2, null, tokens(50, 51)); // LCP 0

    var sel = cache.acquire(null, range(0, 20), Set.of(0, 1, 2));

    assertThat(sel.slot()).isEqualTo(0);
    assertThat(sel.reusePrefixTokens()).isEqualTo(10);
  }

  @Test
  void lcp_below_threshold_is_ignored_and_a_cold_slot_is_preferred() {
    var cache = new SlotCache(2, 8);
    cache.release(0, null, range(0, 5)); // LCP 5 < minReuse 8

    var sel = cache.acquire(null, range(0, 20), Set.of(0, 1));

    assertThat(sel.slot()).isEqualTo(1); // cold slot preferred over warm mismatch
    assertThat(sel.reusePrefixTokens()).isZero();
  }

  @Test
  void matching_cache_key_wins_even_below_threshold() {
    var cache = new SlotCache(2, 64);
    cache.release(0, "alice", range(0, 5)); // LCP 5 « threshold 64
    cache.release(1, null, range(0, 30)); // longer LCP but no key

    var sel = cache.acquire("alice", range(0, 100), Set.of(0, 1));

    assertThat(sel.slot()).isEqualTo(0);
    assertThat(sel.reusePrefixTokens()).isEqualTo(5);
  }

  @Test
  void no_key_match_falls_back_to_lcp_selection() {
    var cache = new SlotCache(2, 4);
    cache.release(0, "bob", range(0, 10));

    var sel = cache.acquire("alice", range(0, 10), Set.of(0, 1));

    // No slot has alice's key, but slot 0's LCP (10) beats the threshold.
    assertThat(sel.slot()).isEqualTo(0);
    assertThat(sel.reusePrefixTokens()).isEqualTo(10);
  }

  @Test
  void lru_warm_slot_is_evicted_when_nothing_matches() {
    var cache = new SlotCache(2, 4);
    cache.release(0, null, tokens(90, 91)); // older
    cache.release(1, null, tokens(80, 81)); // newer

    var sel = cache.acquire(null, tokens(1, 2, 3), Set.of(0, 1));

    assertThat(sel.slot()).isEqualTo(0); // least recently used
    assertThat(sel.reusePrefixTokens()).isZero();
  }

  @Test
  void keyed_warm_slots_are_evicted_last() {
    var cache = new SlotCache(2, 4);
    cache.release(0, "alice", tokens(90, 91)); // keyed, older
    cache.release(1, null, tokens(80, 81)); // un-keyed, newer

    var sel = cache.acquire(null, tokens(1, 2, 3), Set.of(0, 1));

    assertThat(sel.slot()).isEqualTo(1); // un-keyed evicted before keyed despite recency
  }

  @Test
  void release_with_null_or_empty_tokens_goes_cold() {
    var cache = new SlotCache(2, 4);
    cache.release(0, "k", null);
    cache.release(1, "k", tokens());

    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.COLD);
    assertThat(cache.state(1)).isEqualTo(SlotCache.SlotState.COLD);
  }

  @Test
  void invalidate_clears_key_and_tokens() {
    var cache = new SlotCache(1, 4);
    cache.release(0, "alice", range(0, 10));
    cache.invalidate(0);

    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.COLD);
    var sel = cache.acquire("alice", range(0, 10), Set.of(0));
    assertThat(sel.reusePrefixTokens()).isZero();
  }

  @Test
  void busy_slot_donates_its_prefix_to_a_free_slot() {
    var cache = new SlotCache(2, 4);

    // Slot 0 starts cold and, once prefilled, publishes what it holds.
    var first = cache.acquire(null, range(0, 20), Set.of(0));
    assertThat(first.slot()).isEqualTo(0);
    assertThat(first.donorSlot()).isEqualTo(-1);
    cache.publish(0, range(0, 20));

    // A second request arrives while slot 0 is still generating: the prefix is
    // copied onto the free slot instead of being prefilled again.
    var second = cache.acquire(null, range(0, 20), Set.of(1));

    assertThat(second.slot()).isEqualTo(1);
    assertThat(second.donorSlot()).isZero();
    assertThat(second.reusePrefixTokens()).isEqualTo(20);
    assertThat(second.requiresCopy()).isTrue();
  }

  @Test
  void busy_slot_does_not_donate_before_it_has_published() {
    var cache = new SlotCache(2, 4);
    cache.acquire(null, range(0, 20), Set.of(0)); // in flight, nothing published yet

    var second = cache.acquire(null, range(0, 20), Set.of(1));

    // Slot 0 provably holds nothing yet — claiming its rows would read cells that
    // do not exist.
    assertThat(second.reusePrefixTokens()).isZero();
    assertThat(second.donorSlot()).isEqualTo(-1);
  }

  @Test
  void idle_holder_is_used_in_place_rather_than_copied_from() {
    var cache = new SlotCache(2, 4);
    cache.release(0, null, range(0, 20));

    var sel = cache.acquire(null, range(0, 20), Set.of(0, 1));

    assertThat(sel.slot()).isEqualTo(0);
    assertThat(sel.donorSlot()).isEqualTo(-1);
    assertThat(sel.requiresCopy()).isFalse();
  }

  @Test
  void acquire_narrows_the_slot_to_the_matched_prefix() {
    var cache = new SlotCache(2, 4);
    cache.release(0, null, range(0, 20));

    // Shares only 6 tokens, so the prefill will trim slot 0 back to 6.
    var sel = cache.acquire(null, tokens(0, 1, 2, 3, 4, 5, 99, 98, 97), Set.of(0));
    assertThat(sel.slot()).isEqualTo(0);
    assertThat(sel.reusePrefixTokens()).isEqualTo(6);

    // Slot 0 must now advertise 6 tokens, not the 20 it used to hold — a copy of
    // 20 would share rows it is about to lose.
    var next = cache.acquire(null, range(0, 20), Set.of(1));
    assertThat(next.donorSlot()).isZero();
    assertThat(next.reusePrefixTokens()).isEqualTo(6);
  }

  @Test
  void donated_prefix_still_respects_the_threshold() {
    var cache = new SlotCache(2, 8);
    cache.acquire(null, range(0, 20), Set.of(0));
    cache.publish(0, range(0, 20));

    // Only 5 tokens in common, below minReuseTokens — not worth a copy.
    var sel = cache.acquire(null, tokens(0, 1, 2, 3, 4, 70, 71), Set.of(1));

    assertThat(sel.reusePrefixTokens()).isZero();
    assertThat(sel.donorSlot()).isEqualTo(-1);
  }

  @Test
  void publish_with_no_tokens_stops_a_slot_donating() {
    var cache = new SlotCache(2, 4);
    cache.acquire(null, range(0, 20), Set.of(0));
    cache.publish(0, range(0, 20));
    cache.publish(0, null); // e.g. the copy onto this slot failed

    var sel = cache.acquire(null, range(0, 20), Set.of(1));

    assertThat(sel.reusePrefixTokens()).isZero();
    assertThat(sel.donorSlot()).isEqualTo(-1);
  }

  @Test
  void full_lifecycle_transitions() {
    var cache = new SlotCache(1, 4);
    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.COLD);

    var sel = cache.acquire("k", range(0, 8), Set.of(0));
    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.IN_USE);
    assertThat(sel.reusePrefixTokens()).isZero();

    cache.release(0, "k", range(0, 12)); // prompt + completion committed
    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.WARM);

    var sel2 = cache.acquire("k", range(0, 8), Set.of(0));
    assertThat(sel2.reusePrefixTokens()).isEqualTo(8);
    assertThat(cache.state(0)).isEqualTo(SlotCache.SlotState.IN_USE);
  }
}
