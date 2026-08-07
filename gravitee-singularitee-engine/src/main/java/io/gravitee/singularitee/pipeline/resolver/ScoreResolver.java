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
package io.gravitee.singularitee.pipeline.resolver;

import io.gravitee.singularitee.pipeline.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for resolving score values from pipeline context.
 *
 * <p>Score resolution logic:
 * <ol>
 *   <li>Try "{@code <input_field>.score}" key</li>
 *   <li>Try stripping last segment: "{@code quality.label}" → "{@code quality.score}"</li>
 *   <li>Fall back to parsing raw value as float</li>
 *   <li>Default to 0.0 if not found or parse fails</li>
 * </ol>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ScoreResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(ScoreResolver.class);

  private ScoreResolver() {}

  /**
   * Resolves a score from context using multi-level fallback logic.
   *
   * @param rawValue the raw field value (may be null)
   * @param context the pipeline context for field lookups
   * @param inputField the input field name (for key derivation)
   * @return the resolved score, or 0.0 if not found
   */
  public static float resolveScore(String rawValue, PipelineContext context, String inputField) {
    // Try "<input_field>.score" first
    String scoreStr = context.get(inputField + ".score");

    // Also try stripping the last segment: "quality.label" → "quality.score"
    if (scoreStr == null && inputField.contains(".")) {
      int lastDot = inputField.lastIndexOf('.');
      scoreStr = context.get(inputField.substring(0, lastDot) + ".score");
    }

    // Fall back to parsing the raw value itself as a float
    if (scoreStr == null) {
      scoreStr = rawValue;
    }

    if (scoreStr == null) {
      return 0f;
    }

    try {
      return Float.parseFloat(scoreStr);
    } catch (NumberFormatException e) {
      LOGGER.warn("Failed to parse score as float: '{}' from field '{}'", scoreStr, inputField);
      return 0f;
    }
  }
}
