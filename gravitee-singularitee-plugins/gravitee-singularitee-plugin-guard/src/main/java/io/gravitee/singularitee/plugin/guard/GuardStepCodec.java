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
package io.gravitee.singularitee.plugin.guard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.plugin.guard.GuardStepConfig.GuardTrigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code guard} step. {@code triggers} wins over the
 * single {@code trigger} form; both land in one list so the executor reads one shape.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GuardStepCodec implements StepConfigCodec<GuardStepConfig> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("action") String action,
    @JsonProperty("trigger") TriggerYaml trigger,
    @JsonProperty("triggers") List<TriggerYaml> triggers,
    @JsonProperty("message") String message,
    @JsonProperty("redact_with_entity_type") boolean redactWithEntityType
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TriggerYaml(
    @JsonProperty("label") String label,
    @JsonProperty("score") float score
  ) {}

  @Override
  public GuardStepConfig parse(String stepId, Map<String, Object> config, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid guard config: " + e.getMessage(),
        e
      );
    }
    List<GuardTrigger> triggers = new ArrayList<>();
    if (d.triggers() != null && !d.triggers().isEmpty()) {
      for (var t : d.triggers()) triggers.add(toTrigger(t));
    } else if (d.trigger() != null) {
      triggers.add(toTrigger(d.trigger()));
    }
    return new GuardStepConfig(
      d.modelId(),
      d.inputField(),
      d.outputField() != null && !d.outputField().isBlank() ? d.outputField() : "",
      GuardAction.parse(d.action()),
      triggers,
      d.message() != null && !d.message().isBlank() ? d.message() : "",
      d.redactWithEntityType()
    );
  }

  private static GuardTrigger toTrigger(TriggerYaml t) {
    return new GuardTrigger(t.label(), t.score() > 0 ? t.score() : 0f);
  }
}
