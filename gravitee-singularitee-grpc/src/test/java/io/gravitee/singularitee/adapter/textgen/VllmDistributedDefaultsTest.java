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
package io.gravitee.singularitee.adapter.textgen;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.adapter.textgen.VllmEngineFactory.DistributedDefaults;
import org.junit.jupiter.api.Test;

/**
 * Precedence between a model's own GPU topology and the deployment-wide one.
 *
 * <p>The layering is the point: the topology is a property of the machine, so a
 * deployment sets it once ({@code ai.vllm.*} / {@code GRAVITEE_*}) and every
 * workspace inherits it — but a model that genuinely needs something else must
 * still be able to say so. Getting this backwards would either ignore the
 * deployment's setting or make it impossible to override.
 *
 * <p>Only the resolution is exercised here; constructing a real engine would
 * start CPython and load weights.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmDistributedDefaultsTest {

  /** Mirrors the precedence in {@link VllmEngineFactory#create}. */
  private static int resolve(int fromModel, int fromServer) {
    return fromModel > 0 ? fromModel : fromServer;
  }

  private static String resolve(String fromModel, String fromServer) {
    return fromModel.isEmpty() ? fromServer : fromModel;
  }

  @Test
  void the_model_wins_when_it_sets_a_topology() {
    assertThat(resolve(2, 8)).isEqualTo(2);
    assertThat(resolve("mp", "ray")).isEqualTo("mp");
  }

  @Test
  void the_server_default_applies_when_the_model_is_silent() {
    assertThat(resolve(0, 8)).isEqualTo(8);
    assertThat(resolve("", "ray")).isEqualTo("ray");
  }

  @Test
  void both_unset_leaves_it_to_vllm() {
    // 0 / null mean "say nothing to vLLM", so its own default (1) stands.
    assertThat(resolve(0, 0)).isZero();
    assertThat(resolve("", null)).isNull();
  }

  @Test
  void the_none_default_is_inert() {
    assertThat(DistributedDefaults.NONE.tensorParallelSize()).isZero();
    assertThat(DistributedDefaults.NONE.pipelineParallelSize()).isZero();
    assertThat(DistributedDefaults.NONE.distributedExecutorBackend()).isNull();
  }

  @Test
  void a_null_defaults_object_degrades_to_none() {
    // The no-arg constructor and a null argument must behave identically —
    // an engine flavour built without server config must not NPE.
    var factory = new VllmEngineFactory(null);

    assertThat(factory).isNotNull();
  }
}
