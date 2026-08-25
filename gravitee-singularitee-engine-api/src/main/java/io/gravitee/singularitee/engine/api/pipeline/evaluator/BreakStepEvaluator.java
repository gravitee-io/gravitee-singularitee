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
package io.gravitee.singularitee.engine.api.pipeline.evaluator;

import static io.gravitee.singularitee.engine.api.pipeline.evaluator.ConditionEvaluatorFactory.*;

import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;

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
   * Evaluates a generic condition: the path every plugin-owned break or loop config uses.
   */
  public static boolean evaluate(StepCondition condition, PipelineContext context) {
    String value = context.get(condition.inputField());
    if (condition.kind().isStringCondition()) {
      return createStringEvaluator(condition.kind(), condition.matchValue()).evaluate(value);
    }
    if (condition.kind().isScoreCondition()) {
      return createContextAwareEvaluator(condition.kind(), condition.threshold()).evaluate(
        value,
        context,
        condition.inputField()
      );
    }
    return false;
  }

  private static ConditionEvaluator<String> createStringEvaluator(
    ConditionKind kind,
    String matchValue
  ) {
    return switch (kind) {
      case EQUALS, LABEL_EQUALS -> forEquals(matchValue);
      case CONTAINS -> forContains(matchValue);
      case NOT_EMPTY -> forNotEmpty();
      case EMPTY -> forEmpty();
      default -> forFalse();
    };
  }

  private static ContextAwareConditionEvaluator createContextAwareEvaluator(
    ConditionKind kind,
    float threshold
  ) {
    return switch (kind) {
      case SCORE_ABOVE -> forScoreAbove(threshold);
      case SCORE_BELOW -> forScoreBelow(threshold);
      default -> forScoreFalse();
    };
  }
}
