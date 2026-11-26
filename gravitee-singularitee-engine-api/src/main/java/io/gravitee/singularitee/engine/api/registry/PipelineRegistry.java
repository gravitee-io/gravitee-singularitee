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
package io.gravitee.singularitee.engine.api.registry;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.Modalities;
import io.gravitee.singularitee.engine.api.pipeline.model.ClassifierLabelsConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.PipelineStatus;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory registry of published pipeline definitions.
 *
 * <p>Pipelines are loaded at startup from the workspace YAML. The registry
 * validates that every model reference in a pipeline resolves against the
 * {@link ModelRegistry} at register time, and that a step bringing its own labels
 * names a model that takes them, and serves look-ups thereafter. The
 * whole set can be replaced at once ({@link #prepareReplacement} then
 * {@link #replaceAll}), which is how a synced node applies a release that changes
 * only pipelines; a run already started keeps the pipeline object it began with.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class PipelineRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineRegistry.class);

  private final ModelRegistry modelRegistry;
  private volatile ConcurrentHashMap<String, PipelineEntry> pipelines = new ConcurrentHashMap<>();
  private volatile StepAvailability stepAvailability = StepAvailability.ALL_AVAILABLE;

  /** Creates a registry that resolves step models through {@code modelRegistry}. */
  public PipelineRegistry(ModelRegistry modelRegistry) {
    this.modelRegistry = modelRegistry;
  }

  /** Installs the license-aware availability check applied to every registration. */
  public void setStepAvailability(StepAvailability stepAvailability) {
    this.stepAvailability = stepAvailability == null
      ? StepAvailability.ALL_AVAILABLE
      : stepAvailability;
  }

  // ---------------------------------------------------------------------------
  // Register
  // ---------------------------------------------------------------------------

  /**
   * Registers a pipeline definition after validating all model references.
   *
   * <p>The pipeline's {@code id} is used as the stable ID when non-blank; otherwise
   * the server generates a UUID. If the resolved ID is already in use, an
   * {@link IllegalArgumentException} is thrown.
   *
   * @param pipeline the pipeline definition (may carry a caller-supplied id)
   * @return the final pipeline id used (caller's value or generated UUID)
   * @throws IllegalArgumentException if the id is already in use, or a referenced
   *                                   model_id is not active
   */
  public String register(PipelineModel pipeline) {
    PipelineModel published = published(pipeline);
    String resolvedId = published.id();
    if (pipelines.putIfAbsent(resolvedId, activeEntry(published)) != null) {
      throw new IllegalArgumentException("pipeline_id already in use: " + resolvedId);
    }

    LOGGER.info("Pipeline published: id={}, name={}", resolvedId, pipeline.name());
    return resolvedId;
  }

  // ---------------------------------------------------------------------------
  // Replace
  // ---------------------------------------------------------------------------

  /**
   * Validates a complete pipeline set as {@link #register} validates each pipeline, without
   * publishing anything.
   *
   * @throws IllegalArgumentException as {@link #register} does, or when two pipelines share an id
   */
  public Replacement prepareReplacement(List<PipelineModel> replacement) {
    return prepare(replacement, false);
  }

  /**
   * As {@link #prepareReplacement}, but a pipeline that fails validation is left out and listed in
   * {@link Replacement#skipped()}, as a workspace load that is not strict skips it. An unlicensed
   * step still fails the whole set, as it fails a workspace load.
   *
   * @throws UnlicensedStepException when a pipeline declares a step whose license feature is missing
   */
  public Replacement prepareLenientReplacement(List<PipelineModel> replacement) {
    return prepare(replacement, true);
  }

  private Replacement prepare(List<PipelineModel> replacement, boolean lenient) {
    Map<String, PipelineEntry> entries = new LinkedHashMap<>();
    Map<String, String> skipped = new LinkedHashMap<>();
    for (PipelineModel pipeline : replacement) {
      PipelineModel published;
      try {
        published = published(pipeline);
      } catch (UnlicensedStepException e) {
        throw e;
      } catch (RuntimeException e) {
        if (!lenient) {
          throw e;
        }
        skipped.putIfAbsent(label(pipeline), e.getMessage());
        continue;
      }
      if (entries.putIfAbsent(published.id(), activeEntry(published)) != null) {
        String duplicate = "pipeline_id declared twice: " + published.id();
        if (!lenient) {
          throw new IllegalArgumentException(duplicate);
        }
        skipped.putIfAbsent(published.id(), duplicate);
      }
    }
    return new Replacement(entries, skipped);
  }

  private static String label(PipelineModel pipeline) {
    return pipeline.id() != null && !pipeline.id().isBlank()
      ? pipeline.id()
      : String.valueOf(pipeline.name());
  }

  /**
   * Publishes a prepared set in place of every registered pipeline in one step: a lookup sees
   * either the old set or the new one, never a mix.
   */
  public void replaceAll(Replacement replacement) {
    pipelines = new ConcurrentHashMap<>(replacement.entries());
    LOGGER.info("Pipelines replaced: {}", replacement.ids());
  }

  /**
   * A validated pipeline set, ready to replace the registered one.
   *
   * @param skipped why each pipeline a lenient preparation left out failed, keyed by pipeline id
   */
  public record Replacement(Map<String, PipelineEntry> entries, Map<String, String> skipped) {
    public Replacement {
      entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
      skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
    }

    public Replacement(Map<String, PipelineEntry> entries) {
      this(entries, Map.of());
    }

    /** The pipeline ids of the set, in declaration order. */
    public List<String> ids() {
      return List.copyOf(entries.keySet());
    }
  }

  private PipelineModel published(PipelineModel pipeline) {
    String resolvedId = pipeline.id() != null && !pipeline.id().isBlank()
      ? pipeline.id()
      : UUID.randomUUID().toString();

    validateStepAvailability(pipeline, resolvedId);
    validateModelReferences(pipeline);

    String task = pipeline.task() != null && !pipeline.task().isBlank()
      ? pipeline.task()
      : outputModel(pipeline).map(ModelRegistry.ModelEntry::task).orElse("");
    List<String> modalities = !pipeline.inputModalities().isEmpty()
      ? pipeline.inputModalities()
      : derivedModalities(pipeline);

    return new PipelineModel(
      resolvedId,
      pipeline.name(),
      pipeline.entryStepId(),
      pipeline.steps(),
      pipeline.edges(),
      task,
      pipeline.hidden(),
      modalities
    );
  }

  private static PipelineEntry activeEntry(PipelineModel published) {
    return new PipelineEntry(
      published,
      PipelineStatus.PIPELINE_STATUS_ACTIVE,
      new AtomicInteger(0)
    );
  }

  // ---------------------------------------------------------------------------
  // Get
  // ---------------------------------------------------------------------------

  /** Looks up a registered pipeline by id. */
  public Optional<PipelineEntry> get(String pipelineId) {
    return Optional.ofNullable(pipelines.get(pipelineId));
  }

  // ---------------------------------------------------------------------------
  // List
  // ---------------------------------------------------------------------------

  /** Returns a snapshot of every registered pipeline. */
  public List<PipelineEntry> list() {
    return List.copyOf(pipelines.values());
  }

  /** Returns the live id-to-entry view of the registry. */
  public Set<Map.Entry<String, PipelineEntry>> entries() {
    return pipelines.entrySet();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves the model behind a pipeline's output step, the one that decides what
   * the pipeline is, i.e. its task.
   *
   * <p>A pipeline's public surface is the surface of whatever produces its answer:
   * a pipeline ending in a text-gen model is a text-generation endpoint no matter
   * how many guards and routers precede it. So the derivation asks the engine
   * rather than the step type: only the engine can tell sequence-level
   * classification from token-level.
   *
   * <p>Falls back to the entry step when no step claims {@code role: output}, and
   * to nothing when the step names no model. Empty is the honest answer: a caller
   * that cannot tell which endpoint a pipeline belongs on is better served by no
   * label than by a guess.
   */
  private Optional<ModelRegistry.ModelEntry> outputModel(PipelineModel pipeline) {
    var outputStep = pipeline
      .steps()
      .stream()
      .filter(step -> step.role() == StepRole.STEP_ROLE_OUTPUT)
      .reduce((first, second) -> second)
      .or(() -> pipeline.step(pipeline.entryStepId()));

    return outputStep
      .map(PipelineRegistry::extractModelId)
      .filter(id -> id != null && !id.isBlank())
      .flatMap(modelRegistry::get);
  }

  /**
   * Derives what a pipeline accepts as input: the union of what every model-bound
   * step accepts.
   *
   * <p>Unlike the task, this is not a property of the output step. Media rides on
   * the request's messages and reaches whichever step feeds those messages to a
   * model: a caption-then-polish pipeline decodes its image in the entry step and
   * answers from a text-only model. A union rather than an intersection because
   * that is how media flows: a text-only guard in front of a vision model does not
   * stop the image reaching the model that can read it.
   *
   * <p>Text-only when no step names a model, which is all such a pipeline can read.
   */
  private List<String> derivedModalities(PipelineModel pipeline) {
    var vision = false;
    var audio = false;
    for (var step : pipeline.steps()) {
      String modelId = extractModelId(step);
      if (modelId == null || modelId.isBlank()) continue;
      var entry = modelRegistry.get(modelId);
      if (entry.isEmpty()) continue;
      vision |= entry.get().accepts(Modalities.IMAGE);
      audio |= entry.get().accepts(Modalities.AUDIO);
    }
    return Modalities.of(vision, audio);
  }

  /**
   * A gated step type without a licensed executor fails the registration, naming the
   * feature. Checked before anything else: it is the error an operator must see first.
   */
  private void validateStepAvailability(PipelineModel pipeline, String pipelineId) {
    for (var step : pipeline.steps()) {
      var missing = stepAvailability.missingLicenseFeature(step.type());
      if (missing.isPresent()) {
        throw new UnlicensedStepException(pipelineId, step.id(), step.type(), missing.get());
      }
    }
  }

  private void validateModelReferences(PipelineModel pipeline) {
    for (var step : pipeline.steps()) {
      String modelId = extractModelId(step);
      if (modelId != null && !modelId.isBlank()) {
        if (modelRegistry.get(modelId).isEmpty()) {
          throw new IllegalArgumentException(
            "Pipeline references unknown model_id: " + modelId + " in step " + step.id()
          );
        }
      }
      validateStepLabels(step, modelId);
    }
  }

  /** Labels a step brings replace the model's, which only a model taking request labels honours. */
  private void validateStepLabels(StepModel step, String modelId) {
    if (!(step.config() instanceof ClassifierLabelsConfig c) || c.labels().isEmpty()) {
      return;
    }
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException(
        "Step '" + step.id() + "' sets labels but names no model to classify with"
      );
    }
    var engine = modelRegistry.get(modelId).map(ModelRegistry.ModelEntry::engine).orElse(null);
    if (!(engine instanceof ClassifierEngine classifier && classifier.acceptsRequestLabels())) {
      throw new IllegalArgumentException(
        "Step '" +
          step.id() +
          "' sets labels, but model '" +
          modelId +
          "' cannot take request labels; labels need a zero-shot classifier " +
          "(gliner_classifier, gliner_ner or a decision model published as a classifier)"
      );
    }
  }

  private static String extractModelId(StepModel step) {
    return switch (step.config()) {
      case ModelBoundConfig c -> c.modelId();
      case null, default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // PipelineEntry record
  // ---------------------------------------------------------------------------

  /** A registered pipeline with its lifecycle status and in-flight request counter. */
  public record PipelineEntry(
    PipelineModel pipeline,
    PipelineStatus status,
    AtomicInteger inFlightCount
  ) {}
}
