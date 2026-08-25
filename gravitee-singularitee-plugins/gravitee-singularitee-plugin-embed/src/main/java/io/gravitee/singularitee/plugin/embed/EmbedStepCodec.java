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
package io.gravitee.singularitee.plugin.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.util.Map;

/**
 * Parses the {@code config:} block of an {@code embed} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class EmbedStepCodec implements StepConfigCodec<EmbedStepConfig> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public EmbedStepConfig parse(String stepId, Map<String, Object> raw, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(raw, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid embed config: " + e.getMessage(),
        e
      );
    }
    return new EmbedStepConfig(d.modelId(), d.inputField(), d.outputField());
  }

  /** The YAML shape of the block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField
  ) {}
}
