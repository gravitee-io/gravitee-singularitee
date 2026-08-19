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
package io.gravitee.singularitee.http.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.singularitee.engine.Modalities;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.http.translation.EndpointType;
import io.gravitee.singularitee.http.translation.InferRequestBuilder;
import io.gravitee.singularitee.http.translation.PipelineRequestBuilder;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import java.util.Optional;

/**
 * Resolves the OpenAI {@code model} field to either a text-generation model ({@code Infer}) or a
 * pipeline ({@code InferPipeline}). Model ids and pipeline ids are separate namespaces; a
 * {@code pipeline:} prefix forces the pipeline lookup, otherwise a matching text-gen model wins and
 * a bare pipeline id is the fallback. An unresolved id yields {@link Optional#empty()} (→ 400
 * {@code model_not_found}).
 */
public final class ModelOrPipelineResolver {

  private static final String PIPELINE_PREFIX = "pipeline:";

  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;

  public ModelOrPipelineResolver(ModelRegistry modelRegistry, PipelineRegistry pipelineRegistry) {
    this.modelRegistry = modelRegistry;
    this.pipelineRegistry = pipelineRegistry;
  }

  /**
   * Resolves the {@code model} field of a request to a published model or pipeline.
   *
   * <p>Hidden models and pipelines resolve to nothing: they are internal building
   * blocks, reachable as pipeline dependencies but not as an endpoint of their own.
   * Callers turn the empty result into the same {@code model_not_found} they would
   * get for an id that was never declared — a hidden model does not announce its
   * own existence by answering differently.
   */
  public Optional<Resolution> resolve(String rawModel, JsonNode payload, EndpointType type) {
    if (rawModel == null || rawModel.isBlank()) {
      return Optional.empty();
    }
    JsonNode tools = payload.at("/tools");
    boolean hasTools = tools.isArray() && !tools.isEmpty();

    if (rawModel.startsWith(PIPELINE_PREFIX)) {
      String id = rawModel.substring(PIPELINE_PREFIX.length());
      return pipelineRegistry
        .get(id)
        .filter(p -> !p.pipeline().getHidden())
        .map(p ->
          pipelineResolution(id, payload, type, hasTools, p.pipeline().getInputModalitiesList())
        );
    }

    var model = modelRegistry.get(rawModel).filter(ModelRegistry.ModelEntry::visible);
    if (model.isPresent() && model.get().engine() instanceof TextGenEngine) {
      return Optional.of(
        new Resolution(
          false,
          InferRequestBuilder.build(rawModel, payload, type),
          null,
          rawModel,
          hasTools,
          model.get().inputModalities()
        )
      );
    }

    return pipelineRegistry
      .get(rawModel)
      .filter(p -> !p.pipeline().getHidden())
      .map(p ->
        pipelineResolution(rawModel, payload, type, hasTools, p.pipeline().getInputModalitiesList())
      );
  }

  private Resolution pipelineResolution(
    String id,
    JsonNode payload,
    EndpointType type,
    boolean hasTools,
    java.util.List<String> acceptedModalities
  ) {
    return new Resolution(
      true,
      null,
      PipelineRequestBuilder.build(id, payload, type),
      id,
      hasTools,
      acceptedModalities.isEmpty() ? Modalities.TEXT_ONLY : acceptedModalities
    );
  }

  /**
   * A resolved target: exactly one of {@code inferRequest} / {@code pipelineRequest} is set.
   *
   * <p>{@code acceptedModalities} is what the target will read — the model's own
   * answer, or for a pipeline the answer of the model behind its output step, since
   * that is what any attached media ends up being decoded by.
   */
  public record Resolution(
    boolean pipeline,
    InferRequest inferRequest,
    InferPipelineRequest pipelineRequest,
    String modelName,
    boolean hasTools,
    java.util.List<String> acceptedModalities
  ) {
    /** Returns {@code true} if the target accepts the given input modality. */
    public boolean accepts(String modality) {
      return acceptedModalities.contains(modality);
    }
  }
}
