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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the degenerate-run guard of {@link AbstractBatchEngine}: a sequence
 * emitting the same token text back-to-back hundreds of times (sampler loops,
 * escape-inflation spirals) never recovers on its own, so after 256 identical
 * consecutive emissions the engine cuts it with a final {@code "stop"} token
 * instead of burning to the token cap. Distinct-but-repeating output (alternating
 * tokens) must never trip the guard: only an unbroken identical run counts.
 */
class AbstractBatchEngineDegenerateRunTest {

  /** Identical consecutive emissions before the engine cuts the sequence. */
  private static final int DEGENERATE_RUN_LIMIT = 256;

  private record FakeRequest(String prompt, List<String> tokens) implements GenerationRequest {
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

  /**
   * Scripted single-sequence adapter: each {@code processNextBatch()} emits the next
   * token of the script; once drained it reports {@code "eos"} as the finish reason.
   */
  private static final class ScriptedAdapter
    implements EngineAdapter<Void, FakeRequest, String, ScriptedAdapter.Gen> {

    static final class Gen {

      final List<String> tokens;
      int pos;

      Gen(List<String> tokens) {
        this.tokens = tokens;
      }

      boolean done() {
        return pos >= tokens.size();
      }
    }

    private Gen active;
    private int activeId = -1;

    @Override
    public Gen createSequenceState(int internalId, FakeRequest request) {
      active = new Gen(request.tokens());
      activeId = internalId;
      return active;
    }

    @Override
    public PromptStats validateRequest(FakeRequest request) {
      return new PromptStats(1, 4096, 16);
    }

    @Override
    public Optional<EngineOutput<String, Gen>> processNextBatch() {
      if (active == null || active.done()) {
        return Optional.empty();
      }
      return Optional.of(new EngineOutput<>(activeId, active.tokens.get(active.pos++)));
    }

    @Override
    public void removeSequence(int internalId) {
      if (internalId == activeId) {
        active = null;
        activeId = -1;
      }
    }

    @Override
    public Optional<String> getFinishReason(Gen state) {
      return (state != null && state.done()) ? Optional.of("eos") : Optional.empty();
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

  private static List<InferenceToken<String>> runToCompletion(List<String> script) {
    var engine = new TestEngine(BatchEngineConfig.of(1), new ScriptedAdapter());
    List<InferenceToken<String>> received = Collections.synchronizedList(new ArrayList<>());
    var finalToken = new CountDownLatch(1);
    engine.addSequence(1, new FakeRequest("prompt", script));
    engine.start(token -> {
      received.add(token);
      if (token.isFinal()) {
        finalToken.countDown();
      }
    });
    try {
      assertThat(finalToken.await(15, TimeUnit.SECONDS)).as("the sequence must complete").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      engine.close();
    }
    synchronized (received) {
      return new ArrayList<>(received);
    }
  }

  @Test
  @DisplayName("an identical run past the limit is cut with a final 'stop' token")
  void identical_run_past_the_limit_is_cut() {
    // Well past the limit: without the guard the whole script would stream.
    List<String> script = Collections.nCopies(DEGENERATE_RUN_LIMIT + 50, "loop");

    var received = runToCompletion(script);

    var finals = received.stream().filter(InferenceToken::isFinal).toList();
    assertThat(finals).hasSize(1);
    assertThat(finals.get(0).finishReason())
      .as("a degenerate cut is surfaced as a stop, not the backend's finish reason")
      .isEqualTo("stop");

    // The token whose emission reaches the limit is dropped, so exactly
    // DEGENERATE_RUN_LIMIT - 1 copies were streamed — never the full script.
    long streamed = received
      .stream()
      .filter(t -> !t.isFinal())
      .count();
    assertThat(streamed).isEqualTo(DEGENERATE_RUN_LIMIT - 1);
  }

  @Test
  @DisplayName("alternating tokens never trip the guard, however long the output")
  void alternating_tokens_do_not_trip_the_guard() {
    // Same volume as the degenerate case, but the run is broken on every token.
    List<String> script = new ArrayList<>();
    for (int i = 0; i < DEGENERATE_RUN_LIMIT + 50; i++) {
      script.add(i % 2 == 0 ? "tic" : "tac");
    }

    var received = runToCompletion(script);

    var finals = received.stream().filter(InferenceToken::isFinal).toList();
    assertThat(finals).hasSize(1);
    assertThat(finals.get(0).finishReason())
      .as("a broken run must finish naturally with the backend's reason")
      .isEqualTo("eos");
    assertThat(
      received
        .stream()
        .filter(t -> !t.isFinal())
        .count()
    ).isEqualTo(script.size());
  }
}
