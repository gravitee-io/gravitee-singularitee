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

import io.gravitee.singularitee.inference.api.textgen.InferencePerformance;
import io.gravitee.singularitee.inference.api.textgen.PromptStats;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import java.util.Optional;

/**
 * Adapter interface for engine-specific operations.
 * Implementations handle the actual interaction with inference backends.
 *
 * <p>This interface uses the Template Method pattern - the abstract batch engine
 * handles all sequence management, queuing, thread safety, and token emission,
 * while implementations focus only on engine-specific logic.</p>
 *
 * @param <CONFIG> Engine configuration type
 * @param <REQUEST> Generation request type
 * @param <TOKEN> Token type
 * @param <STATE> Engine-specific sequence state type
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface EngineAdapter<CONFIG, REQUEST, TOKEN, STATE> {
  /**
   * Creates a new sequence state for the given internal ID and request.
   * This is called when a sequence is ready to start processing.
   *
   * @param internalId The internal sequence ID (slot index)
   * @param request The generation request
   * @return A new sequence state, or null if the request is invalid
   * @throws Exception if the state cannot be created
   */
  STATE createSequenceState(int internalId, REQUEST request) throws Exception;

  /**
   * Creates a new sequence state that may reuse the first {@code reusePrefixTokens}
   * tokens already KV-resident in the slot (cross-request prefix cache).
   * Default: ignores the reuse hint and delegates to
   * {@link #createSequenceState(int, Object)}.
   *
   * @param internalId The internal sequence ID (slot index)
   * @param request The generation request
   * @param reusePrefixTokens Number of prompt-prefix tokens already in the slot's KV
   * @return A new sequence state, or null if the request is invalid
   * @throws Exception if the state cannot be created
   */
  default STATE createSequenceState(int internalId, REQUEST request, int reusePrefixTokens)
    throws Exception {
    return createSequenceState(internalId, request);
  }

  /**
   * Tokenizes the request's (rendered) prompt for prefix-cache matching.
   * {@code null} (the default) means this backend does not support server-side
   * prompt caching and the batch engine bypasses the slot cache entirely.
   *
   * @param request The generation request
   * @return The prompt token ids, or {@code null} if unsupported
   */
  default int[] tokenizePrompt(REQUEST request) {
    return null;
  }

  /**
   * The token ids currently committed to the slot's KV cache for this sequence
   * (prompt + accepted completion tokens). {@code null} (the default) when the
   * backend cannot report them — the slot is then treated as cold.
   *
   * @param state The sequence state
   * @return The KV-resident token ids, or {@code null} if unsupported
   */
  default int[] committedTokens(STATE state) {
    return null;
  }

  /**
   * Publishes the leading {@code prefixTokens} KV rows of {@code donorSlot} onto
   * {@code destSlot}, so the sequence starting there can skip re-evaluating them.
   * The destination's existing rows are discarded. The donor may still be
   * generating — on backends where a KV cell is shared by reference rather than
   * owned by one sequence, this moves no tensor data and leaves the donor intact.
   *
   * <p>{@code 0} (the default) means the backend cannot share KV across slots, and
   * the batch engine falls back to a cold prefill.
   *
   * @param donorSlot    Slot holding the prefix
   * @param destSlot     Slot to publish it onto
   * @param prefixTokens Leading tokens to share
   * @param promptTokens Length of the destination's prompt; the shared count is clamped below it,
   *                     since the final prompt token must be re-evaluated for its logits
   * @return Tokens the destination may actually reuse; {@code 0} if nothing was shared
   */
  default int copyKvPrefix(int donorSlot, int destSlot, int prefixTokens, int promptTokens) {
    return 0;
  }

  /**
   * Validates a generation request and calculates statistics.
   * Called before queuing to ensure the request is valid.
   *
   * @param request The generation request
   * @return Statistics about the prompt
   */
  PromptStats validateRequest(REQUEST request);

  /**
   * Processes the next batch of tokens for all active sequences.
   * This is called repeatedly by the worker thread until sequences complete.
   *
   * @return An optional output, or empty if no sequences are active
   * @throws Exception if processing fails
   */
  Optional<EngineOutput<TOKEN, STATE>> processNextBatch() throws Exception;

  /**
   * Whether the backend can no longer make progress on the sequences it still holds.
   * An empty {@link #processNextBatch()} alone cannot say: idle and dead look identical.
   * Defaults to {@code false} for adapters that cannot tell them apart.
   */
  default boolean hasStalled() {
    return false;
  }

  /**
   * Removes a sequence from the batch processor.
   * Called when a sequence completes or is cancelled.
   *
   * @param internalId The internal sequence ID
   */
  void removeSequence(int internalId);

  /**
   * Removes a sequence from the batch processor, optionally keeping its KV
   * cells resident so the next sequence in the slot can reuse the prefix.
   * Default: ignores {@code keepKv} and delegates to {@link #removeSequence(int)}.
   *
   * @param internalId The internal sequence ID
   * @param keepKv Whether to retain the slot's KV cache content
   */
  default void removeSequence(int internalId, boolean keepKv) {
    removeSequence(internalId);
  }

  /**
   * Checks if a sequence has finished.
   *
   * @param state The sequence state
   * @return Optional finish reason if finished, empty otherwise
   */
  Optional<String> getFinishReason(STATE state);

  /**
   * Gets token counts from a sequence state.
   *
   * @param state The sequence state
   * @return Token count information
   */
  TokenCountInfo getTokenCounts(STATE state);

  /**
   * Builds performance metrics for a completed sequence.
   *
   * @param state The sequence state
   * @return Performance metrics, or null if not available
   */
  InferencePerformance buildPerformance(STATE state);

  /**
   * Returns the generation channel the sequence is currently emitting on, as
   * classified by the engine (reasoning vs answer vs tool-call markup).
   * Read at token-production time by the batch engine and stamped on the
   * emitted {@link io.gravitee.singularitee.inference.api.textgen.InferenceToken}.
   *
   * <p>Default: {@code null} (unclassified — ANSWER semantics) for engines
   * that do not track a generation state.
   *
   * @param engineState The sequence state
   * @return The current token channel, or {@code null} if unclassified
   */
  default TokenChannel channelOf(STATE engineState) {
    return null;
  }

  /**
   * Returns the log-probability data of the token the sequence just produced,
   * when the request asked for logprobs collection. Read at token-production
   * time by the batch engine and stamped on the emitted
   * {@link io.gravitee.singularitee.inference.api.textgen.InferenceToken}.
   *
   * <p>Default: {@code null} (collection disabled or unsupported).
   *
   * @param engineState The sequence state
   * @return The last produced token's logprobs, or {@code null}
   */
  default io.gravitee.singularitee.inference.api.textgen.PositionLogprobs logprobsOf(
    STATE engineState
  ) {
    return null;
  }

  /**
   * Releases resources associated with a sequence state.
   *
   * @param state The sequence state to cleanup
   */
  void cleanupSequenceState(STATE state);

  /**
   * Stops the batch processor and releases all resources.
   * Called during engine shutdown.
   */
  void shutdown();

  /**
   * Represents the output from processing a batch.
   *
   * @param sequenceId The internal sequence ID that produced this output
   * @param token The generated token
   */
  record EngineOutput<TOKEN, STATE>(int sequenceId, TOKEN token) {}

  /**
   * Token count information for a sequence.
   *
   * @param inputTokens Number of input/prompt tokens
   * @param outputTokens Number of output/generated tokens
   * @param reasoningTokens Number of reasoning tokens (0 if not supported)
   * @param toolTokens Number of tool call tokens (0 if not supported)
   */
  record TokenCountInfo(int inputTokens, int outputTokens, int reasoningTokens, int toolTokens) {}
}
