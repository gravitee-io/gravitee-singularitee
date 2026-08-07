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

import io.gravitee.singularitee.pipeline.PipelineContext;

/**
 * Strategy interface for evaluating conditions that require pipeline context.
 *
 * <p>Some conditions (like score comparisons) need access to the full pipeline
 * context and input field name to resolve the score. This interface provides
 * a type-safe way to express that dependency.
 *
 * <p>Implementations are stateless and thread-safe.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface ContextAwareConditionEvaluator {
  /**
   * Evaluates the condition with access to the full pipeline context.
   *
   * @param value the field value from context (may be null)
   * @param context the pipeline context (for score field lookup)
   * @param inputField the input field name (for score key derivation)
   * @return true if the condition is satisfied
   */
  boolean evaluate(String value, PipelineContext context, String inputField);
}
