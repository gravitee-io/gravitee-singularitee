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
package io.gravitee.singularitee.plugin.breakstep;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code break} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class BreakStepCodec implements StepConfigCodec<BreakStepConfig> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public BreakStepConfig parse(String stepId, Map<String, Object> raw, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(raw, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid break config: " + e.getMessage(),
        e
      );
    }
    StepCondition condition = null;
    if (d.condition() != null) {
      var c = d.condition();
      ConditionKind kind;
      try {
        kind = ConditionKind.parse(c.type());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Step '" + stepId + "': " + e.getMessage(), e);
      }
      condition = new StepCondition(kind, c.inputField(), c.matchValue(), c.threshold());
    }
    return new BreakStepConfig(d.outputField(), condition);
  }

  /** The YAML shape of the block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("output_field") String outputField,
    @JsonProperty("condition") ConditionYaml condition
  ) {}

  /** The {@code condition:} sub-block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ConditionYaml(
    @JsonProperty("type") String type,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("match_value") Object rawMatchValue,
    @JsonProperty("threshold") float threshold
  ) {
    /**
     * {@code match_value} as a string. YAML 1.1 parses bare YES/NO/TRUE/FALSE as booleans;
     * this maps them back to {@code "YES"} / {@code "NO"}.
     */
    String matchValue() {
      if (rawMatchValue == null) return null;
      if (rawMatchValue instanceof Boolean b) return b ? "YES" : "NO";
      return rawMatchValue.toString();
    }
  }
}
