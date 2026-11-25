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

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@code labels} key of a step config: the labels a step brings to a zero-shot
 * classifier, in the shapes {@code /v1/classify} takes per request.
 *
 * @author GraviteeSource Team
 */
public final class ClassifierLabels {

  /** The step config key. */
  public static final String KEY = "labels";

  private ClassifierLabels() {}

  /**
   * The labels of a step config block: each entry a name, or a map with a {@code name} and an
   * optional {@code description}. Absent or empty means none.
   *
   * @throws IllegalArgumentException naming the step when the value has another shape, a name is
   *                                  blank or a label is declared twice
   */
  public static List<ClassifyLabel> parse(String stepId, Map<String, Object> config) {
    Object raw = config == null ? null : config.get(KEY);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> entries)) {
      throw invalid(stepId, "labels must be a list of names or {name, description} objects");
    }
    List<ClassifyLabel> labels = new ArrayList<>(entries.size());
    Set<String> names = new HashSet<>();
    for (int i = 0; i < entries.size(); i++) {
      ClassifyLabel label = label(stepId, i, entries.get(i));
      if (!names.add(label.name())) {
        throw invalid(stepId, "label '" + label.name() + "' is declared twice");
      }
      labels.add(label);
    }
    return List.copyOf(labels);
  }

  private static ClassifyLabel label(String stepId, int index, Object entry) {
    String where = "labels[" + index + "]";
    return switch (entry) {
      case String name -> new ClassifyLabel(requireName(stepId, where, name), "");
      case Map<?, ?> map -> {
        Object name = map.get("name");
        Object description = map.get("description");
        if (!(name instanceof String text)) {
          throw invalid(stepId, where + " needs a name");
        }
        if (description != null && !(description instanceof String)) {
          throw invalid(stepId, where + " has a description that is not text");
        }
        yield new ClassifyLabel(
          requireName(stepId, where, text),
          description == null ? "" : (String) description
        );
      }
      case null, default -> throw invalid(
        stepId,
        where + " must be a name or a {name, description} object"
      );
    };
  }

  private static String requireName(String stepId, String where, String name) {
    if (name.isBlank()) {
      throw invalid(stepId, where + " has a blank name");
    }
    return name;
  }

  private static IllegalArgumentException invalid(String stepId, String message) {
    return new IllegalArgumentException("Step '" + stepId + "': " + message);
  }

  /**
   * Fails unless {@code label} is one of {@code labels}; nothing to check when the step brings
   * none.
   *
   * @param what what names the label, for the message (a rule, a trigger)
   * @throws IllegalArgumentException naming the step, the label and the step's labels
   */
  public static void requireAmong(
    String stepId,
    String what,
    String label,
    List<ClassifyLabel> labels
  ) {
    if (labels.isEmpty() || labels.stream().anyMatch(l -> l.name().equals(label))) {
      return;
    }
    throw invalid(
      stepId,
      what +
        " label '" +
        label +
        "' is not among the step's labels " +
        labels.stream().map(ClassifyLabel::name).toList()
    );
  }
}
