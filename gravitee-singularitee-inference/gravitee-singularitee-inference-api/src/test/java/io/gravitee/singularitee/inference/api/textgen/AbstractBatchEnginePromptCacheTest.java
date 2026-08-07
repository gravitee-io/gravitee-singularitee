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
 * Tests for the cross-request KV prefix cache wiring in {@link AbstractBatchEngine}:
 * reuse-offset propagation into {@code createSequenceState}, KV release on both
 * the finalize and cancel paths, and full bypass for adapters that don't
 * support server-side caching.
 */
class AbstractBatchEnginePromptCacheTest {

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

  /** Adapter with caching support: prompt chars are the token ids. */
  private static class CachingAdapter implements EngineAdapter<Void, FakeRequest, String, Object> {

    record Creation(int slot, int reuse) {}

    record Removal(int slot, boolean keepKv) {}

    final List<Creation> creations = new ArrayList<>();
    final List<Removal> removals = new ArrayList<>();
    int[] committed;
    boolean failCommitted;

    static int[] toIds(String prompt) {
      return prompt.chars().toArray();
    }

    @Override
    public Object createSequenceState(int internalId, FakeRequest request) {
      throw new AssertionError("2-arg createSequenceState must not be used when caching");
    }

    @Override
    public Object createSequenceState(int internalId, FakeRequest request, int reuse) {
      creations.add(new Creation(internalId, reuse));
      return new Object();
    }

    @Override
    public int[] tokenizePrompt(FakeRequest request) {
      return toIds(request.prompt());
    }

    @Override
    public int[] committedTokens(Object state) {
      if (failCommitted) {
        throw new IllegalStateException("boom");
      }
      return committed;
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
      throw new AssertionError("1-arg removeSequence must not be used when caching");
    }

    @Override
    public void removeSequence(int internalId, boolean keepKv) {
      removals.add(new Removal(internalId, keepKv));
    }

    @Override
    public Optional<String> getFinishReason(Object state) {
      return Optional.empty(); // never finishes on its own — cancel drives release
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

    TestEngine(BatchEngineConfig config, EngineAdapter<Void, FakeRequest, String, Object> adapter) {
      super(config, adapter);
    }
  }

  private static BatchEngineConfig cachingConfig(int slots, int minTokens) {
    return BatchEngineConfig.of(slots).withPromptCache(true, minTokens);
  }

  @Test
  void reuse_offset_propagates_to_createSequenceState_on_matching_prefix() {
    var adapter = new CachingAdapter();
    var engine = new TestEngine(cachingConfig(1, 4), adapter);
    String prompt = "system: be nice\nuser: hello";
    adapter.committed = CachingAdapter.toIds(prompt + " world");

    engine.addSequence(1, new FakeRequest(prompt), "alice");
    assertThat(adapter.creations).containsExactly(new CachingAdapter.Creation(0, 0));

    // Finish (cancel) — slot released WARM with the committed tokens.
    engine.cancelSequence(1);
    assertThat(adapter.removals).containsExactly(new CachingAdapter.Removal(0, true));

    // Same key + shared prefix → reuse the full prompt LCP.
    engine.addSequence(2, new FakeRequest(prompt), "alice");
    assertThat(adapter.creations.get(1).slot()).isZero();
    assertThat(adapter.creations.get(1).reuse()).isEqualTo(prompt.length());
  }

  @Test
  void release_happens_on_cancel_and_kv_is_kept() {
    var adapter = new CachingAdapter();
    adapter.committed = CachingAdapter.toIds("abcdef");
    var engine = new TestEngine(cachingConfig(1, 2), adapter);

    engine.addSequence(1, new FakeRequest("abc"), null);
    engine.cancelSequence(1);

    assertThat(adapter.removals).containsExactly(new CachingAdapter.Removal(0, true));

    // The retained prefix is reusable without any cache key.
    engine.addSequence(2, new FakeRequest("abcdefgh"), null);
    assertThat(adapter.creations.get(1).reuse()).isEqualTo(6);
  }

