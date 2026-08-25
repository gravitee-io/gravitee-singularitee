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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A pipeline DAG, server-internal. Built by the workspace loader from YAML and executed
 * by the engine; only discovery metadata (id, name, task, visibility, modalities) is
 * ever exposed over the wire.
 *
 * <p>{@code edges} holds the plain {@code next_step} edges (step id to next step id);
 * routing, loop and todo steps declare their own branch targets inside their configs.
 * Step order in {@code steps} has no execution meaning.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record PipelineModel(
  String id,
  String name,
  String entryStepId,
  List<StepModel> steps,
  Map<String, String> edges,
  String task,
  boolean hidden,
  List<String> inputModalities
) {
  public PipelineModel {
    steps = steps == null ? List.of() : List.copyOf(steps);
    edges = edges == null ? Map.of() : Map.copyOf(edges);
    inputModalities = inputModalities == null ? List.of() : List.copyOf(inputModalities);
  }

  /** The step with the given id, if declared. */
  public Optional<StepModel> step(String stepId) {
    return steps
      .stream()
      .filter(s -> s.id().equals(stepId))
      .findFirst();
  }
}
