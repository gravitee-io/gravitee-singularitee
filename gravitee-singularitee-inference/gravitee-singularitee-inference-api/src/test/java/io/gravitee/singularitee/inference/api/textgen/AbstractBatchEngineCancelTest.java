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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.api.EngineAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link AbstractBatchEngine#cancelSequence(int)}.
 *
 * <p>Cancelling an active sequence (client disconnect, context-window guard)
 * must release its slot back to the pool. Before the fix, the cancellation
 * path delegated to {@code finalizeSequence}, which early-returns when the
 * engine reports no finish reason — true for any sequence cancelled before
 * its natural end. The slot was therefore never returned: each cancellation
 * permanently consumed one of {@code maxConcurrentSequences} slots until the
 * engine stopped accepting work entirely.
 */
class AbstractBatchEngineCancelTest {

  /** Bare generation request — only {@code stop()} is consulted on this path. */
  private record FakeRequest(String prompt) implements GenerationRequest {
    @Override
    public Integer maxTokens() {
      return null;
    }

    @Override
    public Float temperature() {
      return null;
    }

    @Override
    public Float topP() {
      return null;
    }

    @Override
    public Float presencePenalty() {
      return null;
    }

    @Override
    public Float frequencyPenalty() {
      return null;
    }

    @Override
    public List<String> stop() {
      return List.of();
    }

    @Override
    public Integer seed() {
      return null;
    }
  }

  /** Minimal in-memory adapter: sequences never finish on their own. */
  private static class FakeAdapter implements EngineAdapter<Void, FakeRequest, String, Object> {

    final List<Integer> removedInternalIds = new ArrayList<>();
    final List<Object> cleanedStates = new ArrayList<>();

    @Override
    public Object createSequenceState(int internalId, FakeRequest request) {
      return new Object();
    }

    @Override
    public PromptStats validateRequest(FakeRequest request) {
      return new PromptStats(1, 4096, 16);
    }

    @Override
    public Optional<EngineOutput<String, Object>> processNextBatch() {
      return Optional.empty();
    }

    @Override
    public void removeSequence(int internalId) {
      removedInternalIds.add(internalId);
    }

    @Override
    public Optional<String> getFinishReason(Object state) {
      return Optional.empty(); // still generating — cancellation case
    }

    @Override
    public TokenCountInfo getTokenCounts(Object state) {
      return new TokenCountInfo(0, 0, 0, 0);
    }

    @Override
    public InferencePerformance buildPerformance(Object state) {
      return null;
    }

    @Override
    public void cleanupSequenceState(Object state) {
      cleanedStates.add(state);
    }

    @Override
    public void shutdown() {}
  }

  private static final class TestEngine
    extends AbstractBatchEngine<Void, FakeRequest, String, Object> {

    TestEngine(BatchEngineConfig config, FakeAdapter adapter) {
      super(config, adapter);
    }
  }

  @Test
  void cancelling_active_sequence_returns_cancelled_final_token() {
    var adapter = new FakeAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    engine.addSequence(1, new FakeRequest("bonjour"));
    var token = engine.cancelSequence(1);

    assertThat(token).isNotNull();
    assertThat(token.isFinal()).isTrue();
    assertThat(token.finishReason()).isEqualTo("cancelled");
    assertThat(adapter.removedInternalIds).containsExactly(0);
    assertThat(adapter.cleanedStates).hasSize(1);
  }

  @Test
  void cancelling_active_sequence_releases_the_slot() {
    // maxConcurrentSequences = 1: sequence 1 takes the only slot. After
    // cancellation, sequence 2 must START (become active) rather than queue.
    // An active sequence is cancellable with a non-null final token; a
    // queued one is silently dropped (null) — which is what happened before
    // the fix, because the slot was never returned.
    var adapter = new FakeAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    engine.addSequence(1, new FakeRequest("premier"));
    assertThat(engine.cancelSequence(1)).isNotNull();

    engine.addSequence(2, new FakeRequest("deuxième"));
    var token = engine.cancelSequence(2);

    assertThat(token)
      .as("sequence 2 should have taken the slot released by the cancelled sequence 1")
      .isNotNull();
    assertThat(token.finishReason()).isEqualTo("cancelled");
  }

  @Test
  void slot_reuse_survives_many_cancellations() {
    // Each cancel/restart cycle must keep exactly one slot in rotation —
    // the original leak made the engine unusable after maxConcurrentSequences
    // cancellations.
    var adapter = new FakeAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    for (int i = 1; i <= 25; i++) {
      engine.addSequence(i, new FakeRequest("req-" + i));
      assertThat(engine.cancelSequence(i)).as("cancellation #%d", i).isNotNull();
    }
  }

  @Test
  void cancelling_unknown_sequence_is_a_noop() {
    var adapter = new FakeAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    assertThat(engine.cancelSequence(42)).isNull();
    assertThat(adapter.removedInternalIds).isEmpty();
  }

  @Test
  void cancelling_pending_sequence_dequeues_without_final_token() {
    var adapter = new FakeAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    engine.addSequence(1, new FakeRequest("active"));
    engine.addSequence(2, new FakeRequest("queued")); // no slot left — goes to pending

    assertThat(engine.cancelSequence(2)).isNull(); // dropped from queue
    assertThat(engine.cancelSequence(1)).isNotNull(); // active one cancels
  }

  @Test
  void cancel_is_not_starved_by_a_running_generation() throws Exception {
    // The worker loop holds the lock during each (expensive) batch step and
    // re-acquires it nanoseconds after releasing it. With an UNFAIR lock the
    // loop barges back in before a parked waiter wakes, so cancelSequence
    // blocks until the generation ends naturally — observed in the field as
    // "cancel does nothing until the sequence terminates". The fair lock
    // bounds the wait to ~one batch step. This adapter never finishes on its
    // own, so without fairness this test times out.
    var adapter = new FakeAdapter() {
      @Override
      public Optional<EngineOutput<String, Object>> processNextBatch() {
        try {
          Thread.sleep(2); // simulate native decode inside the lock
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Optional.of(new EngineOutput<>(0, "tok"));
      }
    };
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);
    engine.start(token -> {});

    engine.addSequence(1, new FakeRequest("endless"));
    Thread.sleep(100); // let the worker loop spin on the generation

    try {
      var token = java.util.concurrent.CompletableFuture.supplyAsync(() ->
        engine.cancelSequence(1)
      ).get(5, java.util.concurrent.TimeUnit.SECONDS);

      assertThat(token).isNotNull();
      assertThat(token.finishReason()).isEqualTo("cancelled");
    } finally {
      engine.close();
    }
  }
}
