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

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.function.Consumer;

/**
 * A streaming text-generation engine (llama.cpp or vLLM backend).
 *
 * <p>The engine is callback-driven: callers register a {@link Consumer} of
 * {@link ModelEngineToken} at startup time and then submit sequences via
 * {@link #rxAddSequence}. Tokens are delivered asynchronously to the consumer;
 * the final token for a sequence has {@link ModelEngineToken#isFinal()} set to
 * {@code true}.
 *
 * <p>{@link #rxAddSequence} returns a {@link Completable} that completes when the
 * final token for the sequence has been delivered to the consumer. This allows
 * callers to compose reactive chains without blocking on a {@code CountDownLatch}.
 *
 * <p>This interface is {@code non-sealed} so that the adapter layer can implement
 * it with engine-specific concrete classes without leaking those classes above the
 * adapter boundary.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public non-sealed interface TextGenEngine extends ModelEngine {
  @Override
  default ModelEngineType type() {
    return ModelEngineType.TEXT_GEN;
  }

  @Override
  default String task() {
    return ModelTasks.TEXT_GENERATION;
  }

  /**
   * Starts the engine's internal batch-processing loop and registers the token consumer.
   * Must be called exactly once before {@link #rxAddSequence}.
   *
   * @param tokenConsumer receives every token produced by this engine
   */
  void start(Consumer<ModelEngineToken> tokenConsumer);

  /**
   * Queues a generation request as a new sequence and returns a {@link Completable}
   * that completes when the final token for this sequence has been delivered to the
   * token consumer registered via {@link #start}.
   *
   * <p>Implementations must never block the calling thread — they subscribe to the
   * underlying generation stream and relay tokens to the consumer asynchronously.
   *
   * @param seqId   server-assigned internal sequence identifier (must be unique
   *                across all currently active sequences on this engine)
   * @param request the generation parameters
   * @return a {@link Completable} that completes on the final token, or errors on failure
   */
  Completable rxAddSequence(int seqId, TextGenRequest request);

  /**
   * Returns a hot, backpressure-aware token stream for a single sequence.
   *
   * <p>Subscribe to this <em>before</em> (or atomically with) {@link #rxAddSequence}: the
   * stream is keyed by {@code seqId} and buffers tokens as soon as the sequence produces
   * them. It {@code onComplete}s after the final token and {@code onError}s if the
   * per-sequence buffer overflows (a client that cannot keep up) or the engine fails.
   * Cancelling the subscription cancels the sequence, exactly like disposing the
   * {@link #rxAddSequence} {@link Completable}.
   *
   * <p>Pairs with {@link #rxAddSequence} by {@code seqId}: {@code rxStream} carries the
   * tokens, {@code rxAddSequence} submits the request and signals lifecycle/latency.
   *
   * <p>The default throws — engines that only deliver via the {@link #start(Consumer)}
   * callback do not expose a per-sequence reactive stream.
   *
   * @param seqId the sequence whose tokens to stream
   * @return a per-sequence {@link Flowable} of tokens
   */
  default Flowable<ModelEngineToken> rxStream(int seqId) {
    throw new UnsupportedOperationException(
      getClass().getSimpleName() + " does not support rxStream"
    );
  }

  /**
   * Cancels a running sequence (e.g. the client disconnected mid-stream).
   * Stops generation for that sequence as soon as the engine allows and
   * releases its resources; no further tokens are delivered for it.
   *
   * <p>Idempotent and safe to call for already-finished or unknown sequence
   * ids — implementations must treat that as a no-op. The default does
   * nothing: engines whose cancellation is driven by reactive disposal of
   * the {@link #rxAddSequence} subscription (e.g. the remote gRPC proxy,
   * where disposing the subscription cancels the underlying call) need not
   * override it.
   *
   * @param seqId the sequence to cancel
   */
  default void cancelSequence(int seqId) {}

  /**
   * Returns the raw Jinja2 chat template string from the model (GGUF or HuggingFace).
   * Returns {@code null} if the engine cannot provide a template.
   */
  default String chatTemplateString() {
    return null;
  }

  /**
   * The literal text of every token this model parses as special (control / user-defined) —
   * {@code <|im_start|>}, {@code <|channel|>}, {@code <start_of_turn>}, and so on.
   *
   * <p>Prompts are tokenized with special-token parsing enabled, which is required for the chat
   * template's own scaffolding to become real control tokens. The same pass applies to message
   * text, so a caller whose message contains one of these strings would have it tokenized as the
   * control token rather than as text — forging conversation structure from inside a message.
   * Callers neutralise these strings in caller-supplied text before rendering.
   *
   * <p>Longest first, so replacing them in order cannot let a short marker consume part of a
   * longer one. Empty (the default) means the engine cannot enumerate them and no neutralisation
   * is applied.
   */
  default java.util.List<String> specialTokenTexts() {
    return java.util.List.of();
  }

  /**
   * Returns the model's context window size in tokens (e.g. llama.cpp {@code n_ctx}),
   * or {@code 0} when unknown.
   *
   * <p>When greater than zero, the engine guards every sequence against overrunning
   * this window: once {@code promptTokens + completionTokens} reaches a fixed fraction
   * of this size, generation is stopped and the sequence finishes with reason
   * {@code "length"}. A value of {@code 0} disables the guard.
   */
  default int contextSize() {
    return 0;
  }

  /**
   * Counts the tokens in {@code text} using the model's own tokenizer.
   *
   * <p>Returns {@code -1} when the engine has no tokenizer to consult
   * (the default) — callers must then fall back to an estimation heuristic
   * (e.g. {@code EstimatedTokens}).
   *
   * @param text the text to tokenize
   * @return the exact token count, or {@code -1} when unsupported
   */
  default int countTokens(String text) {
    return -1;
  }

  /**
   * Returns the BOS (beginning-of-sentence) token text.
   */
  default String bosToken() {
    return "";
  }

  /**
   * Returns the EOS (end-of-sentence) token text.
   */
  default String eosToken() {
    return "";
  }
}
