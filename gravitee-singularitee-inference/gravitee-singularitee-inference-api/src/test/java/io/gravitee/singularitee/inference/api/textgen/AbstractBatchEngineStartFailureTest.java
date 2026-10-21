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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A sequence whose adapter state cannot be created (a corrupt media attachment, say) must fail
 * its caller rather than leave it waiting for a final token that never comes.
 */
class AbstractBatchEngineStartFailureTest {

  /** Bare generation request. */
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

  /** Adapter that cannot create state for a request whose prompt is "corrupt". */
  private static class FailingAdapter implements EngineAdapter<Void, FakeRequest, String, Object> {

    final AtomicBoolean stalled = new AtomicBoolean(false);
    final List<Integer> removedInternalIds = new CopyOnWriteArrayList<>();

    @Override
    public Object createSequenceState(int internalId, FakeRequest request) {
      if (request.prompt().equals("corrupt")) {
        throw new IllegalStateException("Failed to create bitmap from file bytes");
      }
      return new Object();
    }

    @Override
    public PromptStats validateRequest(FakeRequest request) {
      return new PromptStats(1, 4096, 16);
    }

    @Override
    public Optional<EngineOutput<String, Object>> processNextBatch() {
      return Optional.empty(); // no output: exactly what a dead batch looks like
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

    TestEngine(BatchEngineConfig config, FailingAdapter adapter) {
      super(config, adapter);
    }
  }

  @Test
  void a_failed_start_is_thrown_to_the_caller_and_frees_the_slot() throws Exception {
    var engine = new TestEngine(BatchEngineConfig.of(1), new FailingAdapter());
    var received = new CopyOnWriteArrayList<InferenceToken<String>>();
    engine.start(received::add);
    try {
      assertThatThrownBy(() -> engine.addSequence(1, new FakeRequest("corrupt"))).isInstanceOf(
        IllegalStateException.class
      );
      // The one slot is free again: a healthy request starts instead of queueing.
      engine.addSequence(2, new FakeRequest("fine"));
      engine.addSequence(3, new FakeRequest("queued"));
      assertThat(engine.cancelSequence(2)).as("sequence 2 was running").isNotNull();
    } finally {
      engine.close();
    }
  }

  @Test
  void a_queued_sequence_that_fails_to_start_gets_a_final_token() throws Exception {
    var engine = new TestEngine(BatchEngineConfig.of(1), new FailingAdapter());
    var received = new CopyOnWriteArrayList<InferenceToken<String>>();
    var finalToken = new CountDownLatch(1);
    engine.start(token -> {
      received.add(token);
      if (token.isFinal() && token.seqId() == 2) {
        finalToken.countDown();
      }
    });
    try {
      engine.addSequence(1, new FakeRequest("fine"));
      engine.addSequence(2, new FakeRequest("corrupt")); // queued behind 1
      engine.cancelSequence(1); // frees the slot: 2 now tries to start on the worker side

      assertThat(finalToken.await(5, TimeUnit.SECONDS))
        .as("the queued caller must be terminated, not left open")
        .isTrue();
      var token = received
        .stream()
        .filter(t -> t.seqId() == 2)
        .findFirst()
        .orElseThrow();
      assertThat(token.finishReason()).isEqualTo("start_failed");
    } finally {
      engine.close();
    }
  }
}
