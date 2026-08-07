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
package io.gravitee.singularitee.pipeline.evaluator;

import static io.gravitee.singularitee.pipeline.evaluator.ConditionEvaluatorFactory.*;

import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.BreakCondition;
import io.gravitee.singularitee.protocol.BreakStepConfig;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.LoopStepConfig;

/**
 * Evaluates break and loop step conditions using type-safe evaluators.
 *
 * <p>This class provides the high-level API for condition evaluation.
 * The actual condition logic is delegated to type-safe condition evaluators
 * (either ConditionEvaluator<String> for simple checks, or
 * ContextAwareConditionEvaluator for context-dependent checks)
 * via {@link ConditionEvaluatorFactory}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class BreakStepEvaluator {

  private BreakStepEvaluator() {}

  /**
   * Evaluates a break step condition.
   * If met, signals halt in the context and returns true.
   *
   * @param cfg the break step configuration
   * @param context the pipeline context
   * @return true if condition was met and halt was signalled
   */
  public static boolean evaluate(BreakStepConfig cfg, PipelineContext context) {
    boolean conditionMet = evaluateCondition(cfg, context);

    if (conditionMet) {
      context.signalHalt(cfg.getOutputField(), FinishReason.FINISH_REASON_BREAK_CONDITION);
    }

    return conditionMet;
  }

  /**
   * Evaluates a loop exit condition.
   * Returns true when the loop should exit.
   *
   * @param cfg the loop step configuration
   * @param context the pipeline context
   * @return true if the exit condition is met
   */
  public static boolean evaluateLoopExit(LoopStepConfig cfg, PipelineContext context) {
    return evaluateCondition(cfg, context);
  }

  private static boolean evaluateCondition(BreakStepConfig cfg, PipelineContext context) {
    String value = context.get(cfg.getInputField());
    if (ConditionEvaluatorFactory.isStringCondition(cfg.getCondition())) {
      return createStringEvaluator(cfg.getCondition(), cfg.getMatchValue()).evaluate(value);
    }
    if (ConditionEvaluatorFactory.isContextAwareCondition(cfg.getCondition())) {
      return createContextAwareEvaluator(cfg.getCondition(), cfg.getThreshold()).evaluate(
        value,
        context,
        cfg.getInputField()
      );
    }
    return false;
  }

  private static boolean evaluateCondition(LoopStepConfig cfg, PipelineContext context) {
    String value = context.get(cfg.getInputField());
    if (ConditionEvaluatorFactory.isStringCondition(cfg.getCondition())) {
      return createStringEvaluator(cfg.getCondition(), cfg.getMatchValue()).evaluate(value);
    }
    if (ConditionEvaluatorFactory.isContextAwareCondition(cfg.getCondition())) {
      return createContextAwareEvaluator(cfg.getCondition(), cfg.getThreshold()).evaluate(
        value,
        context,
        cfg.getInputField()
      );
    }
    return false;
  }

  /**
   * Creates a string evaluator for the given condition type.
   */
  private static ConditionEvaluator<String> createStringEvaluator(
    BreakCondition condition,
    String matchValue
  ) {
    return switch (condition) {
      case BREAK_CONDITION_EQUALS, BREAK_CONDITION_LABEL_EQUALS -> forEquals(matchValue);
      case BREAK_CONDITION_CONTAINS -> forContains(matchValue);
      case BREAK_CONDITION_NOT_EMPTY -> forNotEmpty();
      case BREAK_CONDITION_EMPTY -> forEmpty();
      default -> forFalse();
    };
  }

  /**
   * Creates a context-aware evaluator for the given condition type.
   */
  private static ContextAwareConditionEvaluator createContextAwareEvaluator(
    BreakCondition condition,
    float threshold
  ) {
    return switch (condition) {
      case BREAK_CONDITION_SCORE_ABOVE -> forScoreAbove(threshold);
      case BREAK_CONDITION_SCORE_BELOW -> forScoreBelow(threshold);
      default -> forScoreFalse();
    };
  }
}
