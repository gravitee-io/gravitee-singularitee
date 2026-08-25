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
package io.gravitee.singularitee.plugin.loop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.pipeline.model.ConditionKind;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.protocol.SamplingParams;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code loop} step. The exit edge is the envelope's
 * {@code next_step}, which the loader exposes to the codec under the {@code next_step} key.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LoopStepCodec implements StepConfigCodec<LoopStepConfig> {

  /** Key under which the loader exposes the step envelope's {@code next_step}. */
  static final String NEXT_STEP_KEY = "next_step";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public LoopStepConfig parse(String stepId, Map<String, Object> raw, StepCodecContext ctx) {
    Yaml d;
    try {
      d = MAPPER.convertValue(raw, Yaml.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Step '" + stepId + "': invalid loop config: " + e.getMessage(),
        e
      );
    }
    String exitStepId = raw.get(NEXT_STEP_KEY) instanceof String s ? s : null;

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

    // Optional message injected as a USER (or configured role) turn into the
    // conversation every time the loop branches back. The content supports Jinja
    // interpolation and is rendered by the executor at loop-back time.
    MessageTemplate loopback = d.loopbackMessage() == null
      ? null
      : new MessageTemplate(d.loopbackMessage().role(), d.loopbackMessage().content());

    // Retry-edge sampling override: applied only when branching back (a request
    // override still wins).
    SamplingParams retry = d.retrySamplingParams() == null
      ? null
      : toSamplingParams(d.retrySamplingParams());

    try {
      return new LoopStepConfig(
        d.loopbackStep(),
        exitStepId,
        d.fallbackStep(),
        d.maxIterations(),
        condition,
        loopback,
        retry
      );
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Step '" + stepId + "': " + e.getMessage(), e);
    }
  }

  private static SamplingParams toSamplingParams(SamplingYaml s) {
    var sp = SamplingParams.newBuilder();
    if (s.maxTokens() > 0) sp.setMaxTokens(s.maxTokens());
    if (s.temperature() > 0) sp.setTemperature(s.temperature());
    if (s.topP() > 0) sp.setTopP(s.topP());
    if (s.presencePenalty() != 0) sp.setPresencePenalty(s.presencePenalty());
    if (s.frequencyPenalty() != 0) sp.setFrequencyPenalty(s.frequencyPenalty());
    return sp.build();
  }

  /** The YAML shape of the block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Yaml(
    @JsonProperty("loopback_step") String loopbackStep,
    @JsonProperty("max_iterations") int maxIterations,
    @JsonProperty("fallback_step") String fallbackStep,
    @JsonProperty("condition") ConditionYaml condition,
    @JsonProperty("loopback_message") MessageYaml loopbackMessage,
    @JsonProperty("retry_sampling_params") SamplingYaml retrySamplingParams
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

  /** The {@code loopback_message:} sub-block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MessageYaml(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content
  ) {}

  /** The {@code retry_sampling_params:} sub-block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SamplingYaml(
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("temperature") float temperature,
    @JsonProperty("top_p") float topP,
    @JsonProperty("presence_penalty") float presencePenalty,
    @JsonProperty("frequency_penalty") float frequencyPenalty,
    @JsonProperty("stop") List<String> stop
  ) {}
}
