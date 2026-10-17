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
package io.gravitee.singularitee.inference.api.decision;

import java.util.Locale;

/**
 * The type of a decision question, which fixes the shape of its answer.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum QuestionKind {
  /** Pick one option out of several. */
  CHOICE,
  /** Place the state on an ordered scale of 2 to 10 levels. */
  SCORE,
  /** Yes or no, answered as the probability of yes. */
  NOUL;

  /** The lower-case name templates and the wire use. */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parses a wire name; {@code null} when unknown. */
  public static QuestionKind fromWireName(String name) {
    if (name == null) return null;
    return switch (name) {
      case "choice" -> CHOICE;
      case "score" -> SCORE;
      case "noul" -> NOUL;
      default -> null;
    };
  }
}
