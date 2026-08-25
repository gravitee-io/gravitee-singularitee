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
package io.gravitee.singularitee.engine.pipeline.executor;

import io.gravitee.singularitee.engine.api.pipeline.TodoSessionStore;
import io.gravitee.singularitee.engine.api.pipeline.executor.PipelineExecutorCallback;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.executor.StreamRegistry;
import io.gravitee.singularitee.engine.api.pipeline.executor.SubPipelineCallbacks;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.pipeline.executor.WarmupAware;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.reactivex.rxjava3.core.Completable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating and configuring step executors.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StepExecutorFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(StepExecutorFactory.class);

  private final StepExecutionContext execContext;
  private final TemplateRenderer templateRenderer;
  private final TodoSessionStore todoSessionStore;
  private final SubPipelineCallbacks subPipelineCallbacks = new SubPipelineCallbacks();
  /**
   * Starts empty: every step executor arrives through the plugin assembly
   * ({@link #addExecutors}). A dispatcher is only created once the core vocabulary is covered.
   */
  private final Map<String, StepExecutor<?>> handlers = new LinkedHashMap<>();
  private final List<StepExecutorDecorator> pluginDecorators = new ArrayList<>();

  /**
   * Creates a new factory and initializes all standard step handlers.
   *
   * @param modelRegistry    the model registry
   * @param pipelineRegistry the pipeline registry
   * @param streamRegistry   the stream registry for token delivery
   * @param templateRenderer the shared template renderer used by every executor
   *                         that needs to evaluate a template string
   * @param pipelineCallback callback to execute sub-pipelines (can be null initially)
   */
  public StepExecutorFactory(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    StreamRegistry streamRegistry,
    TemplateRenderer templateRenderer,
    PipelineExecutorCallback pipelineCallback
  ) {
    this(modelRegistry, pipelineRegistry, streamRegistry, templateRenderer, pipelineCallback, null);
  }

  /**
   * @param todoSessionStore cross-request todo persistence, or {@code null} to disable
   *                         (client-side executor, tests)
   */
  public StepExecutorFactory(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    StreamRegistry streamRegistry,
    TemplateRenderer templateRenderer,
    PipelineExecutorCallback pipelineCallback,
    TodoSessionStore todoSessionStore
  ) {
    this.execContext = new StepExecutionContext(modelRegistry, pipelineRegistry, streamRegistry);
    this.templateRenderer = templateRenderer;
    this.todoSessionStore = todoSessionStore;
    if (pipelineCallback != null) {
      subPipelineCallbacks.register(pipelineCallback, Map.of());
    }
  }

  /** The shared executor context handed to step executors. */
  public StepExecutionContext executionContext() {
    return execContext;
  }

  /** The renderer shared by every executor that evaluates a template. */
  public TemplateRenderer templateRenderer() {
    return templateRenderer;
  }

  /** Cross-request todo persistence, or {@code null} when disabled. */
  public TodoSessionStore todoSessionStore() {
    return todoSessionStore;
  }

  /** The registry sub-pipeline steps execute through. */
  public SubPipelineCallbacks subPipelineCallbacks() {
    return subPipelineCallbacks;
  }

  /**
   * Adds executors supplied by plugins. A type registered twice is a misassembly (two
   * providers claiming one step type is not a merge to arbitrate) and fails.
   */
  public void addExecutors(Map<String, StepExecutor<?>> pluginHandlers) {
    for (var e : pluginHandlers.entrySet()) {
      if (handlers.putIfAbsent(e.getKey(), e.getValue()) != null) {
        throw new IllegalStateException(
          "Step type '" + e.getKey() + "' is provided twice; remove one of the providers"
        );
      }
    }
  }

  /** Adds plugin decorators, in order (first = outermost among plugin decorators). */
  public void addDecorators(List<StepExecutorDecorator> decorators) {
    pluginDecorators.addAll(decorators);
  }

  /** Whether an executor is registered for the step type. */
  public boolean supports(String type) {
    return handlers.containsKey(type);
  }

  /**
   * Returns the step dispatcher over every registered executor, inside the decorator
   * chain. Fails when a core step type has no executor: a server unable to run its own
   * vocabulary is misassembled, never an inert node.
   */
  public StepDispatcher createDispatcher() {
    var missing = new TreeSet<>(StepTypes.CORE);
    missing.removeAll(handlers.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
        "Core step types without an executor: " + missing + " (a core step plugin is missing)"
      );
    }
    LOGGER.info(
      "Creating step dispatcher with {} executor(s) and {} plugin decorator(s)",
      handlers.size(),
      pluginDecorators.size()
    );
    return new StepDispatcher(handlers, pluginDecorators);
  }

  /**
   * Registers the sub-pipeline executors once the pipeline executor exists.
   *
   * @param localCallback   callback for locally-registered pipelines
   * @param remoteCallbacks callbacks for remote pipelines, keyed by remote server ID
   */
  public void setSubPipelineCallbacks(
    PipelineExecutorCallback localCallback,
    Map<String, PipelineExecutorCallback> remoteCallbacks
  ) {
    subPipelineCallbacks.register(localCallback, remoteCallbacks);
    LOGGER.info("Sub-pipeline callbacks registered");
  }

  /**
   * Pre-computes KNN reference embeddings reactively for all route steps in the given pipeline.
   *
   * @param pipeline the pipeline whose KNN route steps should be warmed up
   * @return a {@link Completable} that completes when all embeddings are ready
   */
  public Completable rxWarmupEmbeddings(PipelineModel pipeline) {
    var warmups = handlers
      .values()
      .stream()
      .filter(WarmupAware.class::isInstance)
      .map(WarmupAware.class::cast)
      .map(w -> w.rxWarmup(pipeline))
      .toList();
    return warmups.isEmpty() ? Completable.complete() : Completable.merge(warmups);
  }
}
