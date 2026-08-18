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
package io.gravitee.singularitee.registry;

import io.gravitee.singularitee.protocol.Pipeline;
import io.gravitee.singularitee.protocol.PipelineStatus;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory registry of published pipeline definitions.
 *
 * <p>Pipelines are loaded once at startup from the workspace YAML and live for
 * the lifetime of the process. The registry only validates that every model
 * reference in a pipeline resolves against the {@link ModelRegistry} at
 * register time and serves look-ups thereafter. There is no runtime lifecycle
 * (no retirement, no updates) — the deployment is static.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class PipelineRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineRegistry.class);

  private final ModelRegistry modelRegistry;
  private final ConcurrentHashMap<String, PipelineEntry> pipelines = new ConcurrentHashMap<>();

  public PipelineRegistry(ModelRegistry modelRegistry) {
    this.modelRegistry = modelRegistry;
  }

  // ---------------------------------------------------------------------------
  // Register
  // ---------------------------------------------------------------------------

  /**
   * Registers a pipeline definition after validating all model references.
   *
   * <p>The pipeline's {@code pipeline_id} field is used as the stable ID when
   * non-blank; otherwise the server generates a UUID. If the resolved ID is
   * already in use, an {@link IllegalArgumentException} is thrown.
   *
   * @param pipeline the pipeline definition (may carry a caller-supplied pipeline_id)
   * @return the final pipeline_id used (caller's value or generated UUID)
   * @throws IllegalArgumentException if the pipeline_id is already in use, or a
   *                                   referenced model_id is not active
   */
  public String register(Pipeline pipeline) {
    validateModelReferences(pipeline);

    String resolvedId = !pipeline.getPipelineId().isBlank()
      ? pipeline.getPipelineId()
      : java.util.UUID.randomUUID().toString();

    Pipeline published = pipeline.getTask().isBlank()
      ? pipeline.toBuilder().setTask(deriveTask(pipeline)).build()
      : pipeline;

    if (
      pipelines.putIfAbsent(
        resolvedId,
        new PipelineEntry(published, PipelineStatus.PIPELINE_STATUS_ACTIVE, new AtomicInteger(0))
      ) !=
      null
    ) {
      throw new IllegalArgumentException("pipeline_id already in use: " + resolvedId);
    }

    LOGGER.info("Pipeline published: id={}, name={}", resolvedId, pipeline.getPipelineName());
    return resolvedId;
  }

  // ---------------------------------------------------------------------------
  // Get
  // ---------------------------------------------------------------------------

  public Optional<PipelineEntry> get(String pipelineId) {
    return Optional.ofNullable(pipelines.get(pipelineId));
  }

  // ---------------------------------------------------------------------------
  // List
  // ---------------------------------------------------------------------------

  public List<PipelineEntry> list() {
    return List.copyOf(pipelines.values());
  }

  public java.util.Set<java.util.Map.Entry<String, PipelineEntry>> entries() {
    return pipelines.entrySet();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Infers the task slug a pipeline exposes from the model behind its output step.
   *
   * <p>A pipeline's public surface is the surface of whatever produces its answer:
   * a pipeline ending in a text-gen model is a text-generation endpoint no matter
   * how many guards and routers precede it. So the derivation asks the engine
   * rather than the step type — only the engine can tell sequence-level
   * classification from token-level.
   *
   * <p>Falls back to the entry step when no step claims {@code role: output}, and
   * to an empty slug when nothing resolves. Empty is the honest answer: a caller
   * that cannot tell which endpoint a pipeline belongs on is better served by no
   * label than by a guess.
   */
  private String deriveTask(Pipeline pipeline) {
    var outputStep = pipeline
      .getStepsList()
      .stream()
      .filter(step -> step.getRole() == StepRole.STEP_ROLE_OUTPUT)
      .reduce((first, second) -> second)
      .or(() ->
        pipeline
          .getStepsList()
          .stream()
          .filter(step -> step.getStepId().equals(pipeline.getEntryStepId()))
          .findFirst()
      );

    return outputStep
      .map(PipelineRegistry::extractModelId)
      .filter(id -> id != null && !id.isBlank())
      .flatMap(id -> modelRegistry.get(id))
      .map(ModelRegistry.ModelEntry::task)
      .orElse("");
  }

  private void validateModelReferences(Pipeline pipeline) {
    for (var step : pipeline.getStepsList()) {
      String modelId = extractModelId(step);
      if (modelId != null && !modelId.isBlank()) {
        if (modelRegistry.get(modelId).isEmpty()) {
          throw new IllegalArgumentException(
            "Pipeline references unknown model_id: " + modelId + " in step " + step.getStepId()
          );
        }
      }
    }
  }

  private static String extractModelId(io.gravitee.singularitee.protocol.PipelineStep step) {
    return switch (step.getType()) {
      case STEP_TYPE_INFER -> step.getInferConfig().getModelId();
      case STEP_TYPE_CLASSIFY -> step.getClassifyConfig().getModelId();
      case STEP_TYPE_EMBED -> step.getEmbedConfig().getModelId();
      case STEP_TYPE_ROUTE -> step.getRouteConfig().getModelId();
      case STEP_TYPE_GUARD -> step.getGuardConfig().getModelId();
      case STEP_TYPE_LLM_GUARD -> step.getLlmGuardConfig().getModelId();
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // PipelineEntry record
  // ---------------------------------------------------------------------------

  public record PipelineEntry(
    Pipeline pipeline,
    PipelineStatus status,
    AtomicInteger inFlightCount
  ) {}
}
