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
import io.gravitee.llmbridge4j.core.or.v1.model.LlmRequest;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.http.translation.CanonicalChatRequestMapper;
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

  public Optional<Resolution> resolve(String rawModel, JsonNode payload, EndpointType type) {
    if (rawModel == null || rawModel.isBlank()) {
      return Optional.empty();
    }
    JsonNode tools = payload.at("/tools");
    boolean hasTools = tools.isArray() && !tools.isEmpty();

    if (rawModel.startsWith(PIPELINE_PREFIX)) {
      String id = rawModel.substring(PIPELINE_PREFIX.length());
      return pipelineRegistry.get(id).map(p -> pipelineResolution(id, payload, type, hasTools));
    }

    var model = modelRegistry.get(rawModel);
    if (model.isPresent() && model.get().engine() instanceof TextGenEngine) {
      return Optional.of(
        new Resolution(
          false,
          InferRequestBuilder.build(rawModel, payload, type),
          null,
          rawModel,
          hasTools
        )
      );
    }

    return pipelineRegistry
      .get(rawModel)
      .map(p -> pipelineResolution(rawModel, payload, type, hasTools));
  }

  /** Resolves Chat Completions using a request already canonicalized by LlmBridge4j. */
  public Optional<Resolution> resolve(String rawModel, LlmRequest request) {
    if (rawModel == null || rawModel.isBlank()) {
      return Optional.empty();
    }
    boolean hasTools = request.tools() != null && !request.tools().isEmpty();
    if (rawModel.startsWith(PIPELINE_PREFIX)) {
      String id = rawModel.substring(PIPELINE_PREFIX.length());
      return pipelineRegistry.get(id).map(p -> canonicalPipelineResolution(id, request, hasTools));
    }
    var model = modelRegistry.get(rawModel);
    if (model.isPresent() && model.get().engine() instanceof TextGenEngine) {
      return Optional.of(
        new Resolution(
          false,
          CanonicalChatRequestMapper.toDirect(rawModel, request),
          null,
          rawModel,
          hasTools
        )
      );
    }
    return pipelineRegistry
      .get(rawModel)
      .map(p -> canonicalPipelineResolution(rawModel, request, hasTools));
  }

  private Resolution canonicalPipelineResolution(String id, LlmRequest request, boolean hasTools) {
    return new Resolution(
      true,
      null,
      CanonicalChatRequestMapper.toPipeline(id, request),
      id,
      hasTools
    );
  }

  private Resolution pipelineResolution(
    String id,
    JsonNode payload,
    EndpointType type,
    boolean hasTools
  ) {
    return new Resolution(
      true,
      null,
      PipelineRequestBuilder.build(id, payload, type),
      id,
      hasTools
    );
  }

  /** A resolved target: exactly one of {@code inferRequest} / {@code pipelineRequest} is set. */
  public record Resolution(
    boolean pipeline,
    InferRequest inferRequest,
    InferPipelineRequest pipelineRequest,
    String modelName,
    boolean hasTools
  ) {}
}
