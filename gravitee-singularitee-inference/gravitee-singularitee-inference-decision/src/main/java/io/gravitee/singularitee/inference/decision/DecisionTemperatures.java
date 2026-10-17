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
package io.gravitee.singularitee.inference.decision;

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.HashMap;
import java.util.Map;

/**
 * The calibration temperatures of a decision model: the ones it ships, overridden per key by the
 * workspace. A temperature can depend on the number of options, through the bucket the model was
 * fitted with.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionTemperatures {

  private final DecisionFamily family;
  private final Map<String, Float> values;

  /**
   * @param family    selects the option-count buckets
   * @param model     the temperatures read from the model
   * @param overrides workspace values, same keys; they win
   */
  public DecisionTemperatures(
    DecisionFamily family,
    Map<String, Float> model,
    Map<String, Float> overrides
  ) {
    this.family = family;
    var merged = new HashMap<String, Float>(model == null ? Map.of() : model);
    if (overrides != null) merged.putAll(overrides);
    merged.forEach((key, value) -> {
      if (value == null || !(value > 0f)) {
        throw new IllegalArgumentException("invalid decision temperature: " + key + " = " + value);
      }
    });
    this.values = Map.copyOf(merged);
  }

  /** The temperature of a question of {@code kind} with {@code nOptions} options; 1 when unset. */
  public float of(QuestionKind kind, int nOptions) {
    String type = kind.wireName();
    Float bucketed = values.get(type + "." + bucket(nOptions));
    if (bucketed != null) return bucketed;
    return values.getOrDefault(type, 1.0f);
  }

  private String bucket(int n) {
    if (family == DecisionFamily.LEV) {
      return n <= 8 ? "small" : n <= 26 ? "mid" : "large";
    }
    return n <= 2 ? "2" : n <= 5 ? "3_5" : n <= 10 ? "6_10" : "11";
  }
}
