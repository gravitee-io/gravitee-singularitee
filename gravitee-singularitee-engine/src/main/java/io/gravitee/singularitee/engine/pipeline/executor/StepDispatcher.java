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

import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepInvocation;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches step execution: looks up the {@link StepExecutor} for the step's type and
 * runs it inside the decorator chain.
 *
 * <p>The chain is the same for every executor, built-in or plugin-supplied: the platform
 * decorators (tracing, diagnostics) are outermost and fixed, then the plugin decorators
 * in their declared order, then the executor. Nothing a plugin executor does can bypass
 * a platform layer, because the layers are applied here, around it.
 *
 * <p>Returns a {@link Maybe} emitting the next step ID, allowing the
 * {@code PipelineExecutor} to build a fully reactive DAG walk via {@code flatMapCompletable}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class StepDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(StepDispatcher.class);

  private final Map<String, StepExecutor<?>> handlers;
  private final List<StepExecutorDecorator> chain;

  /** Creates a dispatcher over a copy of {@code handlers} with the platform chain only. */
  public StepDispatcher(Map<String, StepExecutor<?>> handlers) {
    this(handlers, List.of());
  }

  /**
   * Creates a dispatcher over a copy of {@code handlers}; {@code pluginDecorators} run
   * inside the platform decorators, in list order (first = outermost among plugins).
   */
  public StepDispatcher(
    Map<String, StepExecutor<?>> handlers,
    List<StepExecutorDecorator> pluginDecorators
  ) {
    this.handlers = new LinkedHashMap<>(handlers);
    var layers = new ArrayList<StepExecutorDecorator>();
    layers.add(new TracingStepDecorator());
    layers.add(new DiagnosticsStepDecorator());
    layers.addAll(pluginDecorators);
    this.chain = List.copyOf(layers);
  }

  /** Whether an executor is registered for the step type. */
  public boolean supports(String type) {
    return handlers.containsKey(type);
  }

  /**
   * Dispatches a pipeline step through the decorator chain to its executor.
   *
   * @param step the step model
   * @param ctx  the step context
   * @return a {@link Maybe} emitting the next step ID, or empty for terminal steps
   */
  public Maybe<String> dispatch(StepModel step, StepContext ctx) {
    StepExecutor<?> handler = handlers.get(step.type());
    if (handler == null) {
      LOGGER.warn(
        "No executor registered for step type '{}', skipping '{}'",
        step.type(),
        step.id()
      );
      return ctx.rxNextStep(step.id());
    }
    StepContext stepCtx = ctx.withStep(step);

    StepInvocation invocation = (s, c) -> execute(handler, s, c);
    for (int i = chain.size() - 1; i >= 0; i--) {
      final StepExecutorDecorator layer = chain.get(i);
      final StepInvocation inner = invocation;
      invocation = (s, c) -> layer.around(s, c, inner);
    }

    return invocation
      .proceed(step, stepCtx)
      .doOnError(e ->
        LOGGER.error("Step '{}' (type={}) failed: {}", step.id(), step.type(), e.getMessage(), e)
      )
      .onErrorComplete(); // terminal on unhandled error
  }

  /** Type-safe bridge: extracts config with the concrete type, then calls execute. */
  private static <C> Maybe<String> execute(
    StepExecutor<C> handler,
    StepModel step,
    StepContext ctx
  ) {
    C config = handler.extractConfig(step);
    return handler.execute(step.id(), config, ctx);
  }
}
