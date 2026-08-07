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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared context and utilities available to all step executors.
 *
 * <p>Passed to each step executor on construction, allowing them to share
 * infrastructure without coupling to PipelineExecutor.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StepExecutionContext {

  private static final Logger LOGGER = LoggerFactory.getLogger(StepExecutionContext.class);

  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;
  private final StreamRegistry streamRegistry;

  public StepExecutionContext(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    StreamRegistry streamRegistry
  ) {
    this.modelRegistry = modelRegistry;
    this.pipelineRegistry = pipelineRegistry;
    this.streamRegistry = streamRegistry;
  }

  /**
   * Returns the pipeline registry.
   */
  public PipelineRegistry pipelineRegistry() {
    return pipelineRegistry;
  }

  /**
   * Returns the stream registry.
   */
  public StreamRegistry streamRegistry() {
    return streamRegistry;
  }

  /**
   * Looks up a model by ID from the registry, returning a {@link Maybe} that
   * emits the entry if found or completes empty if not registered.
   *
   * @param modelId the model ID
   * @return a {@link Maybe} emitting the model entry, or empty if not found
   */
  public Maybe<ModelRegistry.ModelEntry> rxLookupModel(String modelId) {
    return modelRegistry.rxGet(modelId);
  }

  /**
   * Looks up a model by ID from the registry.
   *
   * @param modelId the model ID
   * @return optional containing the model entry if found
   */
  public Optional<ModelRegistry.ModelEntry> lookupModel(String modelId) {
    return modelRegistry.get(modelId);
  }

  /**
   * Gets the value of a context field, using a default input field if blank.
   *
   * @param context the pipeline context
   * @param providedField the explicitly configured field name
   * @param defaultField the default field to use if provided is blank
   * @return the field value, or null if not found
   */
  public String getInputField(PipelineContext context, String providedField, String defaultField) {
    String fieldName = (providedField == null || providedField.isBlank())
      ? defaultField
      : providedField;
    return context.get(fieldName);
  }

  /**
   * Returns the output field name, using provided field if non-blank,
   * or the default suffix applied to step ID.
   *
   * @param providedField the explicitly configured output field
   * @param stepId the step ID (for default generation)
   * @param suffix the suffix to append (e.g., ".label", ".output")
   * @return the output field name to use
   */
  public String getOutputField(String providedField, String stepId, String suffix) {
    if (providedField != null && !providedField.isBlank()) {
      return providedField;
    }
    return stepId + suffix;
  }

  /**
   * Logs a model not found warning.
   *
   * @param stepId the step identifier
   * @param modelId the missing model ID
   */
  public void logModelNotFound(String stepId, String modelId) {
    LOGGER.warn("Step '{}': model '{}' not found", stepId, modelId);
  }

  /**
   * Logs a type mismatch warning.
   *
   * @param stepId the step identifier
   * @param modelId the model ID
   * @param expectedType the expected engine type
   * @param actualType the actual engine type
   */
  public void logTypeError(String stepId, String modelId, String expectedType, String actualType) {
    LOGGER.warn(
      "Step '{}': model '{}' is {} (expected {})",
      stepId,
      modelId,
      actualType,
      expectedType
    );
  }

  /**
   * Logs an empty input field warning.
   *
   * @param stepId the step identifier
   * @param fieldName the field that was empty
   */
  public void logEmptyField(String stepId, String fieldName) {
    LOGGER.warn("Step '{}': input field '{}' is empty", stepId, fieldName);
  }
}
