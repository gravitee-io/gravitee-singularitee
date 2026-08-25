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
package io.gravitee.singularitee.plugin.regexguard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.plugin.regexguard.RegexGuardStepConfig.RegexEntity;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code regex_guard} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RegexGuardStepCodec implements StepConfigCodec<RegexGuardStepConfig> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("input_field") String inputField,
    @JsonProperty("patterns") List<EntityYaml> patterns,
    @JsonProperty("redact_with_entity_type") boolean redactWithEntityType,
    @JsonProperty("action") String action,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("message") String message
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EntityYaml(
    @JsonProperty("name") String name,
    @JsonProperty("pattern") String pattern
  ) {}

  @Override
  public RegexGuardStepConfig parse(
    String stepId,
    Map<String, Object> config,
    StepCodecContext ctx
  ) {
    Yaml d;
    try {
      d = MAPPER.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid regex_guard config: " + e.getMessage(),
        e
      );
    }
    List<RegexEntity> patterns = d.patterns() == null
      ? List.of()
      : d
        .patterns()
        .stream()
        .map(e -> new RegexEntity(e.name(), e.pattern()))
        .toList();
    return new RegexGuardStepConfig(
      d.inputField(),
      patterns,
      d.redactWithEntityType(),
      GuardAction.parse(d.action()),
      d.outputField() != null && !d.outputField().isBlank() ? d.outputField() : "",
      d.message() != null && !d.message().isBlank() ? d.message() : ""
    );
  }
}
