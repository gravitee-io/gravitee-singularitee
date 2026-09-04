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
package io.gravitee.singularitee.plugin.breakstep;

import io.gravitee.singularitee.engine.api.pipeline.evaluator.BreakStepEvaluator;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.TracingOptions;
import io.gravitee.singularitee.protocol.FinishReason;
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
  public Maybe<String> execute(String stepId, BreakStepConfig cfg, StepContext ctx) {
    var pctx = ctx.pipelineContext();
    var cond = cfg.condition();
    boolean shouldBreak = cond != null && BreakStepEvaluator.evaluate(cond, pctx);

    // Span introspection: the condition, the field it read, the verdict, and the resulting branch.
    String inputField = cond != null ? cond.inputField() : "";
    String nextStep = shouldBreak ? "(halt)" : ctx.nextStep(stepId);
    var scribe = ctx
      .stepScribe()
      .set(SpanNames.key("break.condition"), cond != null ? String.valueOf(cond.kind()) : "")
      .set(SpanNames.key("break.input_field"), inputField)
      .set(SpanNames.key("break.condition_met"), shouldBreak)
      .set(SpanNames.key("break.decision"), shouldBreak ? "halt" : "continue")
      .set(SpanNames.key("break.next_step"), nextStep == null ? "" : nextStep);
    if (TracingOptions.verbose()) {
      String value = pctx.get(inputField);
      scribe.set(SpanNames.key("break.input_value"), value == null ? "" : value);
    }

    if (shouldBreak) {
      pctx.signalHalt(cfg.outputField(), FinishReason.FINISH_REASON_BREAK_CONDITION);
      LOGGER.debug("BreakStep '{}': condition met, halting pipeline", stepId);
      return Maybe.empty();
    }

    return ctx.rxNextStep(stepId);
  }
}
