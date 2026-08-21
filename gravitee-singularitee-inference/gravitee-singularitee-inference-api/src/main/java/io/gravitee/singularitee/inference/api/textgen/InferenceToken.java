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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * One token streamed by a batch engine, or the final marker of a sequence.
 *
 * <p>A final token ({@code isFinal}) carries no text, always has a {@code finishReason}, and
 * holds the closing counters and timings. Non-final tokens carry text plus the running counters.
 *
 * @param <T> token type ({@link String} for text tokens)
 * @param seqId external sequence id
 * @param token token content, {@code null} on the final token
 * @param index position of the token in the sequence
 * @param isFinal whether this closes the sequence
 * @param finishReason why the sequence ended; required when final
 * @param promptTokens prompt tokens processed so far
 * @param completionTokens completion tokens generated so far
 * @param reasoningTokens completion tokens on the reasoning channel ({@code 0} if untracked)
 * @param toolTokens completion tokens on the tool-call channel ({@code 0} if untracked)
 * @param performance per-request timings, set on the final token when the backend reports them
 * @param channel generation channel as classified by the engine; {@code null} means unclassified
 *                (answer semantics)
 * @param logprobs log-probabilities for this position; {@code null} unless collection was
 *                 requested
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record InferenceToken<T>(
  int seqId,
  T token,
  int index,
  boolean isFinal,
  String finishReason,
  int promptTokens,
  int completionTokens,
  int reasoningTokens,
  int toolTokens,
  InferencePerformance performance,
  TokenChannel channel,
  PositionLogprobs logprobs
) {
  /** Token without logprobs. */
  public InferenceToken(
    int seqId,
    T token,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int reasoningTokens,
    int toolTokens,
    InferencePerformance performance,
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

  /** Token without channel or logprobs. */
  public InferenceToken(
    int seqId,
    T token,
    int index,
    boolean isFinal,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int reasoningTokens,
    int toolTokens,
    InferencePerformance performance
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

  public InferenceToken {
    if (seqId < 0) {
      throw new IllegalArgumentException("seqId must be non-negative");
    }
    if (index < 0) {
      throw new IllegalArgumentException("index must be non-negative");
    }
    if (isFinal && finishReason == null) {
      throw new IllegalArgumentException("finishReason is required when isFinal is true");
    }
  }
}
