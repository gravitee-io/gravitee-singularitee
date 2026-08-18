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

import static io.gravitee.singularitee.protocol.StepType.*;
import static io.gravitee.singularitee.protocol.StepType.STEP_TYPE_GUARD;
import static io.gravitee.singularitee.protocol.StepType.STEP_TYPE_INFER;
import static io.gravitee.singularitee.protocol.StepType.STEP_TYPE_LOOP;
import static io.gravitee.singularitee.protocol.StepType.STEP_TYPE_REGEX_GUARD;
import static io.gravitee.singularitee.protocol.StepType.STEP_TYPE_ROUTE;

import io.gravitee.singularitee.pipeline.TodoSessionStore;
import io.gravitee.singularitee.protocol.Pipeline;
import io.gravitee.singularitee.protocol.StepType;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.reactivex.rxjava3.core.Completable;
import java.util.EnumMap;
import java.util.Map;
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
  private final JinjaRenderer jinjaRenderer;
  private final TodoSessionStore todoSessionStore;
  private final Map<StepType, StepExecutor<?>> handlers;
  private SubPipelineStepExecutor subPipelineExecutor;

  /**
   * Creates a new factory and initializes all standard step handlers.
   *
   * @param modelRegistry    the model registry
   * @param pipelineRegistry the pipeline registry
   * @param streamRegistry   the stream registry for token delivery
   * @param jinjaRenderer    the shared Jinja2 renderer used by every executor
   *                         that needs to evaluate a template string
   * @param pipelineCallback callback to execute sub-pipelines (can be null initially)
   */
  public StepExecutorFactory(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    StreamRegistry streamRegistry,
    JinjaRenderer jinjaRenderer,
    SubPipelineStepExecutor.PipelineExecutorCallback pipelineCallback
  ) {
    this(modelRegistry, pipelineRegistry, streamRegistry, jinjaRenderer, pipelineCallback, null);
  }

  /**
   * @param todoSessionStore cross-request todo persistence, or {@code null} to disable
   *                         (client-side executor, tests)
   */
  public StepExecutorFactory(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    StreamRegistry streamRegistry,
    JinjaRenderer jinjaRenderer,
    SubPipelineStepExecutor.PipelineExecutorCallback pipelineCallback,
    TodoSessionStore todoSessionStore
  ) {
    this.execContext = new StepExecutionContext(modelRegistry, pipelineRegistry, streamRegistry);
    this.jinjaRenderer = jinjaRenderer;
    this.todoSessionStore = todoSessionStore;
    this.handlers = createHandlers(pipelineCallback);
  }

  /**
   * Returns the step dispatcher configured with all registered handlers.
   */
  public StepDispatcher createDispatcher() {
    LOGGER.info("Creating step dispatcher with {} handlers", handlers.size());
    return new StepDispatcher(handlers);
  }

  /**
   * Sets the sub-pipeline executor callbacks after PipelineExecutor is created.
   * This avoids circular dependency issues.
   *
   * @param localCallback   callback for locally-registered pipelines
   * @param remoteCallbacks callbacks for remote pipelines, keyed by remote server ID
   */
  public void setSubPipelineCallbacks(
    SubPipelineStepExecutor.PipelineExecutorCallback localCallback,
    Map<String, SubPipelineStepExecutor.PipelineExecutorCallback> remoteCallbacks
  ) {
    if (subPipelineExecutor != null) {
      subPipelineExecutor.setCallbacks(localCallback, remoteCallbacks);
      LOGGER.info("Sub-pipeline callbacks injected");
    }
  }

  /**
   * Pre-computes KNN reference embeddings reactively for all route steps in the given pipeline.
   *
   * @param pipeline the pipeline whose KNN route steps should be warmed up
   * @return a {@link Completable} that completes when all embeddings are ready
   */
  public Completable rxWarmupEmbeddings(Pipeline pipeline) {
    var route = handlers.get(STEP_TYPE_ROUTE);
    if (route instanceof RouteStepExecutor rse) {
      return rse.rxWarmupEmbeddings(pipeline);
    }
    return Completable.complete();
  }

  /**
   * Creates and initializes all standard step handlers.
   */
  private Map<StepType, StepExecutor<?>> createHandlers(
    SubPipelineStepExecutor.PipelineExecutorCallback pipelineCallback
  ) {
    var handlers = new EnumMap<StepType, StepExecutor<?>>(StepType.class);

    handlers.put(STEP_TYPE_CLASSIFY, new ClassifyStepExecutor(execContext));
    handlers.put(STEP_TYPE_EMBED, new EmbedStepExecutor(execContext));
    handlers.put(STEP_TYPE_BREAK, new BreakStepExecutor());
    handlers.put(STEP_TYPE_LOOP, new LoopStepExecutor(jinjaRenderer));
    handlers.put(STEP_TYPE_GUARD, new GuardStepExecutor(execContext, jinjaRenderer));
    handlers.put(
      STEP_TYPE_ROUTE,
      new RouteStepExecutor(
        execContext,
        todoSessionStore != null ? todoSessionStore.cacheManager() : null
      )
    );
    handlers.put(STEP_TYPE_INFER, new InferStepExecutor(execContext, jinjaRenderer));
    handlers.put(STEP_TYPE_LLM_GUARD, new LlmGuardStepExecutor(execContext, jinjaRenderer));
    handlers.put(STEP_TYPE_REGEX_GUARD, new RegexGuardStepExecutor(jinjaRenderer));
    handlers.put(STEP_TYPE_TOOL_SELECT, new ToolSelectStepExecutor(execContext, jinjaRenderer));
    handlers.put(STEP_TYPE_TODO, new TodoStepExecutor(todoSessionStore));

    subPipelineExecutor = new SubPipelineStepExecutor(execContext, pipelineCallback, null);
    handlers.put(StepType.STEP_TYPE_SUB_PIPELINE, subPipelineExecutor);

    LOGGER.info("Initialized {} step handlers: {}", handlers.size(), handlers.keySet());

    return handlers;
  }
}