  @Test
  void release_happens_on_natural_finalize() throws Exception {
    var adapter = new CachingAdapter() {
      volatile boolean finished;

      @Override
      public Optional<EngineOutput<String, Object>> processNextBatch() {
        finished = true;
        return Optional.of(new EngineOutput<>(0, "tok"));
      }

      @Override
      public Optional<String> getFinishReason(Object state) {
        return finished ? Optional.of("stop") : Optional.empty();
      }
    };
    adapter.committed = CachingAdapter.toIds("abcdef");
    var engine = new TestEngine(cachingConfig(1, 2), adapter);

    var latch = new java.util.concurrent.CountDownLatch(1);
    engine.start(token -> {
      if (token.isFinal()) {
        latch.countDown();
      }
    });
    try {
      engine.addSequence(1, new FakeRequest("abc"), null);
      assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("final token emitted")
        .isTrue();

      assertThat(adapter.removals).contains(new CachingAdapter.Removal(0, true));
      assertThat(adapter.removals).allMatch(CachingAdapter.Removal::keepKv);
    } finally {
      engine.close();
    }
  }

  @Test
  void committedTokens_failure_invalidates_and_drops_kv() {
    var adapter = new CachingAdapter();
    adapter.failCommitted = true;
    var engine = new TestEngine(cachingConfig(1, 2), adapter);

    engine.addSequence(1, new FakeRequest("abc"), "alice");
    engine.cancelSequence(1);

    assertThat(adapter.removals).containsExactly(new CachingAdapter.Removal(0, false));

    // The slot went COLD: same key gets no reuse.
    adapter.failCommitted = false;
    engine.addSequence(2, new FakeRequest("abc"), "alice");
    assertThat(adapter.creations.get(1).reuse()).isZero();
  }

  @Test
  void unsupported_adapter_bypasses_the_cache() {
    // tokenizePrompt returns null (the interface default) → no acquire, no
    // reuse; release falls back to keepKv=false since committedTokens is null.
    var removals = new ArrayList<Integer>();
    var creations = new ArrayList<Integer>();
    var adapter = new EngineAdapter<Void, FakeRequest, String, Object>() {
      @Override
      public Object createSequenceState(int internalId, FakeRequest request) {
        creations.add(internalId);
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
        removals.add(internalId);
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
    };
    var engine = new TestEngine(cachingConfig(1, 2), adapter);

    engine.addSequence(1, new FakeRequest("abc"), "alice");
    var token = engine.cancelSequence(1);

    assertThat(token).isNotNull();
    assertThat(creations).containsExactly(0);
    // Default 2-arg removeSequence delegates to the 1-arg one.
    assertThat(removals).containsExactly(0);

    // Slot came back — a new sequence can start.
    engine.addSequence(2, new FakeRequest("abc"), null);
    assertThat(engine.cancelSequence(2)).isNotNull();
  }

  @Test
  void disabled_cache_keeps_todays_behavior() {
    var removals = new ArrayList<Integer>();
    var adapter = new EngineAdapter<Void, FakeRequest, String, Object>() {
      @Override
      public Object createSequenceState(int internalId, FakeRequest request) {
        return new Object();
      }

      @Override
      public Object createSequenceState(int internalId, FakeRequest request, int reuse) {
        throw new AssertionError("reuse overload must not be called with cache disabled");
      }

      @Override
      public int[] tokenizePrompt(FakeRequest request) {
        throw new AssertionError("tokenizePrompt must not be called with cache disabled");
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
        removals.add(internalId);
      }

      @Override
      public void removeSequence(int internalId, boolean keepKv) {
        throw new AssertionError("keepKv overload must not be called with cache disabled");
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
    };
    var engine = new TestEngine(BatchEngineConfig.of(1), adapter);

    engine.addSequence(1, new FakeRequest("abc"), "alice");
    assertThat(engine.cancelSequence(1)).isNotNull();
    assertThat(removals).containsExactly(0);
  }
}
