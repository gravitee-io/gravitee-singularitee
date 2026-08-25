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
package io.gravitee.singularitee.plugin.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.util.Map;

/**
 * Parses the YAML {@code config:} block of a {@code todo} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TodoStepCodec implements StepConfigCodec<TodoStepConfig> {

  private final ObjectMapper mapper = new ObjectMapper();

  /** The YAML shape of the block, keyed exactly as a workspace writes it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(@JsonProperty("handled_step") String handledStep) {}

  @Override
  public TodoStepConfig parse(String stepId, Map<String, Object> config, StepCodecContext ctx) {
    Yaml d;
    try {
      d = mapper.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "todo step '" + stepId + "' has an invalid config: " + e.getMessage(),
        e
      );
    }
    return new TodoStepConfig(d.handledStep());
  }
}
