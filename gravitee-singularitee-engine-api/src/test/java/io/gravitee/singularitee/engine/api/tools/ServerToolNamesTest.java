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
package io.gravitee.singularitee.engine.api.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Display names are a value: defaults are identity, overrides map both ways, and the
 * definitions carry the display name while the canonical constants never move.
 */
class ServerToolNamesTest {

  @Test
  void defaults_are_identity() {
    var names = ServerToolNames.DEFAULTS;
    assertThat(names.display(TodoTools.ASK_USER)).isEqualTo(TodoTools.ASK_USER);
    assertThat(names.canonical("anything")).isEqualTo("anything");
    assertThat(names.serverToolNames()).isEqualTo(TodoTools.NAMES);
    assertThat(names.delegableToolNames()).isEqualTo(TodoTools.DELEGABLE);
    assertThat(ServerToolNames.of(Map.of())).isSameAs(names);
  }

  @Test
  void overrides_map_both_ways_and_reach_the_definitions() {
    var names = ServerToolNames.of(Map.of(TodoTools.ASK_USER, " ask_human "));

    assertThat(names.display(TodoTools.ASK_USER)).isEqualTo("ask_human");
    assertThat(names.canonical("ask_human")).isEqualTo(TodoTools.ASK_USER);
    assertThat(names.canonical(TodoTools.ASK_USER)).isEqualTo(TodoTools.ASK_USER);
    assertThat(names.delegableToolNames()).containsExactly("ask_human");
    assertThat(names.serverToolNames()).containsExactlyInAnyOrder(
      TodoTools.SET_TODOS,
      TodoTools.COMPLETE_TODO,
      "ask_human"
    );
    assertThat(TodoTools.definitions(names))
      .extracting(d -> d.getName())
      .containsExactly(TodoTools.SET_TODOS, TodoTools.COMPLETE_TODO, "ask_human");
    assertThat(ServerToolNames.DEFAULTS.display(TodoTools.ASK_USER)).isEqualTo(TodoTools.ASK_USER);
  }

  @Test
  void invalid_overrides_are_rejected() {
    assertThatThrownBy(() -> ServerToolNames.of(Map.of("nope", "x")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Unknown server tool");
    assertThatThrownBy(() -> ServerToolNames.of(Map.of(TodoTools.ASK_USER, " ")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Blank");
    assertThatThrownBy(() -> ServerToolNames.of(Map.of(TodoTools.ASK_USER, TodoTools.SET_TODOS)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("distinct");
  }
}
