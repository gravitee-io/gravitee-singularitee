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

import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineReplacementTest {

  private record Bound(String modelId) implements ModelBoundConfig {}

  private static PipelineModel pipeline(String id, Object config) {
    return new PipelineModel(
      id,
      id,
      "s",
      List.of(new StepModel("s", "noop", StepRole.STEP_ROLE_OUTPUT, config)),
      Map.of(),
      null,
      false,
      null
    );
  }

  private static List<String> ids(PipelineRegistry registry) {
    return registry
      .list()
      .stream()
      .map(e -> e.pipeline().id())
      .toList();
  }

  @Test
  void a_replacement_publishes_the_new_set_and_drops_the_rest() {
    var registry = new PipelineRegistry(new ModelRegistry());
    registry.register(pipeline("kept", null));
    registry.register(pipeline("removed", null));
    var started = registry.get("kept").orElseThrow();

    registry.replaceAll(
      registry.prepareReplacement(List.of(pipeline("kept", "v2"), pipeline("added", null)))
    );

    assertThat(ids(registry)).containsExactlyInAnyOrder("kept", "added");
    assertThat(registry.get("kept").orElseThrow().pipeline().steps().get(0).config()).isEqualTo(
      "v2"
    );
    assertThat(started.pipeline().steps().get(0).config()).isNull();
  }

  @Test
  void preparing_a_set_with_one_invalid_pipeline_changes_nothing() {
    var registry = new PipelineRegistry(new ModelRegistry());
    registry.register(pipeline("old", null));
    var before = registry.get("old").orElseThrow();

    assertThatThrownBy(() ->
      registry.prepareReplacement(
        List.of(pipeline("good", null), pipeline("bad", new Bound("gone")))
      )
    ).hasMessageContaining("gone");

    assertThat(ids(registry)).containsExactly("old");
    assertThat(registry.get("old").orElseThrow()).isSameAs(before);
  }

  @Test
  void a_replacement_naming_one_id_twice_is_refused() {
    var registry = new PipelineRegistry(new ModelRegistry());

    assertThatThrownBy(() ->
      registry.prepareReplacement(List.of(pipeline("twice", null), pipeline("twice", null)))
    ).hasMessageContaining("twice");
  }

  @Test
  void a_prepared_set_is_not_served_until_it_replaces_the_current_one() {
    var registry = new PipelineRegistry(new ModelRegistry());
    registry.register(pipeline("old", null));

    var replacement = registry.prepareReplacement(List.of(pipeline("new", null)));

    assertThat(ids(registry)).containsExactly("old");
    assertThat(replacement.ids()).containsExactly("new");
  }
}
