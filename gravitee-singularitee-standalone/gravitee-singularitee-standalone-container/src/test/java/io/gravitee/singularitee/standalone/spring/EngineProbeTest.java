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
package io.gravitee.singularitee.standalone.spring;

import static io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration.GLINER_PROBE;
import static io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration.LLAMA_CPP_PROBE;
import static io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration.ONNX_PROBE;
import static io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration.VLLM_PROBE;
import static io.gravitee.singularitee.standalone.spring.SingulariteeConfiguration.isPresent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the classpath probes that decide which engines a distribution
 * advertises.
 *
 * <p>The failure mode these tests exist for is silent and severe: a probe
 * naming a class that does not exist makes {@code isPresent} return
 * {@code false} forever, so the engine is quietly dropped from <em>every</em>
 * flavour — including the full one — and the only symptom is "no factory for
 * model type" at workspace-load time, far from the cause. Package names do move
 * (gliner4j's runtime lives under {@code io.gravitee.lab.gliner4j}, not
 * {@code io.gravitee.ai.gliner4j}, despite the Maven groupId), so this must be
 * checked rather than assumed.
 *
 * <p>The test module has every engine on its classpath, which is what makes it
 * the right place to assert that all four resolve.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class EngineProbeTest {

  @Test
  void every_probe_names_a_class_that_exists() {
    for (String probe : List.of(LLAMA_CPP_PROBE, VLLM_PROBE, ONNX_PROBE, GLINER_PROBE)) {
      assertThat(isPresent(probe))
        .as(
          "Probe '%s' does not resolve. The engine it guards would be silently " +
            "dropped from every distribution flavour — fix the class name or the dependency.",
          probe
        )
        .isTrue();
    }
  }

  @Test
  void an_absent_class_is_reported_absent() {
    // The other half of the contract: probing must fail closed, not throw,
    // which is exactly what the per-engine flavours rely on at startup.
    assertThat(isPresent("io.gravitee.does.not.Exist")).isFalse();
  }

  @Test
  void probes_name_the_third_party_library_not_our_adapter() {
    // Per-engine flavours drop the engine's third-party JAR. Probing one of our
    // own adapter classes instead would resolve in every flavour and defeat the
    // whole mechanism.
    assertThat(LLAMA_CPP_PROBE).doesNotStartWith("io.gravitee.singularitee");
    assertThat(VLLM_PROBE).doesNotStartWith("io.gravitee.singularitee");
    assertThat(ONNX_PROBE).doesNotStartWith("io.gravitee.singularitee");
    assertThat(GLINER_PROBE).doesNotStartWith("io.gravitee.singularitee");
  }
}
