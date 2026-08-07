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

import io.gravitee.singularitee.pipeline.resolver.ScoreResolver;
import io.gravitee.singularitee.protocol.BreakCondition;

/**
 * Factory for creating condition evaluators by break condition type.
 *
 * <p>Creates type-safe evaluators with only the parameters they need.
 * Uses factory methods to encapsulate condition logic and capture required parameters
 * (like matchValue or threshold) in closures.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ConditionEvaluatorFactory {

  private ConditionEvaluatorFactory() {}

  // =========================================================================
  // Factory Methods for String Conditions
  // =========================================================================

  /**
   * Creates an evaluator that checks for string equality.
   *
   * @param matchValue the value to compare against
   * @return a string condition evaluator
   */
  public static ConditionEvaluator<String> forEquals(String matchValue) {
    return value -> matchValue != null && matchValue.equals(value);
  }

  /**
   * Creates an evaluator that checks for string containment.
   *
   * @param matchValue the substring to search for
   * @return a string condition evaluator
   */
  public static ConditionEvaluator<String> forContains(String matchValue) {
    return value -> value != null && matchValue != null && value.contains(matchValue);
  }

  /**
   * Creates an evaluator that checks if string is not empty.
   *
   * @return a string condition evaluator
   */
  public static ConditionEvaluator<String> forNotEmpty() {
    return value -> value != null && !value.isBlank();
  }

  /**
   * Creates an evaluator that checks if string is empty.
   *
   * @return a string condition evaluator
   */
  public static ConditionEvaluator<String> forEmpty() {
    return value -> value == null || value.isBlank();
  }

  /**
   * Creates an evaluator that checks if string is empty.
   *
   * @return a string condition evaluator
   */
  public static ConditionEvaluator<String> forFalse() {
    return __ -> false;
  }

  // =========================================================================
  // Factory Methods for Context-Aware Conditions
  // =========================================================================

  /**
   * Creates an evaluator that checks if score is above threshold.
   *
   * @param threshold the minimum score (inclusive)
   * @return a context-aware condition evaluator
   */
  public static ContextAwareConditionEvaluator forScoreAbove(float threshold) {
    return (value, context, inputField) ->
      ScoreResolver.resolveScore(value, context, inputField) >= threshold;
  }

  /**
   * Creates an evaluator that checks if score is below threshold.
   *
   * @param threshold the maximum score (exclusive)
   * @return a context-aware condition evaluator
   */
  public static ContextAwareConditionEvaluator forScoreBelow(float threshold) {
    return (value, context, inputField) ->
      ScoreResolver.resolveScore(value, context, inputField) < threshold;
  }

  public static ContextAwareConditionEvaluator forScoreFalse() {
    return (__, ___, ____) -> false;
  }

  // =========================================================================
  // Evaluator Lookup by Condition Type
  // =========================================================================

  /**
   * Returns true if the condition is a simple string condition.
   */
  public static boolean isStringCondition(BreakCondition condition) {
    return (
      condition == BreakCondition.BREAK_CONDITION_EQUALS ||
      condition == BreakCondition.BREAK_CONDITION_CONTAINS ||
      condition == BreakCondition.BREAK_CONDITION_LABEL_EQUALS ||
      condition == BreakCondition.BREAK_CONDITION_NOT_EMPTY ||
      condition == BreakCondition.BREAK_CONDITION_EMPTY
    );
  }

  /**
   * Returns true if the condition is a context-aware condition.
   */
  public static boolean isContextAwareCondition(BreakCondition condition) {
    return (
      condition == BreakCondition.BREAK_CONDITION_SCORE_ABOVE ||
      condition == BreakCondition.BREAK_CONDITION_SCORE_BELOW
    );
  }
}
