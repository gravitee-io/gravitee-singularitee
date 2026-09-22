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
package io.gravitee.singularitee.plugin.infer;

import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.model.TagSet;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link TextGenRequest} for an INFER step: resolves each sampling
 * parameter across the override chain (request override wins over the
 * loop-retry override, which wins over the step's sampling params), maps the
 * step's reasoning/tool tag pairs onto the engine's {@link TagConfig},
 * carrying everything the proto defines, alternatives and the repeatable flag
 * included, so the pipeline path and the direct Infer path share one tag
 * contract, and assembles the per-step template context.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class TextGenRequestFactory {

  private TextGenRequestFactory() {}

  /**
   * The effective completion reservation: request override wins over the
   * loop-retry override, which wins over the step's sampling params. Resolved
   * once and reused for both the context-window trim and the request.
   */
  static int resolveMaxTokens(
    InferStepConfig cfg,
    SamplingParams requestOverrides,
    SamplingParams retryOverrides
  ) {
    var stepSp = stepSamplingParams(cfg);
    return pick(
      requestOverrides != null ? requestOverrides.getMaxTokens() : 0,
      pick(retryOverrides != null ? retryOverrides.getMaxTokens() : 0, stepSp.getMaxTokens())
    );
  }

  static TextGenRequest create(
    InferStepConfig cfg,
    String renderedPrompt,
    List<ChatTurn> wireMessages,
    SamplingParams requestOverrides,
    SamplingParams retryOverrides,
    int maxTokens,
    String cacheKey,
    String reasoningEffort,
    StructuredOutput structuredOutput
  ) {
    var stepSp = stepSamplingParams(cfg);

    float temperature = pickFloat(
      requestOverrides != null ? requestOverrides.getTemperature() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getTemperature() : 0,
        stepSp.getTemperature()
      )
    );
    float topP = pickFloat(
      requestOverrides != null ? requestOverrides.getTopP() : 0,
      pickFloat(retryOverrides != null ? retryOverrides.getTopP() : 0, stepSp.getTopP())
    );
    float presencePenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getPresencePenalty() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getPresencePenalty() : 0,
        stepSp.getPresencePenalty()
      )
    );
    float frequencyPenalty = pickFloat(
      requestOverrides != null ? requestOverrides.getFrequencyPenalty() : 0,
      pickFloat(
        retryOverrides != null ? retryOverrides.getFrequencyPenalty() : 0,
        stepSp.getFrequencyPenalty()
      )
    );
    int seed = pick(
      requestOverrides != null ? requestOverrides.getSeed() : 0,
      pick(retryOverrides != null ? retryOverrides.getSeed() : 0, stepSp.getSeed())
    );

    TagConfig reasoningTags = TagSet.toEngine(cfg.reasoningTags());
    TagConfig toolTags = TagSet.toEngine(cfg.toolCallTags());

    return new TextGenRequest(
      renderedPrompt,
      // Multimodal media extraction when prompt is set; the actual payload
      // (engine-side rendering) when prompt is null.
      wireMessages,
      maxTokens > 0 ? maxTokens : null,
      temperature > 0 ? temperature : null,
      topP > 0 ? topP : null,
      presencePenalty != 0 ? presencePenalty : null,
      frequencyPenalty != 0 ? frequencyPenalty : null,
      cfg.stop().isEmpty() ? null : cfg.stop(),
      seed > 0 ? seed : null,
      reasoningTags,
      toolTags,
      cfg.lora() != null ? cfg.lora().getLoraName() : null,
      cfg.lora() != null ? cfg.lora().getLoraPath() : null,
      // Per-step context: variables (enable_thinking, …). Only consulted by
      // the engine when it has to render `messages` itself (no pre-rendered
      // prompt); harmless otherwise. Request-level reasoning_effort overrides
      // the step-config value, mirroring the Jinja context build.
      buildTemplateContext(cfg, reasoningEffort),
      cacheKey,
      // Request the top-3 token log-probabilities per position (computed during sampling anyway) so
      // the capture stream can derive confidence signals: the chosen-token perplexity, plus
      // distributional summaries that need the alternatives (top1-vs-top2 margin, per-token entropy).
      // Depth 3 keeps the payload modest while enabling the margin/entropy features.
      3,
      structuredOutput
    );
  }

  /**
   * The caller's decoding constraint applies to the {@code role: output} step only, so a guard
   * or router never inherits it.
   */
  static StructuredOutput resolveStructuredOutput(StepRole role, StructuredOutput requested) {
    return role == StepRole.STEP_ROLE_OUTPUT ? requested : null;
  }

  private static SamplingParams stepSamplingParams(InferStepConfig cfg) {
    return cfg.effectiveSamplingParams();
  }

  private static Map<String, Object> buildTemplateContext(
    InferStepConfig cfg,
    String reasoningEffort
  ) {
    if (cfg.context() == null && reasoningEffort == null) {
      return null;
    }
    Map<String, Object> ctx = new LinkedHashMap<>();
    if (cfg.context() != null) {
      ctx.putAll(cfg.context());
    }
    if (reasoningEffort != null) {
      ctx.put(PipelineContext.KEY_REASONING_EFFORT, reasoningEffort);
    }
    return ctx;
  }

  private static int pick(int override, int fallback) {
    return override > 0 ? override : fallback;
  }

  private static float pickFloat(float override, float fallback) {
    return override != 0 ? override : fallback;
  }
}
