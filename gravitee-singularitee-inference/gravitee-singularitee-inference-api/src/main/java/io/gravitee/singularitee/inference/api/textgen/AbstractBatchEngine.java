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

import io.gravitee.singularitee.inference.api.EngineAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for batch inference engines.
 * Provides thread-safe sequence management, automatic queuing, stop sequence detection,
 * and token streaming. Engine-specific logic is delegated to an {@link EngineAdapter}.
 *
 * <p>This class handles all the complex orchestration:
 * <ul>
 *   <li>Thread-safe sequence addition, removal, and cancellation</li>
 *   <li>Automatic slot allocation and pending queue management</li>
 *   <li>Stop sequence detection with buffering</li>
 *   <li>Performance tracking and metrics collection</li>
 *   <li>Proper resource cleanup and shutdown</li>
 * </ul>
 *
 * <p>Implementers only need to provide an {@link EngineAdapter} that handles
 * the actual engine-specific operations.</p>
 *
 * @param <CONFIG> Engine configuration type
 * @param <REQUEST> Generation request type
 * @param <TOKEN> Token type
 * @param <STATE> Engine-specific sequence state type
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractBatchEngine<CONFIG, REQUEST extends GenerationRequest, TOKEN, STATE>
  implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBatchEngine.class);
  private final BatchEngineConfig engineConfig;
  private final EngineAdapter<CONFIG, REQUEST, TOKEN, STATE> adapter;
  private final Map<Integer, SequenceState<STATE>> sequences = new ConcurrentHashMap<>();
  private final Map<Integer, Integer> externalToInternal = new ConcurrentHashMap<>();
  private final Deque<QueuedSequence<REQUEST>> pending = new ArrayDeque<>();
  private final Deque<Integer> availableSlots;
  /**
   * Cross-request KV prefix cache bookkeeping, or {@code null} when disabled by
   * configuration. Even when non-null it is bypassed per request whenever the
   * adapter cannot tokenize the prompt ({@code tokenizePrompt} returns null —
   * backend without server-side caching, or a media request). All access is
   * under {@link #lock}.
   */
  private final SlotCache slotCache;
  /**
   * FAIR lock, deliberately. The worker loop re-acquires this lock
   * nanoseconds after releasing it (the gap between two batch iterations),
   * while doing all the expensive native decoding inside the critical
   * section. With an unfair lock the loop barges back in before a parked
   * waiter can wake, starving {@link #cancelSequence} and
   * {@link #addSequence} until the loop parks on {@link #hasWork} — i.e.
   * until every running generation has finished naturally. Fair FIFO
   * handoff bounds their wait to a single batch step (~one token); the
   * fairness overhead is negligible next to the per-iteration decode cost.
   */
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition hasWork = lock.newCondition();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Consumer<InferenceToken<TOKEN>> tokenConsumer;
  private ExecutorService executor;
  private Future<?> workerFuture;

  /**
   * Creates a new batch engine with custom configuration.
   *
   * @param engineConfig The engine configuration
   * @param adapter The engine adapter
   */
  protected AbstractBatchEngine(
    BatchEngineConfig engineConfig,
    EngineAdapter<CONFIG, REQUEST, TOKEN, STATE> adapter
  ) {
    this.engineConfig = Objects.requireNonNull(engineConfig, "engineConfig is required");
    this.adapter = Objects.requireNonNull(adapter, "adapter is required");
    this.availableSlots = new ArrayDeque<>(engineConfig.maxConcurrentSequences());
    for (int i = 0; i < engineConfig.maxConcurrentSequences(); i++) {
      availableSlots.addLast(i);
    }
    this.slotCache = engineConfig.promptCache()
      ? new SlotCache(engineConfig.maxConcurrentSequences(), engineConfig.promptCacheMinTokens())
      : null;
  }

  /**
   * Starts the engine with a token consumer.
   *
   * @param tokenConsumer Callback for receiving generated tokens
   * @throws IllegalStateException if already started
   * @throws NullPointerException if tokenConsumer is null
   */
  public void start(Consumer<InferenceToken<TOKEN>> tokenConsumer) {
    Objects.requireNonNull(tokenConsumer, "tokenConsumer is required");
    this.tokenConsumer = tokenConsumer;
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Engine is already running");
    }

    executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "batch-engine-iterator");
      thread.setUncaughtExceptionHandler((t, e) -> {
        running.set(false);
        LOGGER.error("Uncaught exception in batch engine worker thread: {}", e.getMessage());
      });
      return thread;
    });
    workerFuture = executor.submit(this::runLoop);
  }

  /**
   * Adds a sequence to be processed.
   *
   * <p>If no slots are available, the sequence is queued automatically.
   * If the pending queue is full, the sequence is rejected.</p>
   *
   * @param seqId External sequence ID (client-facing)
   * @param request The generation request
   * @throws IllegalStateException if pending queue is full
   */
  public void addSequence(int seqId, REQUEST request) {
    addSequence(seqId, request, null);
  }

  /**
   * Adds a sequence with an optional client cache-affinity key.
   *
   * @param seqId External sequence ID (client-facing)
   * @param request The generation request
   * @param cacheKey Cache-affinity key for the KV prefix cache, or {@code null}
   * @throws IllegalStateException if pending queue is full
   */
  public void addSequence(int seqId, REQUEST request, String cacheKey) {
    Objects.requireNonNull(request, "request is required");

    // Built under the lock, emitted after release so the downstream stream write
    // never happens inside the critical section (see runLoop).
    InferenceToken<TOKEN> rejected = null;
    lock.lock();
    try {
      // Check for duplicate sequences
      if (externalToInternal.containsKey(seqId) || containsPending(seqId)) {
        return;
      }

      // Validate request
      PromptStats stats = adapter.validateRequest(request);
      if (!stats.fitsInContext()) {
        rejected = buildLengthToken(seqId, stats.promptTokens());
      } else if (pending.size() >= engineConfig.queueCapacity()) {
        // Check if queue is full
        throw new IllegalStateException(
          "Pending queue is full (capacity: " + engineConfig.queueCapacity() + ")"
        );
      } else if (availableSlots.isEmpty()) {
        // Queue or start immediately
        pending.addLast(new QueuedSequence<>(seqId, request, cacheKey));
      } else {
        startSequence(seqId, request, cacheKey);
      }
    } finally {
      lock.unlock();
    }

    if (rejected != null) {
      emitToken(rejected);
    }
  }

  /**
   * Cancels a sequence.
   *
   * @param seqId External sequence ID
   * @return The final token if the sequence was active, null otherwise
   */
  public InferenceToken<TOKEN> cancelSequence(int seqId) {
    lock.lock();
    try {
      // Remove from pending queue
      if (removePending(seqId)) {
        return null;
      }

      // Cancel active sequence
      Integer internalId = externalToInternal.get(seqId);
      if (internalId == null) {
        return null;
      }

      SequenceState<STATE> state = sequences.get(internalId);
      if (state != null && adapter.getFinishReason(state.engineState).isEmpty()) {
        releaseSequence(state);
        // finalizeSequence() would early-return here: a cancelled sequence
        // has no engine finish reason, so it would never release the slot —
        // each cancellation would permanently leak one slot out of
        // maxConcurrentSequences until the engine stops accepting work.
        // Finalize the cancellation explicitly instead.
        return finalizeCancelled(state);
      }
      return null;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Fails every registered sequence after the backend reported it can no longer make
   * progress ({@link EngineAdapter#hasStalled()}).
   *
   * <p>A decode failure takes down the whole batch. Cleanup mirrors the cancellation path,
   * slots included, so the engine keeps serving. Finish reason is {@code "stop"}: OpenAI
   * defines no error value. Call under {@link #lock}; the caller emits the tokens after.
   */
  private void failAllSequences(List<InferenceToken<TOKEN>> out) {
    LOGGER.error(
      "Backend stalled — failing {} in-flight sequence(s) {}: generation was cut short",
      sequences.size(),
      sequences
        .values()
        .stream()
        .map(s -> String.valueOf(s.externalId))
        .toList()
    );
    for (SequenceState<STATE> state : new ArrayList<>(sequences.values())) {
      try {
        adapter.removeSequence(state.conversationId);
      } catch (Exception e) {
        LOGGER.warn(
          "removeSequence({}) failed while stalled: {}",
          state.conversationId,
          e.getMessage()
        );
      }
      if (state.finalSent) {
        sequences.remove(state.conversationId);
        externalToInternal.remove(state.externalId);
        availableSlots.addLast(state.conversationId);
        continue;
      }
      state.finalSent = true;
      updateTokenCounts(state);
      out.add(
        new InferenceToken<>(
          state.externalId,
          null,
          state.index,
          true,
          "stop",
          state.inputTokens,
          state.outputTokens,
          state.reasoningTokens,
          state.toolTokens,
          null
        )
      );
      try {
        adapter.cleanupSequenceState(state.engineState);
      } catch (Exception e) {
        LOGGER.error("Error cleaning up stalled sequence state: {}", e.getMessage());
      }
      sequences.remove(state.conversationId);
      externalToInternal.remove(state.externalId);
      availableSlots.addLast(state.conversationId);
    }
  }

  /**
   * Finalizes a sequence cancelled before its natural end (client disconnect,
   * context-window guard): cleans up engine state, releases the tracking maps
   * and — critically — returns the slot to {@link #availableSlots} so new
   * sequences can start. The native state was already removed by the caller
   * via {@code adapter.removeSequence}.
   *
   * @return a final token with finish reason {@code "cancelled"}, or
   *         {@code null} if the sequence already emitted its final token
   */
  private InferenceToken<TOKEN> finalizeCancelled(SequenceState<STATE> state) {
    if (state == null || state.finalSent) {
      return null;
    }
    state.finalSent = true;

    updateTokenCounts(state);

    InferenceToken<TOKEN> token = new InferenceToken<>(
      state.externalId,
      null,
      state.index,
      true,
      "cancelled",
      state.inputTokens,
      state.outputTokens,
      state.reasoningTokens,
      state.toolTokens,
      adapter.buildPerformance(state.engineState)
    );

    try {
      adapter.cleanupSequenceState(state.engineState);
    } catch (Exception e) {
      LOGGER.error("Error cleaning up cancelled sequence state: {}", e.getMessage());
    }

    sequences.remove(state.conversationId);
    externalToInternal.remove(state.externalId);
    availableSlots.addLast(state.conversationId);

    if (engineConfig.enableAutoStart()) {
      startNextPending();
    }

    return token;
  }

  /**
   * Main worker loop that processes sequences.
   */
  private void runLoop() {
    while (running.get()) {
      // Tokens produced this iteration. Built while holding the lock, emitted
      // after releasing it.
      List<InferenceToken<TOKEN>> out = new ArrayList<>();
      lock.lock();
      try {
        // Wait for work
        while (running.get() && sequences.isEmpty()) {
          hasWork.await();
        }
        if (!running.get()) {
          return;
        }

        // Process next batch
        var optOutput = adapter.processNextBatch();
        if (optOutput.isPresent()) {
          EngineAdapter.EngineOutput<TOKEN, STATE> output = optOutput.get();
          SequenceState<STATE> state = sequences.get(output.sequenceId());
          if (state != null) {
            processOutput(state, output.token(), out);
          }
        }

        emitFinals(out);

        // AFTER emitFinals: that is what completes sequences normally, and on the
        // iteration where a model hits EOS processNextBatch() is already empty.
        // Whatever it left behind while the backend is stalled is truly stranded.
        if (!sequences.isEmpty() && adapter.hasStalled()) {
          failAllSequences(out);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        LOGGER.error("Error processing batch: {}", e.getMessage());
      } finally {
        lock.unlock();
      }

      // Emit OUTSIDE the critical section: token serialization and the downstream
      // stream write must not block native decoding or the addSequence /
      // cancelSequence handshake, which all contend for the same lock.
      for (InferenceToken<TOKEN> token : out) {
        emitToken(token);
      }
    }
  }

  /**
   * Processes a token output for a sequence.
   */
  private void processOutput(
    SequenceState<STATE> state,
    TOKEN token,
    List<InferenceToken<TOKEN>> out
  ) {
    // An output proves this sequence's prompt has been prefilled, which is the
    // earliest point its full prefix can be offered to other requests.
    publishPrefixOnce(state);

    // Handle null or empty tokens
    if (token == null || (token instanceof String && ((String) token).isEmpty())) {
      return;
    }

    // Update token counts from engine state
    updateTokenCounts(state);

    // Convert token to string for stop detection
    String tokenText = token.toString();
    TokenEmission emission = state.consume(tokenText);

    // Stage token if there's text to emit
    if (!emission.text().isEmpty()) {
      if (state.firstTokenNanos == 0) {
        state.firstTokenNanos = System.nanoTime();
        LOGGER.info(
          "seq={} slot={} ttft={}ms",
          state.externalId,
          state.conversationId,
          (state.firstTokenNanos - state.startedNanos) / 1_000_000
        );
      }
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("seq={} token: {}", state.externalId, emission.text());
      }
      out.add(buildToken(state, emission.text(), state.index++, false));
    }

    // A stop string matched: the sequence is over, and this is the only place that knows it.
    // The final token MUST be staged here — dropping it leaves the stream open forever, because
    // releasing the sequence stops the backend from ever reporting a finish reason of its own.
    if (emission.stopMatched()) {
      state.stopMatched = true;
      InferenceToken<TOKEN> finalToken = finalizeSequence(state);
      if (finalToken != null) {
        out.add(finalToken);
      }
    }
  }

  /**
   * Emits final tokens for completed sequences.
   */
  private void emitFinals(List<InferenceToken<TOKEN>> out) {
    for (var entry : sequences.entrySet()) {
      SequenceState<STATE> state = entry.getValue();
      emitFinalIfNeeded(state, out);
    }
  }

  /**
   * Stages the final token for a sequence if it's finished.
   */
  private void emitFinalIfNeeded(SequenceState<STATE> state, List<InferenceToken<TOKEN>> out) {
    if (state == null || state.finalSent) {
      return;
    }

    var finishReason = finishReasonOf(state);
    if (finishReason.isEmpty()) {
      return;
    }

    // Flush pending tokens
    String pending = state.flushPending();
    if (!pending.isEmpty()) {
      out.add(buildToken(state, pending, state.index++, false));
    }

    // Stage final token
    InferenceToken<TOKEN> finalToken = finalizeSequence(state);
    if (finalToken != null) {
      out.add(finalToken);
    }
  }

  /**
   * The sequence's finish reason: a matched stop string wins over the backend's own view.
   *
   * <p>Stop strings are matched here, on decoded text, so the backend has no idea the sequence
   * ended and keeps reporting "still generating". Asking it would strand the stream.
   */
  private Optional<String> finishReasonOf(SequenceState<STATE> state) {
    return state.stopMatched ? Optional.of("stop") : adapter.getFinishReason(state.engineState);
  }

  /**
   * Finalizes a sequence and cleans up resources.
   */
  private InferenceToken<TOKEN> finalizeSequence(SequenceState<STATE> state) {
    if (state == null || state.finalSent) {
      return null;
    }

    var finishReason = finishReasonOf(state);
    if (finishReason.isEmpty()) {
      return null;
    }

    state.finalSent = true;

    // Update token counts before building the final token
    updateTokenCounts(state);

    long now = System.nanoTime();
    long decodeMs = state.firstTokenNanos > 0 ? (now - state.firstTokenNanos) / 1_000_000 : 0;
    LOGGER.info(
      "seq={} slot={} done: ttft={}ms decode={}ms tokens={} ({} tok/s)",
      state.externalId,
      state.conversationId,
      state.firstTokenNanos > 0 ? (state.firstTokenNanos - state.startedNanos) / 1_000_000 : -1,
      decodeMs,
      state.outputTokens,
      decodeMs > 0 ? String.format("%.1f", (state.outputTokens * 1000.0) / decodeMs) : "n/a"
    );

    // Remove from adapter (retaining KV when the slot cache is active)
    releaseSequence(state);

    // Build final token
    InferenceToken<TOKEN> token = new InferenceToken<>(
      state.externalId,
      null,
      state.index,
      true,
      finishReason.get(),
      state.inputTokens,
      state.outputTokens,
      state.reasoningTokens,
      state.toolTokens,
      adapter.buildPerformance(state.engineState)
    );

    // Cleanup engine state
    try {
      adapter.cleanupSequenceState(state.engineState);
    } catch (Exception e) {
      LOGGER.error("Error cleaning up sequence state: {}", e.getMessage());
    }

    // Remove from tracking
    sequences.remove(state.conversationId);
    externalToInternal.remove(state.externalId);
    availableSlots.addLast(state.conversationId);

    // Start next pending if enabled
    if (engineConfig.enableAutoStart()) {
      startNextPending();
    }

    return token;
  }

  /**
   * Starts a sequence for processing.
   */
  private void startSequence(int seqId, REQUEST request, String cacheKey) {
    if (availableSlots.isEmpty()) {
      pending.addLast(new QueuedSequence<>(seqId, request, cacheKey));
      return;
    }

    // Prefix-cache slot selection: tokenize the prompt (vocab-only, cheap, safe
    // on the caller thread) and let the SlotCache pick the free slot with the
    // longest reusable prefix. Adapters without server-side caching return null
    // tokens and fall through to plain FIFO slot allocation.
    Integer internalId = null;
    int reusePrefixTokens = 0;
    int promptTokenCount = 0;
    if (slotCache != null) {
      int[] promptTokens = null;
      try {
        promptTokens = adapter.tokenizePrompt(request);
      } catch (Exception e) {
        LOGGER.warn("tokenizePrompt failed — bypassing prompt cache: {}", e.getMessage());
      }
      if (promptTokens != null) {
        promptTokenCount = promptTokens.length;
        SlotCache.Selection selection = slotCache.acquire(
          cacheKey,
          promptTokens,
          Set.copyOf(availableSlots)
        );
        if (selection != null) {
          internalId = selection.slot();
          reusePrefixTokens = selection.reusePrefixTokens();
          availableSlots.remove(internalId);
          if (selection.requiresCopy()) {
            // The prefix lives on a slot that is still generating. Republish it onto
            // ours — cells are shared, not copied — and correct the slot cache to
            // whatever was actually shared, so a concurrent acquire cannot claim rows
            // this slot does not hold.
            int copied = 0;
            try {
              copied = adapter.copyKvPrefix(
                selection.donorSlot(),
                internalId,
                reusePrefixTokens,
                promptTokens.length
              );
            } catch (Exception e) {
              LOGGER.warn(
                "copyKvPrefix({} -> {}) failed — cold prefill: {}",
                selection.donorSlot(),
                internalId,
                e.getMessage()
              );
            }
            reusePrefixTokens = copied;
            slotCache.publish(internalId, copied > 0 ? Arrays.copyOf(promptTokens, copied) : null);
          }
        }
      }
    }
    if (internalId == null) {
      internalId = availableSlots.pollFirst();
    }
    if (internalId == null) {
      pending.addLast(new QueuedSequence<>(seqId, request, cacheKey));
      return;
    }

    try {
      STATE engineState = slotCache == null
        ? adapter.createSequenceState(internalId, request)
        : adapter.createSequenceState(internalId, request, reusePrefixTokens);
      if (engineState == null) {
        returnSlot(internalId);
        return;
      }

      if (slotCache != null && promptTokenCount > 0) {
        LOGGER.info(
          "seq={} slot={} reused_prefix={}/{}",
          seqId,
          internalId,
          reusePrefixTokens,
          promptTokenCount
        );
      }

      SequenceState<STATE> state = new SequenceState<>(
        internalId,
        seqId,
        engineState,
        request.stop()
      );
      state.cacheKey = cacheKey;
      sequences.put(internalId, state);
      externalToInternal.put(seqId, internalId);
      hasWork.signalAll();
    } catch (Exception e) {
      LOGGER.error("Error starting sequence: {}", e.getMessage());
      returnSlot(internalId);
    }
  }

  /**
   * Widens a running slot's advertised prefix to everything now KV-resident, once
   * its prompt has been prefilled. This is what lets a burst of requests behind one
   * system prompt share it: without it a slot only becomes a donor after it
   * <em>finishes</em>, so every concurrent sibling would prefill the prompt in full.
   * Runs once per sequence; a backend that cannot report committed tokens simply
   * never donates.
   */
  private void publishPrefixOnce(SequenceState<STATE> state) {
    if (slotCache == null || state.prefixPublished) {
      return;
    }
    state.prefixPublished = true;
    try {
      slotCache.publish(state.conversationId, adapter.committedTokens(state.engineState));
    } catch (Exception e) {
      LOGGER.warn(
        "committedTokens({}) failed — slot will not donate: {}",
        state.conversationId,
        e.getMessage()
      );
      slotCache.publish(state.conversationId, null);
    }
  }

  /** Returns an unused slot to the free pool, marking its cached KV cold. */
  private void returnSlot(int internalId) {
    if (slotCache != null) {
      slotCache.invalidate(internalId);
    }
    availableSlots.addLast(internalId);
  }

  /**
   * Removes a finished/cancelled sequence from the adapter. With the slot cache
   * active, the committed KV tokens are recorded and the KV is retained so the
   * next request in this slot can reuse the shared prefix; any adapter failure
   * invalidates the slot and drops the KV instead. Idempotent per sequence.
   */
  private void releaseSequence(SequenceState<STATE> state) {
    if (state.slotReleased) {
      return;
    }
    state.slotReleased = true;
    if (slotCache == null) {
      adapter.removeSequence(state.conversationId);
      return;
    }
    try {
      int[] committed = adapter.committedTokens(state.engineState);
      if (committed != null && committed.length > 0) {
        adapter.removeSequence(state.conversationId, true);
        slotCache.release(state.conversationId, state.cacheKey, committed);
      } else {
        adapter.removeSequence(state.conversationId, false);
        slotCache.invalidate(state.conversationId);
      }
    } catch (Exception e) {
      LOGGER.warn(
        "Failed to retain KV for slot {} — invalidating: {}",
        state.conversationId,
        e.getMessage()
      );
      slotCache.invalidate(state.conversationId);
      try {
        adapter.removeSequence(state.conversationId, false);
      } catch (Exception removeError) {
        LOGGER.error(
          "removeSequence({}) failed: {}",
          state.conversationId,
          removeError.getMessage()
        );
      }
    }
  }

  /**
   * Starts the next pending sequence if a slot is available.
   */
  private void startNextPending() {
    if (pending.isEmpty() || availableSlots.isEmpty()) {
      return;
    }
    QueuedSequence<REQUEST> next = pending.pollFirst();
    if (next != null) {
      startSequence(next.seqId(), next.request(), next.cacheKey());
    }
  }

  /**
   * Removes a sequence from the pending queue.
   */
  private boolean removePending(int seqId) {
    var iterator = pending.iterator();
    while (iterator.hasNext()) {
      if (iterator.next().seqId() == seqId) {
        iterator.remove();
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if a sequence ID is in the pending queue.
   */
  private boolean containsPending(int seqId) {
    return pending.stream().anyMatch(q -> q.seqId() == seqId);
  }

  /**
   * Builds a length-failed token (emitted by the caller, outside the lock).
   */
  private InferenceToken<TOKEN> buildLengthToken(int seqId, int promptTokens) {
    return new InferenceToken<>(seqId, null, 0, true, "length", promptTokens, 0, 0, 0, null);
  }

  /**
   * Builds an inference token.
   */
  @SuppressWarnings("unchecked")
  private InferenceToken<TOKEN> buildToken(
    SequenceState<STATE> state,
    String text,
    int index,
    boolean isFinal
  ) {
    TOKEN token;
    if (state.tokenType == String.class) {
      token = (TOKEN) text;
    } else {
      // For non-string token types, you'd need to implement custom conversion
      token = null;
    }

    var finishReason = adapter.getFinishReason(state.engineState);
    // Stamp the engine's per-token generation channel (reasoning / answer /
    // tool) read at production time. Caveat: under MTP fused rounds the
    // engine state may have advanced past earlier buffered tokens of the
    // same round, so boundary tokens can be off by one round — acceptable.
    TokenChannel channel = isFinal ? null : adapter.channelOf(state.engineState);
    return new InferenceToken<>(
      state.externalId,
      token,
      index,
      isFinal,
      finishReason.orElse(null),
      state.inputTokens,
      state.outputTokens,
      state.reasoningTokens,
      state.toolTokens,
      isFinal ? adapter.buildPerformance(state.engineState) : null,
      channel,
      isFinal ? null : adapter.logprobsOf(state.engineState)
    );
  }

  /**
   * Safely emits a token to the consumer.
   */
  private void emitToken(InferenceToken<TOKEN> token) {
    Consumer<InferenceToken<TOKEN>> consumer = this.tokenConsumer;
    if (consumer != null) {
      consumer.accept(token);
    }
  }

  /**
   * Updates token counts from the engine state.
   */
  private void updateTokenCounts(SequenceState<STATE> state) {
    var counts = adapter.getTokenCounts(state.engineState);
    state.inputTokens = counts.inputTokens();
    state.outputTokens = counts.outputTokens();
    state.reasoningTokens = counts.reasoningTokens();
    state.toolTokens = counts.toolTokens();
  }

  @Override
  public void close() {
    running.set(false);
    lock.lock();
    try {
      hasWork.signalAll();
    } finally {
      lock.unlock();
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    if (workerFuture != null) {
      workerFuture.cancel(true);
    }
    adapter.shutdown();
  }
}
