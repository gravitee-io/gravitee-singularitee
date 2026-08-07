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

import io.gravitee.singularitee.protocol.PipelineStep;
import io.reactivex.rxjava3.core.Maybe;

/**
 * Reactive strategy interface for executing a specific step type in a pipeline.
 *
 * <p>Each step type (INFER, CLASSIFY, EMBED, …) implements this interface
 * with its own config type {@code C}. The execute method returns a {@link Maybe}
 * that emits the ID of the next step to execute, or completes empty for terminal
 * steps. This keeps the entire pipeline walk reactive without any blocking or
 * sentinel values.
 *
 * <p>Implementations are stateless and can be safely shared across requests.
 *
 * @param <C> the protobuf config type this executor works with
 *            (e.g. ClassifyStepConfig, InferStepConfig)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface StepExecutor<C> {
  /**
   * Extracts the typed config from the generic pipeline step.
   *
   * @param step the pipeline step (contains the oneof config)
   * @return the typed config for this executor
   */
  C extractConfig(PipelineStep step);

  /**
   * Executes the step reactively.
   *
   * @param stepId  the step identifier
   * @param config  the typed step configuration
   * @param ctx     everything the step needs from the outside world
   * @return a {@link Maybe} emitting the next step ID, or empty for terminal steps
   */
  Maybe<String> execute(String stepId, C config, StepContext ctx);
}
