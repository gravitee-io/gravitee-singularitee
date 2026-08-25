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
package io.gravitee.singularitee.engine.api.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Registration refuses a pipeline that declares a step type the node cannot execute for
 * license reasons, naming the feature; every other type registers as before.
 */
class StepAvailabilityTest {

  private static PipelineModel pipelineWith(String type) {
    return new PipelineModel(
      "p",
      "p",
      "s",
      List.of(new StepModel("s", type, StepRole.STEP_ROLE_OUTPUT, null)),
      Map.of(),
      null,
      false,
      null
    );
  }

  @Test
  void unlicensed_gated_step_fails_registration_with_the_feature_named() {
    var registry = new PipelineRegistry(new ModelRegistry());
    registry.setStepAvailability(type ->
      "gated_step".equals(type) ? Optional.of("com.example.gated") : Optional.empty()
    );

    assertThatThrownBy(() -> registry.register(pipelineWith("gated_step")))
      .isInstanceOf(UnlicensedStepException.class)
      .hasMessageContaining("com.example.gated")
      .hasMessageContaining("gated_step");
    assertThat(registry.get("p")).isEmpty();
  }

  @Test
  void available_types_register_and_the_default_availability_allows_everything() {
    var registry = new PipelineRegistry(new ModelRegistry());

    assertThat(registry.register(pipelineWith(StepTypes.BREAK))).isEqualTo("p");
  }
}
