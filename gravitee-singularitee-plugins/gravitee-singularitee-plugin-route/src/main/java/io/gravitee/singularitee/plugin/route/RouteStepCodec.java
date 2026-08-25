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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.plugin.route.RouteStepConfig.RouteRule;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code route} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RouteStepCodec implements StepConfigCodec<RouteStepConfig> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("strategy") String strategy,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("default_step") String defaultStep,
    @JsonProperty("rules") List<RuleYaml> rules
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RuleYaml(
    @JsonProperty("label") String label,
    @JsonProperty("sentences") List<String> sentences,
    @JsonProperty("next_step") String nextStep
  ) {}

  @Override
  public RouteStepConfig parse(String stepId, Map<String, Object> config, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid route config: " + e.getMessage(),
        e
      );
    }
    List<RouteRule> rules = d.rules() == null
      ? List.of()
      : d
        .rules()
        .stream()
        .map(r -> new RouteRule(r.label(), r.sentences(), r.nextStep()))
        .toList();
    return new RouteStepConfig(
      d.modelId(),
      RoutingStrategy.parse(d.strategy()),
      d.inputField(),
      d.defaultStep(),
      rules
    );
  }
}
