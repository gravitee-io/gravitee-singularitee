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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.EngineAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the continuous-batching scheduling loop of {@link AbstractBatchEngine}:
 * slot admission, the pending queue, queue overflow, sequence de-duplication,
 * auto-start of queued work when a slot frees, in-order token streaming, natural
 * (EOS) finalization, fair interleaving of concurrent sequences, and rejection of
 * prompts that exceed the context window.
 *
 * <p>The cancellation/slot-leak path is covered separately by
 * {@link AbstractBatchEngineCancelTest}. These tests drive the engine through a
 * scripted in-memory {@link EngineAdapter} so no native backend is required.
 *
 * <p><b>Determinism:</b> all sequences are admitted <em>before</em> the worker is
 * started, so slot assignment (and therefore round-robin order) is fixed and the
 * worker never observes a partially-populated batch. Completion is awaited on the
 * emitted-token stream rather than on wall-clock sleeps, and the "auto-start
 * disabled" case is proven by the adapter's sequence-creation count — never by a
 * timed wait — so there are no races.
 */
class AbstractBatchEngineSchedulingTest {

  /**
   * Generation request carrying a deterministic token script. {@code tokens} are
   * the tokens the adapter will "decode" for the sequence; {@code finishReason}
   * is reported once the script is exhausted; {@code oversized} forces
   * {@link EngineAdapter#validateRequest} to report a prompt that does not fit.
   */
  private record FakeRequest(
    String prompt,
    List<String> tokens,
    String finishReason,
    List<String> stop,
    boolean oversized
  ) implements GenerationRequest {
    static FakeRequest of(String prompt, String... toks) {
      return new FakeRequest(prompt, List.of(toks), "eos", List.of(), false);
    }

    static FakeRequest withStop(String prompt, List<String> stop, String... toks) {
      return new FakeRequest(prompt, List.of(toks), "eos", stop, false);
    }

    static FakeRequest oversized(String prompt) {
      return new FakeRequest(prompt, List.of(), "length", List.of(), true);
    }

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
    public Integer seed() {
      return null;
    }
  }

  /**
   * Round-robin scripted adapter. Each {@code processNextBatch()} emits one token
   * for the next active sequence that still has script left (continuous batching:
   * one decode step per sequence per turn). A sequence reports its finish reason
   * once its script is drained. All state is touched only under the engine lock.
   */
  private static final class ScriptedAdapter
    implements EngineAdapter<Void, FakeRequest, String, ScriptedAdapter.Gen> {

    static final class Gen {

      final String[] tokens;
      final String finishReason;
      int pos;

      Gen(String[] tokens, String finishReason) {
        this.tokens = tokens;
        this.finishReason = finishReason;
      }

      boolean done() {
        return pos >= tokens.length;
      }
    }

    private final List<Integer> order = new ArrayList<>();
    private final Map<Integer, Gen> active = new HashMap<>();
    private int cursor = 0;

    /** Number of sequences ever admitted (createSequenceState). Volatile for cross-thread reads. */
    private volatile int created = 0;

    @Override
    public Gen createSequenceState(int internalId, FakeRequest request) {
      Gen gen = new Gen(request.tokens().toArray(new String[0]), request.finishReason());
      active.put(internalId, gen);
      order.add(internalId);
      created++;
      return gen;
    }

    @Override
    public PromptStats validateRequest(FakeRequest request) {
      return request.oversized() ? new PromptStats(5000, 4096, 16) : new PromptStats(1, 4096, 16);
    }

    @Override
    public Optional<EngineOutput<String, Gen>> processNextBatch() {
      if (order.isEmpty()) {
        return Optional.empty();
      }
      for (int k = 0; k < order.size(); k++) {
        int idx = (cursor + k) % order.size();
        int id = order.get(idx);
        Gen gen = active.get(id);
        if (gen != null && !gen.done()) {
          String token = gen.tokens[gen.pos++];
          cursor = (idx + 1) % order.size();
          return Optional.of(new EngineOutput<>(id, token));
        }
      }
      return Optional.empty();
    }

    @Override
    public void removeSequence(int internalId) {
      active.remove(internalId);
      order.remove((Integer) internalId);
      cursor = order.isEmpty() ? 0 : cursor % order.size();
    }

    @Override
    public Optional<String> getFinishReason(Gen state) {
      return (state != null && state.done()) ? Optional.of(state.finishReason) : Optional.empty();
    }

    @Override
    public TokenCountInfo getTokenCounts(Gen state) {
      return new TokenCountInfo(1, state == null ? 0 : state.pos, 0, 0);
    }

    @Override
    public InferencePerformance buildPerformance(Gen state) {
      return null;
    }

    @Override
    public void cleanupSequenceState(Gen state) {}

    @Override
    public void shutdown() {}
  }

