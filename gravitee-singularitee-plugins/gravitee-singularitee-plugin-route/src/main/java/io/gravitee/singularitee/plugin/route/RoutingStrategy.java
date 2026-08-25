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
package io.gravitee.singularitee.plugin.route;

import java.util.Locale;

/**
 * How a {@code route} step picks a label for the input.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum RoutingStrategy {
  /** A classifier's top label. */
  CLASSIFIER,
  /** Nearest reference embedding by cosine similarity. */
  EMBEDDING_KNN,
  /** The input text (typically a prior judge step's output) is matched against rule labels. */
  LLM_STRUCTURED;

  /** Parses the YAML spelling; unset or unknown is {@link #CLASSIFIER}. */
  public static RoutingStrategy parse(String value) {
    if (value == null) return CLASSIFIER;
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "embedding_knn" -> EMBEDDING_KNN;
      case "llm_structured" -> LLM_STRUCTURED;
      default -> CLASSIFIER;
    };
  }
}
