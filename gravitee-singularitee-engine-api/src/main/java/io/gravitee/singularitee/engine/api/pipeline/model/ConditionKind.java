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
package io.gravitee.singularitee.engine.api.pipeline.model;

import java.util.Locale;

/**
 * The kinds of condition a break or loop step evaluates over a context field.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum ConditionKind {
  /** Field value equals match_value. */
  EQUALS,
  /** Field value contains match_value. */
  CONTAINS,
  /** Classifier top label equals match_value. */
  LABEL_EQUALS,
  /** Resolved score is at least threshold. */
  SCORE_ABOVE,
  /** Resolved score is below threshold. */
  SCORE_BELOW,
  /** Field value is non-null and non-blank. */
  NOT_EMPTY,
  /** Field value is null or blank. */
  EMPTY;

  /** Parses the YAML spelling ({@code contains}, {@code score_above}, ...); unknown fails. */
  public static ConditionKind parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Condition type is required");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ENGLISH));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown condition type '" + value + "'", e);
    }
  }

  public boolean isStringCondition() {
    return (
      this == EQUALS ||
      this == CONTAINS ||
      this == LABEL_EQUALS ||
      this == NOT_EMPTY ||
      this == EMPTY
    );
  }

  public boolean isScoreCondition() {
    return this == SCORE_ABOVE || this == SCORE_BELOW;
  }
}
