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

/**
 * Generic strategy interface for evaluating conditions.
 *
 * <p>Each condition type (EQUALS, CONTAINS, SCORE_ABOVE, etc.) implements this
 * interface with its specific parameter type, eliminating the need for unused parameters.
 *
 * <p>Type-safe and self-documenting: the interface signature tells you exactly
 * what parameters the condition needs to evaluate.
 *
 * <p>Implementations are stateless and thread-safe.
 *
 * @param <T> the type of value this condition evaluates (String, Float, etc.)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface ConditionEvaluator<T> {
  /**
   * Evaluates the condition and returns true if it matches.
   *
   * @param value the value to evaluate (type-specific)
   * @return true if the condition is satisfied
   */
  boolean evaluate(T value);
}
