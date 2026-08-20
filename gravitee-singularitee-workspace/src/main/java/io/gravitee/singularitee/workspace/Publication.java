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

import java.util.List;
import java.util.Set;

/**
 * Validates the publication metadata a workspace entry may declare — {@code task:}
 * and {@code modalities:} — against the closed sets the wire and the OpenAPI schema
 * promise.
 *
 * <p>Both are free-form strings in YAML, and the loader is the only place that sees
 * them. A typo admitted here boots a healthy server whose behaviour nobody can
 * explain: a modality slug no check recognises has every media request refused,
 * and a task slug the schema forbids is published verbatim as {@code type}. So a
 * bad value fails the workspace loudly, the way a bad step id already does.
 *
 * <p>The slugs mirror the engine's {@code ModelTasks} and {@code Modalities}
 * constants, which this module cannot depend on; the two lists must move together.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class Publication {

  /** Task slugs an entry may be advertised under. */
  public static final Set<String> TASKS = Set.of(
    "text-generation",
    "text-classification",
    "token-classification",
    "feature-extraction",
    "reranking"
  );

  /** Input modality slugs an entry may declare. */
  public static final Set<String> MODALITIES = Set.of("text", "image", "audio");

  private Publication() {}

  /**
   * Returns the declared task, or blank when unset so the engine's own answer stands.
   *
   * @throws IllegalArgumentException if the value is not one of {@link #TASKS}
   */
  public static String validatedTask(String entryId, String task) {
    if (task == null || task.isBlank()) return "";
    if (!TASKS.contains(task)) {
      throw new IllegalArgumentException(
        "'" + entryId + "' declares unknown task '" + task + "' — expected one of " + sorted(TASKS)
      );
    }
    return task;
  }

  /**
   * Returns the declared modalities, or an empty list when unset so detection runs.
   *
   * @throws IllegalArgumentException if any value is not one of {@link #MODALITIES}
   */
  public static List<String> validatedModalities(String entryId, List<String> modalities) {
    if (modalities == null) return List.of();
    for (String modality : modalities) {
      if (modality == null || !MODALITIES.contains(modality)) {
        throw new IllegalArgumentException(
          "'" +
            entryId +
            "' declares unknown modality '" +
            modality +
            "' — expected a subset of " +
            sorted(MODALITIES)
        );
      }
    }
    return List.copyOf(modalities);
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }
}
