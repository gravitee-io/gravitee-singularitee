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

import io.gravitee.singularitee.inference.api.textgen.PositionLogprobs;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;

/**
 * A single token emitted by a {@link TextGenEngine}.
 *
 * <p>This is the local equivalent of {@code InferenceToken<String>} from
 * {@code gravitee-inference-api}. It is defined here so that no layer above the
 * {@code adapter} package ever needs to import an external inference library type.
 *
 * <p>On non-final tokens {@link #performance()} is {@code null}.
 * On the final token {@link #isFinal()} is {@code true}, {@link #finishReason()}
 * is set, and {@link #performance()} carries the full timing breakdown.
 *
 * @param seqId             server-assigned internal sequence identifier; used as the
 *                          key in the active-stream map — never exposed to callers
 * @param token             the generated token text (may be {@code null} on the final message)
 * @param index             zero-based index of this token in the generated sequence
 * @param isFinal           {@code true} on the last token of the sequence
 * @param finishReason      stop reason string (e.g. {@code "stop"}, {@code "length"}); only
 *                          meaningful when {@code isFinal} is {@code true}
 * @param promptTokens      number of prompt tokens evaluated
 * @param completionTokens  number of completion tokens generated so far
 * @param reasoningTokens   number of reasoning tokens (0 if not supported by the engine)
 * @param toolTokens        number of tool-call tokens (0 if not supported by the engine)
 * @param performance       timing and throughput metrics; {@code null} on non-final tokens
 * @param channel           generation channel of this token as classified by the engine
 *                          at production time; {@code null} means unclassified (ANSWER
 *                          semantics — engines without classification pass {@code null})
 * @param logprobs          log-probability data for the token position this emission
 *                          resolved; {@code null} unless collection was requested
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ModelEngineToken(
  int seqId,
  String token,
  int index,
  boolean isFinal,
  String finishReason,
  int promptTokens,
  int completionTokens,
  int reasoningTokens,
  int toolTokens,
  ModelEnginePerformance performance,
  TokenChannel channel,
  PositionLogprobs logprobs
) {
  /** Compatibility constructor for callers without logprobs collection: logprobs = null. */
  public ModelEngineToken(
    int seqId,
    String token,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int reasoningTokens,
    int toolTokens,
    ModelEnginePerformance performance,
    TokenChannel channel
  ) {
    this(
      seqId,
      token,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      channel,
      null
    );
  }

  /** Compatibility constructor for callers without channel classification: channel = null. */
  public ModelEngineToken(
    int seqId,
    String token,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int reasoningTokens,
    int toolTokens,
    ModelEnginePerformance performance
  ) {
    this(
      seqId,
      token,
      index,
      isFinal,
      finishReason,
      promptTokens,
      completionTokens,
      reasoningTokens,
      toolTokens,
      performance,
      null,
      null
    );
  }
}
