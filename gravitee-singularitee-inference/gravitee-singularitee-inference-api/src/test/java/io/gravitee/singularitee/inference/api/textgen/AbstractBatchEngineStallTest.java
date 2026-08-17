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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for a backend that dies holding live sequences.
 *
 * <p>A failed native decode (llama.cpp {@code llama_decode: failed to decode, ret = -3})
 * leaves the batch unable to produce tokens while the conversations are still registered.
 * {@code processNextBatch()} then returns empty — indistinguishable, to the worker loop,
 * from an idle engine. Nothing completed those sequences, so callers waited on a stream
 * that never closed; and because the loop only parks while {@code sequences} is empty, it
 * spun instead of sleeping. Observed live as a request that hung until the client timed
 * out, with the server logging nothing at all.
 *
 * <p>{@link EngineAdapter#hasStalled()} lets an adapter distinguish the two, so the engine
 * fails the stranded callers with a terminal token (finish reason {@code "stalled"}; the
 * OpenAI surface maps it to {@code "stop"} since OpenAI defines no error reason, with the
 * failure recorded in the log) and releases their slots.
 */
class AbstractBatchEngineStallTest {

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

  /** Adapter whose backend can be made to "die" mid-flight, as a failed decode does. */
  private static class StallingAdapter implements EngineAdapter<Void, FakeRequest, String, Object> {

    final AtomicBoolean stalled = new AtomicBoolean(false);
    final List<Integer> removedInternalIds = new CopyOnWriteArrayList<>();

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
      return Optional.empty(); // no output — exactly what a dead batch looks like
    }

    @Override
    public boolean hasStalled() {
      return stalled.get();
    }

    @Override
    public void removeSequence(int internalId) {
      removedInternalIds.add(internalId);
    }

    @Override
    public Optional<String> getFinishReason(Object state) {
      return Optional.empty();
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
    public void cleanupSequenceState(Object state) {}

    @Override
    public void shutdown() {}
  }

  private static final class TestEngine
    extends AbstractBatchEngine<Void, FakeRequest, String, Object> {

    TestEngine(BatchEngineConfig config, StallingAdapter adapter) {
      super(config, adapter);
    }
  }

  @Test
  void stalled_backend_fails_the_waiting_caller_instead_of_hanging() throws Exception {
    var adapter = new StallingAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    var received = new CopyOnWriteArrayList<InferenceToken<String>>();
    var finalToken = new CountDownLatch(1);
    engine.start(token -> {
      received.add(token);
      if (token.isFinal()) {
        finalToken.countDown();
      }
    });

    try {
      adapter.stalled.set(true);
      engine.addSequence(1, new FakeRequest("bonjour"));

      // The whole point: this returns rather than timing out. Before the fix the
      // sequence was never completed and no token was ever emitted.
      assertThat(finalToken.await(5, TimeUnit.SECONDS))
        .as("a stalled backend must terminate the caller's stream, not leave it open")
        .isTrue();

      assertThat(received).hasSize(1);
      var token = received.get(0);
      assertThat(token.isFinal()).isTrue();
      assertThat(token.finishReason()).isEqualTo("stalled");
      assertThat(token.seqId()).isEqualTo(1);
      assertThat(adapter.removedInternalIds).contains(0);
    } finally {
      engine.close();
    }
  }

  @Test
  void stalled_backend_fails_every_in_flight_caller() throws Exception {
    // A decode failure takes down the whole batch, so every sequence sharing the
    // engine must be failed — not just the one that happened to be sampled.
    var adapter = new StallingAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(3), adapter);

    var received = new CopyOnWriteArrayList<InferenceToken<String>>();
    var allFinal = new CountDownLatch(3);
    engine.start(token -> {
      received.add(token);
      if (token.isFinal()) {
        allFinal.countDown();
      }
    });

    try {
      engine.addSequence(1, new FakeRequest("un"));
      engine.addSequence(2, new FakeRequest("deux"));
      engine.addSequence(3, new FakeRequest("trois"));
      adapter.stalled.set(true);

      assertThat(allFinal.await(5, TimeUnit.SECONDS))
        .as("every caller sharing the stalled engine must be terminated")
        .isTrue();
      assertThat(received).allMatch(InferenceToken::isFinal);
      assertThat(received.stream().map(InferenceToken::finishReason).distinct()).containsExactly(
        "stalled"
      );
      assertThat(received.stream().map(InferenceToken::seqId).sorted().toList()).containsExactly(
        1,
        2,
        3
      );
    } finally {
      engine.close();
    }
  }

  @Test
  void a_healthy_idle_engine_is_never_failed() throws Exception {
    // processNextBatch() returning empty is normal while idle. Only an adapter
    // reporting hasStalled() may trigger the failure path — otherwise a quiet
    // engine would kill its own live sequences.
    var adapter = new StallingAdapter();
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    var received = new CopyOnWriteArrayList<InferenceToken<String>>();
    engine.start(received::add);

    try {
      engine.addSequence(1, new FakeRequest("toujours en vie"));
      Thread.sleep(300);

      assertThat(received).as("an idle backend must not terminate a live sequence").isEmpty();
    } finally {
      engine.close();
    }
  }
}
