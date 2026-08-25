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
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generic plugin-section pass-through: unmodeled top-level workspace sections reach a step
 * codec untyped, and only well-formed id-lists are recognised so an unrelated top-level key never
 * fails the load.
 */
class ExtraSectionsTest {

  @Test
  void id_bearing_list_becomes_a_named_section_keyed_by_id() {
    var extras = Map.<String, Object>of(
      "policies",
      List.of(Map.of("id", "office", "tools", Map.of()), Map.of("id", "admin", "tools", Map.of()))
    );

    var sections = YamlWorkspaceLoader.rawExtraSections(extras);

    assertThat(sections).containsOnlyKeys("policies");
    assertThat(sections.get("policies")).containsOnlyKeys("office", "admin");
    assertThat(sections.get("policies").get("office")).containsEntry("id", "office");
  }

  @Test
  void unrelated_top_level_keys_are_left_untouched_not_a_hard_failure() {
    var extras = Map.<String, Object>of(
      "notes", // a plain list of scalars
      List.of("a", "b"),
      "count", // a scalar
      3,
      "labels", // a list of maps WITHOUT an id
      List.of(Map.of("name", "x"))
    );

    var sections = YamlWorkspaceLoader.rawExtraSections(extras);

    assertThat(sections).isEmpty();
  }

  @Test
  void duplicate_ids_within_a_recognised_section_still_fail() {
    var extras = Map.<String, Object>of(
      "policies",
      List.of(Map.of("id", "dup"), Map.of("id", "dup"))
    );

    assertThatThrownBy(() -> YamlWorkspaceLoader.rawExtraSections(extras))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("duplicate workspace policies id: dup");
  }

  @Test
  void null_extras_is_empty() {
    assertThat(YamlWorkspaceLoader.rawExtraSections(null)).isEmpty();
  }
}
