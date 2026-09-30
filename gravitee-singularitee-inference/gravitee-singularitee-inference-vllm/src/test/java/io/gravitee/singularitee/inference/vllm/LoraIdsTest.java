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
package io.gravitee.singularitee.inference.vllm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * LoRA ids follow the adapter, not the sequence slot: parallel sequences on one adapter share an id,
 * and a recycled slot never carries a previous adapter's id.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class LoraIdsTest {

  @Test
  void same_path_shares_one_id() {
    var ids = new LoraIds();
    var first = ids.assign("default", "/adapters/a");
    var second = ids.assign("default", "/adapters/a");
    assertThat(second).isEqualTo(first);
    assertThat(first.id()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void different_paths_get_different_ids() {
    var ids = new LoraIds();
    var a = ids.assign("a", "/adapters/a");
    var b = ids.assign("b", "/adapters/b");
    assertThat(a.id()).isNotEqualTo(b.id());
    assertThat(ids.assign("a", "/adapters/a")).isEqualTo(a);
  }

  @Test
  void blank_name_is_generated_from_the_id() {
    var ids = new LoraIds();
    var a = ids.assign(null, "/adapters/a");
    assertThat(a.name()).isEqualTo("lora-" + a.id());
    assertThat(ids.assign(" ", "/adapters/a")).isEqualTo(a);
  }

  @Test
  void a_name_reused_for_another_path_is_made_unique() {
    var ids = new LoraIds();
    var a = ids.assign("default", "/adapters/a");
    var b = ids.assign("default", "/adapters/b");
    assertThat(a.name()).isEqualTo("default");
    assertThat(b.name()).isNotEqualTo(a.name());
    assertThat(b.id()).isNotEqualTo(a.id());
  }

  @Test
  void concurrent_sequences_on_one_adapter_share_one_id() throws Exception {
    var ids = new LoraIds();
    int threads = 16;
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(threads)) {
      List<Future<LoraIds.Assigned>> futures = IntStream.range(0, threads)
        .mapToObj(i ->
          pool.submit(() -> {
            start.await();
            return ids.assign("default", "/adapters/a");
          })
        )
        .toList();
      start.countDown();
      Set<LoraIds.Assigned> distinct = futures
        .stream()
        .map(f -> {
          try {
            return f.get();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        })
        .collect(Collectors.toSet());
      assertThat(distinct).hasSize(1);
    }
  }
}
