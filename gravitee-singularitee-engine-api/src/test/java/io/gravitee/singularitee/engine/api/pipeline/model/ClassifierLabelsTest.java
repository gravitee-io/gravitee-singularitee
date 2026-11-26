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
package io.gravitee.singularitee.engine.api.pipeline.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifierLabelsTest {

  private static Map<String, Object> labels(Object value) {
    Map<String, Object> config = new HashMap<>();
    config.put("labels", value);
    return config;
  }

  @Test
  void names_and_objects_with_a_description_are_labels() {
    var parsed = ClassifierLabels.parse(
      "topic",
      labels(List.of("billing", Map.of("name", "tech", "description", "A technical question.")))
    );

    assertThat(parsed).containsExactly(
      new ClassifyLabel("billing", ""),
      new ClassifyLabel("tech", "A technical question.")
    );
  }

  @Test
  void an_object_without_a_description_has_an_empty_one() {
    assertThat(
      ClassifierLabels.parse("topic", labels(List.of(Map.of("name", "sales"))))
    ).containsExactly(new ClassifyLabel("sales", ""));
  }

  @Test
  void absent_null_or_empty_labels_are_none() {
    assertThat(ClassifierLabels.parse("topic", Map.of())).isEmpty();
    assertThat(ClassifierLabels.parse("topic", labels(null))).isEmpty();
    assertThat(ClassifierLabels.parse("topic", labels(List.of()))).isEmpty();
  }

  @Test
  void a_value_that_is_not_a_list_is_refused_naming_the_step() {
    assertThatThrownBy(() -> ClassifierLabels.parse("topic", labels("billing")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("labels");
  }

  @Test
  void a_blank_or_missing_name_is_refused() {
    assertThatThrownBy(() -> ClassifierLabels.parse("topic", labels(List.of(" "))))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'");
    assertThatThrownBy(() ->
      ClassifierLabels.parse("topic", labels(List.of(Map.of("description", "no name"))))
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'");
  }

  @Test
  void an_entry_of_another_shape_is_refused() {
    assertThatThrownBy(() -> ClassifierLabels.parse("topic", labels(List.of(42))))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'");
  }

  @Test
  void a_label_declared_twice_is_refused() {
    assertThatThrownBy(() ->
      ClassifierLabels.parse("topic", labels(List.of("billing", Map.of("name", "billing"))))
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("'billing'");
  }

  @Test
  void a_label_among_the_step_labels_passes_and_another_is_refused() {
    var stepLabels = List.of(new ClassifyLabel("billing", ""), new ClassifyLabel("tech", ""));

    assertThatCode(() ->
      ClassifierLabels.requireAmong("route", "rule", "tech", stepLabels)
    ).doesNotThrowAnyException();
    assertThatThrownBy(() -> ClassifierLabels.requireAmong("route", "rule", "sales", stepLabels))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'route'")
      .hasMessageContaining("rule label 'sales'")
      .hasMessageContaining("[billing, tech]");
  }

  @Test
  void without_step_labels_any_label_passes() {
    assertThatCode(() ->
      ClassifierLabels.requireAmong("route", "rule", "anything", List.of())
    ).doesNotThrowAnyException();
  }
}
