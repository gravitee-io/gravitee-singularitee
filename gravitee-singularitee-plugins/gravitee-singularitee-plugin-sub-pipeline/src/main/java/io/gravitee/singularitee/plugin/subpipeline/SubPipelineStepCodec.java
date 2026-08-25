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
package io.gravitee.singularitee.plugin.subpipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import java.util.Map;

/**
 * Parses the YAML {@code config:} block of a {@code sub_pipeline} step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SubPipelineStepCodec implements StepConfigCodec<SubPipelineStepConfig> {

  private final ObjectMapper mapper = new ObjectMapper();

  /** The YAML shape of the block, keyed exactly as a workspace writes it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("pipeline_id") String pipelineId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("server") String server,
    @JsonProperty("system_prompt") String systemPrompt,
    @JsonProperty("forward_messages") boolean forwardMessages
  ) {}

  @Override
  public SubPipelineStepConfig parse(
    String stepId,
    Map<String, Object> config,
    StepCodecContext ctx
  ) {
    Yaml d;
    try {
      d = mapper.convertValue(config, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "sub_pipeline step '" + stepId + "' has an invalid config: " + e.getMessage(),
        e
      );
    }
    if (d.pipelineId() == null || d.pipelineId().isBlank()) {
      throw new IllegalArgumentException(
        "sub_pipeline step '" + stepId + "' requires a 'pipeline_id'"
      );
    }
    return new SubPipelineStepConfig(
      d.pipelineId(),
      d.inputField(),
      d.outputField(),
      blankToNull(d.server()),
      blankToNull(d.systemPrompt()),
      d.forwardMessages()
    );
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
