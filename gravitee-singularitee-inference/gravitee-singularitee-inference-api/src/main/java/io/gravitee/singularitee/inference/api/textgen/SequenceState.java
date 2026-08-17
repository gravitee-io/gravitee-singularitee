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

import java.util.List;
import java.util.Objects;

/**
 * Internal state for a sequence in the batch engine.
 * Tracks the conversation state, stop sequence buffering, and token counts.
 *
 * @param <STATE> Engine-specific sequence state type
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SequenceState<STATE> {

  final int conversationId;
  final int externalId;
  final STATE engineState;
  final List<String> stopStrings;
  final int maxStopLength;
  final StringBuilder pending = new StringBuilder();
  int index = 0;
  int inputTokens = 0;
  int outputTokens = 0;
  int reasoningTokens = 0;
  int toolTokens = 0;
  boolean finalSent = false;
  /** Client cache-affinity key for the KV prefix cache, or {@code null}. */
  String cacheKey;
  /**
   * Set when a stop string matched. The backend does not know about stop strings — they are
   * detected here, on the decoded text — so this is the only record that the sequence is over,
   * and the finish reason has to come from it rather than from the engine.
   */
  boolean stopMatched;

  /**
   * Degenerate-run tracking: the text of the last emitted token and how many
   * times it has repeated back-to-back. A model stuck emitting one token
   * forever otherwise burns until the token cap.
   */
  String lastEmittedText;
  int identicalRun;

  /** Guards against double slot-cache release (stop-match + finalize paths). */
  boolean slotReleased = false;
  /** Whether this slot's prefix has been offered to other requests (once, after prefill). */
  boolean prefixPublished = false;

  /**
   * Perf-counter snapshot taken at sequence start: the backend's counters are
   * context-lifetime cumulative, and per-request performance is the delta
   * against this baseline.
   */
  InferencePerformance perfBaseline;

  /** Wall-clock instrumentation: sequence start and first emitted token (nanos, 0 = unset). */
  long startedNanos = System.nanoTime();
  long firstTokenNanos = 0;

  SequenceState(int conversationId, int externalId, STATE engineState, List<String> stopStrings) {
    this.conversationId = conversationId;
    this.externalId = externalId;
    this.engineState = engineState;
    this.stopStrings = stopStrings == null ? List.of() : stopStrings;
    this.maxStopLength = this.stopStrings.stream().mapToInt(String::length).max().orElse(0);

    // Detect token type from first token if possible
    this.tokenType = String.class; // Default to String, could be overridden
  }

  @SuppressWarnings("unused")
  final Class<?> tokenType;

  /**
   * Consumes a token and returns the emission result.
   * Handles stop sequence detection and buffering.
   *
   * @param token The token to consume
   * @return The emission result containing text to emit and whether a stop was matched
   */
  TokenEmission consume(String token) {
    if (token == null || token.isEmpty()) {
      return new TokenEmission("", false);
    }
    if (maxStopLength == 0) {
      return new TokenEmission(token, false);
    }
    pending.append(token);
    int stopIndex = indexOfStop(pending);
    if (stopIndex >= 0) {
      String output = pending.substring(0, stopIndex);
      pending.setLength(0);
      return new TokenEmission(output, true);
    }
    if (pending.length() > maxStopLength) {
      int emitLength = pending.length() - maxStopLength;
      String output = pending.substring(0, emitLength);
      pending.delete(0, emitLength);
      return new TokenEmission(output, false);
    }
    return new TokenEmission("", false);
  }

  /**
   * Flushes any pending buffered tokens.
   *
   * @return The pending text buffer
   */
  String flushPending() {
    if (pending.isEmpty()) {
      return "";
    }
    String output = pending.toString();
    pending.setLength(0);
    return output;
  }

  /**
   * Finds the index of the first stop sequence in the buffer.
   *
   * @param buffer The buffer to search
   * @return The index of the first stop sequence, or -1 if not found
   */
  private int indexOfStop(StringBuilder buffer) {
    int best = -1;
    for (String stop : stopStrings) {
      int idx = buffer.indexOf(stop);
      if (idx >= 0 && (best == -1 || idx < best)) {
        best = idx;
      }
    }
    return best;
  }
}
