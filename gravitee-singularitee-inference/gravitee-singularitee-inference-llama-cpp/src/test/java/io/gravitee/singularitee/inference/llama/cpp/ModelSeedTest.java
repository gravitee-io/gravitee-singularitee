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
package io.gravitee.singularitee.inference.llama.cpp;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.llama.cpp.SpeculativeConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Seed resolution: an explicit seed is kept, an unset one is random per request, and the
 * speculative draft follows the request's seed.
 *
 * @author GraviteeSource Team
 */
class ModelSeedTest {

  private static Request withSeed(Integer seed) {
    return new Request("prompt", List.of(), null, 0.7f, null, null, null, null, seed, null, null);
  }

  @Test
  void an_explicit_seed_is_kept() {
    assertThat(Model.seedFor(withSeed(1234))).isEqualTo(1234);
  }

  @Test
  void an_unset_seed_is_random_per_request() {
    Set<Integer> seeds = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      seeds.add(Model.seedFor(withSeed(null)));
    }
    assertThat(seeds)
      .hasSizeGreaterThan(1)
      .allSatisfy(seed -> assertThat(seed).isPositive());
  }

  @Test
  void a_pinned_draft_temperature_takes_the_request_seed() {
    var spec = SpeculativeConfig.greedy(2).withTemperature(0.5f);

    assertThat(Model.speculativeConfigFor(spec, withSeed(null), 77).seed()).isEqualTo(77);
    assertThat(Model.speculativeConfigFor(spec.withSeed(5), withSeed(1234), 1234).seed()).isEqualTo(
      1234
    );
  }

  @Test
  void a_pinned_draft_seed_is_kept_for_an_unseeded_request() {
    var spec = SpeculativeConfig.greedy(2).withTemperature(0.5f).withSeed(5);

    assertThat(Model.speculativeConfigFor(spec, withSeed(null), 77)).isEqualTo(spec);
  }

  @Test
  void a_request_sampled_draft_takes_the_request_seed() {
    var spec = SpeculativeConfig.greedy(2);

    var resolved = Model.speculativeConfigFor(spec, withSeed(null), 77);

    assertThat(resolved.seed()).isEqualTo(77);
    assertThat(resolved.temperature()).isEqualTo(0.7f);
  }
}
