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

import io.gravitee.singularitee.pipeline.evaluator.BreakStepEvaluator;
import io.gravitee.singularitee.protocol.BreakStepConfig;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.reactivex.rxjava3.core.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a BREAK step: evaluates a condition and halts the pipeline if met.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class BreakStepExecutor implements StepExecutor<BreakStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(BreakStepExecutor.class);

  @Override
  public BreakStepConfig extractConfig(PipelineStep step) {
    return step.getBreakConfig();
  }

  @Override
  public Maybe<String> execute(String stepId, BreakStepConfig cfg, StepContext ctx) {
    boolean shouldBreak = BreakStepEvaluator.evaluate(cfg, ctx.pipelineContext());

    if (shouldBreak) {
      LOGGER.debug("BreakStep '{}': condition met, halting pipeline", stepId);
      return Maybe.empty();
    }

    return ctx.rxNextStep(stepId);
  }
}