  private static final class TestEngine
    extends AbstractBatchEngine<Void, FakeRequest, String, ScriptedAdapter.Gen> {

    TestEngine(BatchEngineConfig config, ScriptedAdapter adapter) {
      super(config, adapter);
    }
  }

  /** Collects emitted tokens from the worker thread; reads take a snapshot under lock. */
  private static final class Collector {

    private final List<InferenceToken<String>> tokens = Collections.synchronizedList(
      new ArrayList<>()
    );

    void accept(InferenceToken<String> token) {
      tokens.add(token);
    }

    List<InferenceToken<String>> snapshot() {
      synchronized (tokens) {
        return new ArrayList<>(tokens);
      }
    }

    long finals() {
      return snapshot().stream().filter(InferenceToken::isFinal).count();
    }

    long finalsFor(int seqId) {
      return snapshot()
        .stream()
        .filter(InferenceToken::isFinal)
        .filter(t -> t.seqId() == seqId)
        .count();
    }

    String streamedTextFor(int seqId) {
      StringBuilder sb = new StringBuilder();
      for (InferenceToken<String> t : snapshot()) {
        if (!t.isFinal() && t.seqId() == seqId && t.token() != null) {
          sb.append(t.token());
        }
      }
      return sb.toString();
    }
  }

  /** Polls until the condition holds; fails (rather than hangs) if it never does. */
  private static void awaitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("condition not met within 10s");
  }

  @Nested
  @DisplayName("Admission, queue and overflow (synchronous, no worker)")
  class Admission {

    @Test
    @DisplayName("fills every slot, queues the rest, then rejects once the queue is full")
    void fills_slots_then_queues_then_overflows() {
      var engine = new TestEngine(BatchEngineConfig.of(2, 2), new ScriptedAdapter());

      // 2 slots -> active, next 2 -> pending (queue capacity 2)
      engine.addSequence(1, FakeRequest.of("a"));
      engine.addSequence(2, FakeRequest.of("b"));
      engine.addSequence(3, FakeRequest.of("c"));
      engine.addSequence(4, FakeRequest.of("d"));

      assertThatThrownBy(() -> engine.addSequence(5, FakeRequest.of("e")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("queue is full");
    }

    @Test
    @DisplayName("duplicate seqId is ignored — consumes neither a slot nor a queue entry")
    void duplicate_seqId_is_ignored() {
      // 1 slot + 1 queue entry. Without de-dup the duplicate adds would fill the
      // queue and make the *second distinct* sequence overflow.
      var engine = new TestEngine(BatchEngineConfig.of(1, 1), new ScriptedAdapter());

      engine.addSequence(1, FakeRequest.of("a")); // active
      engine.addSequence(1, FakeRequest.of("a")); // duplicate active -> no-op

      engine.addSequence(2, FakeRequest.of("b")); // queued (size 1)
      engine.addSequence(2, FakeRequest.of("b")); // duplicate pending -> no-op

      // Only seq2 occupies the queue, so a third distinct sequence overflows.
      assertThatThrownBy(() -> engine.addSequence(3, FakeRequest.of("c"))).isInstanceOf(
        IllegalStateException.class
      );
    }
  }

  @Nested
  @DisplayName("Streaming and finalization (running worker)")
  class Streaming {

    @Test
    @DisplayName(
      "streams tokens in order then emits exactly one final token with the finish reason"
    )
    void streams_in_order_then_final() {
      var engine = new TestEngine(BatchEngineConfig.of(1), new ScriptedAdapter());
      var collector = new Collector();
      // Admit before starting the worker: no partial-batch race.
      engine.addSequence(7, FakeRequest.of("prompt", "h", "e", "l", "l", "o"));
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finalsFor(7) == 1);

        assertThat(collector.streamedTextFor(7)).isEqualTo("hello");
        var last = collector
          .snapshot()
          .stream()
          .filter(InferenceToken::isFinal)
          .findFirst()
          .orElseThrow();
        assertThat(last.finishReason()).isEqualTo("eos");
        assertThat(last.completionTokens()).isEqualTo(5);
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("a completed sequence auto-starts the next pending one")
    void completed_sequence_auto_starts_pending() {
      // 1 slot, auto-start on (default). seq2 can only run once seq1 finishes
      // and releases the slot.
      var adapter = new ScriptedAdapter();
      var engine = new TestEngine(BatchEngineConfig.of(1), adapter);
      var collector = new Collector();
      engine.addSequence(1, FakeRequest.of("p1", "a", "b"));
      engine.addSequence(2, FakeRequest.of("p2", "c")); // no slot -> pending
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finals() == 2);

        assertThat(collector.finalsFor(1)).isEqualTo(1);
        assertThat(collector.finalsFor(2)).isEqualTo(1);
        assertThat(collector.streamedTextFor(1)).isEqualTo("ab");
        assertThat(collector.streamedTextFor(2)).isEqualTo("c");
        assertThat(adapter.created).isEqualTo(2);
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("with auto-start disabled, a queued sequence is never admitted after a slot frees")
    void auto_start_disabled_leaves_pending_idle() {
      var adapter = new ScriptedAdapter();
      var engine = new TestEngine(new BatchEngineConfig(1, 100, false), adapter);
      var collector = new Collector();
      engine.addSequence(1, FakeRequest.of("p1", "a"));
      engine.addSequence(2, FakeRequest.of("p2", "b")); // no slot -> pending
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finalsFor(1) == 1);

        // Deterministic: nothing ever creates seq2's state when auto-start is off,
        // so once seq1's final token is observed the creation count is fixed at 1.
        assertThat(adapter.created).isEqualTo(1);
        assertThat(collector.finalsFor(2)).isZero();
        assertThat(collector.streamedTextFor(2)).isEmpty();
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("concurrent sequences are interleaved (continuous batching), each completing once")
    void interleaves_concurrent_sequences() {
      var engine = new TestEngine(BatchEngineConfig.of(3), new ScriptedAdapter());
      var collector = new Collector();
      // Admit all three before starting: slots 0,1,2 -> seq 1,2,3, fixed order.
      engine.addSequence(1, FakeRequest.of("p1", "1a", "1b"));
      engine.addSequence(2, FakeRequest.of("p2", "2a", "2b"));
      engine.addSequence(3, FakeRequest.of("p3", "3a", "3b"));
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finals() == 3);

        assertThat(collector.finalsFor(1)).isEqualTo(1);
        assertThat(collector.finalsFor(2)).isEqualTo(1);
        assertThat(collector.finalsFor(3)).isEqualTo(1);

        // Round-robin: the first three streamed tokens come from three distinct
        // sequences rather than one sequence draining before the next starts.
        List<Integer> firstThreeSeqIds = collector
          .snapshot()
          .stream()
          .filter(t -> !t.isFinal())
          .map(InferenceToken::seqId)
          .limit(3)
          .toList();
        assertThat(firstThreeSeqIds).containsExactlyInAnyOrder(1, 2, 3);

        // Per-sequence order is preserved.
        assertThat(collector.streamedTextFor(1)).isEqualTo("1a1b");
        assertThat(collector.streamedTextFor(2)).isEqualTo("2a2b");
        assertThat(collector.streamedTextFor(3)).isEqualTo("3a3b");
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("a prompt that exceeds the context emits 'length' and does not consume a slot")
    void oversized_prompt_emits_length_without_consuming_slot() {
      var adapter = new ScriptedAdapter();
      var engine = new TestEngine(BatchEngineConfig.of(1), adapter);
      var collector = new Collector();
      engine.start(collector::accept);
      try {
        // Rejected up-front (emitted synchronously on this thread); must not take
        // the single slot, and must not create any sequence state.
        engine.addSequence(99, FakeRequest.oversized("way-too-long"));

        assertThat(collector.finalsFor(99)).isEqualTo(1);
        var rejected = collector
          .snapshot()
          .stream()
          .filter(t -> t.seqId() == 99)
          .findFirst()
          .orElseThrow();
        assertThat(rejected.isFinal()).isTrue();
        assertThat(rejected.finishReason()).isEqualTo("length_prompt");
        assertThat(adapter.created).isZero();

        // The slot is still free, so a normal sequence runs to completion.
        engine.addSequence(1, FakeRequest.of("ok", "x"));
        awaitUntil(() -> collector.finalsFor(1) == 1);
        assertThat(collector.streamedTextFor(1)).isEqualTo("x");
        assertThat(adapter.created).isEqualTo(1);
      } finally {
        engine.close();
      }
    }
  }

  @Nested
  @DisplayName("Stop strings (running worker)")
  class StopStrings {

    /**
     * Stop strings are matched here, on decoded text — the backend knows nothing about them and
     * goes on reporting "still generating". The final token therefore has to be produced by the
     * stop-match path itself. When it was not, the sequence was detached from the backend (so no
     * finish reason could ever arrive) while the stream stayed open: the client hung until it
     * timed out, with a complete answer already sitting in its buffer.
     */
    @Test
    @DisplayName("a matched stop string ends the stream with a final 'stop' token")
    void stop_match_emits_a_final_token() {
      var engine = new TestEngine(BatchEngineConfig.of(1), new ScriptedAdapter());
      var collector = new Collector();
      engine.addSequence(
        1,
        FakeRequest.withStop("p", List.of("<|observation|>"), "a", "b", "<|observation|>", "junk")
      );
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finalsFor(1) == 1);

        var last = collector
          .snapshot()
          .stream()
          .filter(InferenceToken::isFinal)
          .findFirst()
          .orElseThrow();
        assertThat(last.finishReason()).isEqualTo("stop");
        assertThat(collector.streamedTextFor(1))
          .as("the stop string and anything after it are cut")
          .isEqualTo("ab");
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("a stop string split across tokens still matches")
    void stop_match_spanning_several_tokens() {
      var engine = new TestEngine(BatchEngineConfig.of(1), new ScriptedAdapter());
      var collector = new Collector();
      engine.addSequence(
        1,
        FakeRequest.withStop("p", List.of("<|observation|>"), "hi", "<|obser", "vation|>", "junk")
      );
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finalsFor(1) == 1);
        assertThat(collector.streamedTextFor(1)).isEqualTo("hi");
      } finally {
        engine.close();
      }
    }

    @Test
    @DisplayName("the slot is released, so the next sequence runs")
    void stop_match_releases_the_slot() {
      var engine = new TestEngine(BatchEngineConfig.of(1), new ScriptedAdapter());
      var collector = new Collector();
      engine.addSequence(
        1,
        FakeRequest.withStop("p", List.of("<|observation|>"), "a", "<|observation|>")
      );
      engine.start(collector::accept);
      try {
        awaitUntil(() -> collector.finalsFor(1) == 1);
        engine.addSequence(2, FakeRequest.of("q", "x", "y"));
        awaitUntil(() -> collector.finalsFor(2) == 1);
        assertThat(collector.streamedTextFor(2)).isEqualTo("xy");
      } finally {
        engine.close();
      }
    }
  }
}
