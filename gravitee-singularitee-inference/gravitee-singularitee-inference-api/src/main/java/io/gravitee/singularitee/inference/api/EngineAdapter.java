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
package io.gravitee.singularitee.inference.api;

import io.gravitee.singularitee.inference.api.textgen.AbstractBatchEngine;
import io.gravitee.singularitee.inference.api.textgen.InferencePerformance;
import io.gravitee.singularitee.inference.api.textgen.InferenceToken;
import io.gravitee.singularitee.inference.api.textgen.PositionLogprobs;
import io.gravitee.singularitee.inference.api.textgen.PromptStats;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import java.util.Optional;

/**
 * Backend-specific half of a text-generation engine, driven by {@link AbstractBatchEngine}.
 *
 * <p>Division of responsibilities:
 * <ul>
 *   <li>The batch engine owns slots (internal ids in {@code [0, maxConcurrentSequences)}),
 *       the pending queue, the external-to-internal id mapping, cancellation, stop-string
 *       matching on decoded text, token streaming and the KV prefix cache bookkeeping.</li>
 *   <li>The adapter owns the native or remote state behind each slot: it creates it in
 *       {@link #createSequenceState}, advances every active slot one step in
 *       {@link #processNextBatch()}, reports per-slot finish reasons and counters, and
 *       releases native memory in {@link #removeSequence} / {@link #cleanupSequenceState}.</li>
 * </ul>
 *
 * <p>Threading: every method except {@link #validateRequest} and {@link #tokenizePrompt} is
 * invoked while the batch engine holds its lock, so implementations need no synchronization of
 * their own but must not block for long. {@link #validateRequest} and {@link #tokenizePrompt}
 * run on the caller thread of {@code addSequence}, under the same lock.
 *
 * <p>Lifecycle of one sequence, as the batch engine drives it: {@code validateRequest},
 * optionally {@code tokenizePrompt} and {@code copyKvPrefix}, {@code createSequenceState},
 * repeated {@code processNextBatch} (interleaved with {@code getFinishReason},
 * {@code getTokenCounts}, {@code channelOf}, {@code logprobsOf}, {@code committedTokens}),
 * then {@code removeSequence} followed by {@code cleanupSequenceState}. Cancellation skips the
 * remaining steps and goes straight to {@code removeSequence}.
 *
 * @param <CONFIG> engine configuration type
 * @param <REQUEST> generation request type
 * @param <TOKEN> token type emitted by the backend
 * @param <STATE> per-sequence backend state
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface EngineAdapter<CONFIG, REQUEST, TOKEN, STATE> {
  /**
   * Creates the backend state for a sequence that has just been assigned a slot.
   *
   * @param internalId slot index the sequence will occupy
   * @param request the generation request
   * @return the new state, or {@code null} to reject the request (the slot is returned to the
   *         pool and no token is emitted)
   * @throws Exception if the state cannot be created; the slot is returned to the pool
   */
  STATE createSequenceState(int internalId, REQUEST request) throws Exception;

  /**
   * Creates the backend state for a slot whose first {@code reusePrefixTokens} prompt tokens are
   * already KV-resident, so prefill may skip them. The default ignores the hint and delegates to
   * {@link #createSequenceState(int, Object)}.
   *
   * @param reusePrefixTokens leading prompt tokens already in the slot's KV cache
   */
  default STATE createSequenceState(int internalId, REQUEST request, int reusePrefixTokens)
    throws Exception {
    return createSequenceState(internalId, request);
  }

  /**
   * Tokenizes the rendered prompt for prefix-cache matching.
   *
   * <p>Must be cheap (vocabulary only, no context) since it runs on the caller thread of
   * {@code addSequence}. {@code null} (the default) disables the slot cache for this request:
   * backends without server-side KV reuse, or requests carrying media.
   */
  default int[] tokenizePrompt(REQUEST request) {
    return null;
  }

  /**
   * Token ids currently committed to the slot's KV cache for this sequence (prompt plus
   * accepted completion tokens). {@code null} (the default) when the backend cannot report
   * them; the slot is then treated as cold and never donates its prefix.
   */
  default int[] committedTokens(STATE state) {
    return null;
  }

  /**
   * Makes the leading {@code prefixTokens} KV rows of {@code donorSlot} available to
   * {@code destSlot}, discarding whatever the destination held. The donor may still be
   * generating: on backends where KV cells are shared by reference this moves no tensor data
   * and leaves the donor intact.
   *
   * <p>{@code 0} (the default) means the backend cannot share KV across slots; the batch engine
   * then falls back to a cold prefill.
   *
   * @param promptTokens length of the destination's prompt; the shared count is clamped below
   *                     it because the final prompt token must be re-evaluated for its logits
   * @return tokens the destination may actually reuse, {@code 0} if nothing was shared
   */
  default int copyKvPrefix(int donorSlot, int destSlot, int prefixTokens, int promptTokens) {
    return 0;
  }

  /**
   * Validates a request before it is queued and measures its prompt. A result whose
   * {@code fitsInContext()} is false makes the batch engine reject the request with a
   * {@code length_prompt} final token instead of queuing it.
   */
  PromptStats validateRequest(REQUEST request);

  /**
   * Advances every active sequence by one decode step and returns at most one token.
   *
   * <p>Called in a loop by the worker thread while any sequence is registered. Backends that
   * produce several tokens per step buffer them and drain one per call. An empty result means
   * nothing was produced this step: either all sequences have finished (see
   * {@link #getFinishReason}) or the backend has stalled (see {@link #hasStalled()}).
   *
   * @throws Exception on a decode failure; the batch engine logs it and calls again
   */
  Optional<EngineOutput<TOKEN, STATE>> processNextBatch() throws Exception;

  /**
   * Whether the backend can no longer make progress on the sequences it still holds. An empty
   * {@link #processNextBatch()} alone cannot say: idle and dead look identical. When this returns
   * {@code true} the batch engine fails every in-flight sequence with reason {@code stalled}.
   * Defaults to {@code false}.
   */
  default boolean hasStalled() {
    return false;
  }

  /**
   * Detaches a sequence from the backend batch and frees its KV cells. Called once per
   * sequence, on completion, stop-string match, cancellation or stall, always before
   * {@link #cleanupSequenceState}.
   */
  void removeSequence(int internalId);

  /**
   * Detaches a sequence, optionally keeping its KV cells resident so the next sequence in the
   * slot can reuse the prefix. The default ignores {@code keepKv} and delegates to
   * {@link #removeSequence(int)}.
   */
  default void removeSequence(int internalId, boolean keepKv) {
    removeSequence(internalId);
  }

  /**
   * The backend's own finish reason for a sequence, empty while it is still generating.
   * Stop strings are matched by the batch engine on decoded text and never show up here.
   */
  Optional<String> getFinishReason(STATE state);

  /** Current token counters for a sequence; polled on every emitted token and at finalization. */
  TokenCountInfo getTokenCounts(STATE state);

  /**
   * Cumulative backend timings for the sequence, or {@code null} if unavailable. Read once at
   * sequence start as a baseline and again at the end; the batch engine reports the difference.
   */
  InferencePerformance buildPerformance(STATE state);

  /**
   * The generation channel the sequence is currently emitting on (reasoning, answer or
   * tool-call markup), stamped on each emitted {@link InferenceToken}. {@code null} (the default)
   * means unclassified and is treated as answer text.
   */
  default TokenChannel channelOf(STATE engineState) {
    return null;
  }

  /**
   * Log-probabilities of the token the sequence just produced, when the request asked for them,
   * stamped on the emitted {@link InferenceToken}. {@code null} (the default) when collection is
   * disabled or unsupported.
   */
  default PositionLogprobs logprobsOf(STATE engineState) {
    return null;
  }

  /**
   * Releases whatever {@code state} still owns after {@link #removeSequence} (samplers, buffers,
   * arenas). Must be idempotent-safe against a state that was never fully started.
   */
  void cleanupSequenceState(STATE state);

  /** Stops the backend and releases every native resource. Called once from engine shutdown. */
  void shutdown();

  /**
   * One token produced by {@link #processNextBatch()}.
   *
   * @param sequenceId slot index of the sequence that produced it
   * @param token the generated token
   */
  record EngineOutput<TOKEN, STATE>(int sequenceId, TOKEN token) {}

  /**
   * Token counters for a sequence.
   *
   * @param inputTokens prompt tokens
   * @param outputTokens generated tokens
   * @param reasoningTokens generated tokens on the reasoning channel ({@code 0} if untracked)
   * @param toolTokens generated tokens on the tool-call channel ({@code 0} if untracked)
   */
  record TokenCountInfo(int inputTokens, int outputTokens, int reasoningTokens, int toolTokens) {}
}
