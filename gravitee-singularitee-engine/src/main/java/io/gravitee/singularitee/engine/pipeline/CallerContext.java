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
package io.gravitee.singularitee.engine.pipeline;

import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.model.OutputFieldConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps a caller's {@code context} map out of the keys a pipeline writes itself.
 *
 * <p>Steps, guards and routes read flat context keys. A caller that pre-sets one of them
 * (a step output a later {@code route} reads, an internal {@code __} flag, the todo
 * counters) could steer the pipeline before any step ran. A caller key is dropped when its
 * top-level segment is a step id, the top-level segment of a step's {@code output_field},
 * or a root the engine owns; everything else passes through unchanged.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class CallerContext {

  /** Roots the engine writes itself, independent of the pipeline's steps. */
  static final Set<String> ENGINE_ROOTS = Set.of(
    PipelineContext.KEY_PROMPT,
    root(PipelineContext.KEY_REPEATED_CALL),
    root(PipelineContext.KEY_TODOS_TOTAL)
  );

  private CallerContext() {}

  /** The request with every reserved context key removed, or the request itself if none is. */
  public static InferPipelineRequest withoutReservedKeys(
    PipelineModel pipeline,
    InferPipelineRequest request
  ) {
    var dropped = reservedKeys(pipeline, request.getContextMap().keySet());
    if (dropped.isEmpty()) {
      return request;
    }
    var builder = request.toBuilder();
    dropped.forEach(builder::removeContext);
    return builder.build();
  }

  /** The keys among {@code keys} a caller may not set for {@code pipeline}. */
  static List<String> reservedKeys(PipelineModel pipeline, Set<String> keys) {
    var roots = new HashSet<>(ENGINE_ROOTS);
    for (StepModel step : pipeline.steps()) {
      roots.add(step.id());
      if (
        step.config() instanceof OutputFieldConfig out &&
        out.outputField() != null &&
        !out.outputField().isBlank()
      ) {
        roots.add(root(out.outputField()));
      }
    }
    return keys
      .stream()
      .filter(k -> k.startsWith("__") || roots.contains(root(k)))
      .sorted()
      .toList();
  }

  private static String root(String key) {
    int dot = key.indexOf('.');
    return dot < 0 ? key : key.substring(0, dot);
  }
}
