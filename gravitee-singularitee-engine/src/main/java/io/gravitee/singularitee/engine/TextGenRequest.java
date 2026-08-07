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
package io.gravitee.singularitee.engine;

import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import java.util.List;
import java.util.Map;

/**
 * A text-generation request submitted to a {@link TextGenEngine}.
 *
 * <p>Either {@code prompt} or {@code messages} must be non-null; they are mutually exclusive.
 * When both are present, {@code prompt} takes precedence (pre-rendered by Jinja4j);
 * {@code messages} is retained for multimodal media extraction only.
 *
 * <p>All sampling parameters are optional — {@code null} means «use engine default».
 *
 * @param prompt            rendered prompt string (from Jinja4j chat template or raw template)
 * @param messages          structured chat turns — multimodal media extraction when
 *                          {@code prompt} is set; the payload for engine-side chat
 *                          template rendering when {@code prompt} is {@code null}
 * @param maxTokens         maximum tokens to generate; {@code null} = engine default
 * @param temperature       sampling temperature; {@code null} = engine default
 * @param topP              nucleus sampling threshold; {@code null} = engine default
 * @param presencePenalty   presence penalty; {@code null} = disabled
 * @param frequencyPenalty  frequency penalty; {@code null} = disabled
 * @param stop              additional stop sequences; {@code null} or empty = none
 * @param seed              random seed for reproducibility; {@code null} = random
 * @param reasoningTags     open/close tag pair for reasoning sections; {@code null} = disabled
 * @param toolCallTags      open/close tag pair for tool-call sections; {@code null} = disabled
 * @param loraName          LoRA adapter name for per-request adapter selection (vLLM only)
 * @param loraPath          LoRA adapter path for per-request adapter selection (vLLM only)
 * @param templateContext   extra chat-template variables (e.g. {@code enable_thinking})
 *                          applied when the engine renders {@code messages} with the
 *                          model's chat template; ignored when {@code prompt} is set
 *                          (a pre-rendered prompt is never re-templated); {@code null} = none
 * @param cacheKey          opaque client cache-affinity key (OpenAI {@code prompt_cache_key},
 *                          falling back to {@code user}); requests sharing a key are routed to
 *                          the same KV slot for prefix reuse; {@code null} = no affinity
 * @param topLogprobs       number of top log-probabilities to collect per generated token;
 *                          {@code null} or 0 = disabled (collection has a per-token cost)
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record TextGenRequest(
  String prompt,
  List<ChatTurn> messages,
  Integer maxTokens,
  Float temperature,
  Float topP,
  Float presencePenalty,
  Float frequencyPenalty,
  List<String> stop,
  Integer seed,
  TagConfig reasoningTags,
  TagConfig toolCallTags,
  String loraName,
  String loraPath,
  Map<String, Object> templateContext,
  String cacheKey,
  Integer topLogprobs
) {
  /** Compatibility constructor for callers with no logprobs collection. */
  public TextGenRequest(
    String prompt,
    List<ChatTurn> messages,
    Integer maxTokens,
    Float temperature,
    Float topP,
    Float presencePenalty,
    Float frequencyPenalty,
    List<String> stop,
    Integer seed,
    TagConfig reasoningTags,
    TagConfig toolCallTags,
    String loraName,
    String loraPath,
    Map<String, Object> templateContext,
    String cacheKey
  ) {
    this(
      prompt,
      messages,
      maxTokens,
      temperature,
      topP,
      presencePenalty,
      frequencyPenalty,
      stop,
      seed,
      reasoningTags,
      toolCallTags,
      loraName,
      loraPath,
      templateContext,
      cacheKey,
      null
    );
  }

  /** Compatibility constructor for callers with no cache-affinity key. */
  public TextGenRequest(
    String prompt,
    List<ChatTurn> messages,
    Integer maxTokens,
    Float temperature,
    Float topP,
    Float presencePenalty,
    Float frequencyPenalty,
    List<String> stop,
    Integer seed,
    TagConfig reasoningTags,
    TagConfig toolCallTags,
    String loraName,
    String loraPath,
    Map<String, Object> templateContext
  ) {
    this(
      prompt,
      messages,
      maxTokens,
      temperature,
      topP,
      presencePenalty,
      frequencyPenalty,
      stop,
      seed,
      reasoningTags,
      toolCallTags,
      loraName,
      loraPath,
      templateContext,
      null
    );
  }

  /** Convenience constructor for callers with no extra template variables. */
  public TextGenRequest(
    String prompt,
    List<ChatTurn> messages,
    Integer maxTokens,
    Float temperature,
    Float topP,
    Float presencePenalty,
    Float frequencyPenalty,
    List<String> stop,
    Integer seed,
    TagConfig reasoningTags,
    TagConfig toolCallTags,
    String loraName,
    String loraPath
  ) {
    this(
      prompt,
      messages,
      maxTokens,
      temperature,
      topP,
      presencePenalty,
      frequencyPenalty,
      stop,
      seed,
      reasoningTags,
      toolCallTags,
      loraName,
      loraPath,
      null
    );
  }
}
