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
package io.gravitee.singularitee.inference.vllm.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.decision.BackendPrompt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Label codes map to single tokens, prompts are scored on exactly their labels, and concurrent
 * callers share engine steps.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmLabelBackendTest {

  /** Tokens: one per character, {@code 'A'} is 65; label {@code t} scores {@code -t / 100}. */
  private static class FakeEngine implements LabelEngine {

    final Map<String, int[]> queued = new LinkedHashMap<>();
    final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean closed;
    volatile boolean dropLabels;

    @Override
    public int[] encode(String text, boolean specials) {
      return text.chars().toArray();
    }

    @Override
    public String chatTemplate() {
      return "{{ messages }}";
    }

    @Override
    public synchronized void add(String requestId, int[] tokens, int[] labels) {
      queued.put(requestId, labels);
    }

    @Override
    public List<Scored> step() {
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      synchronized (this) {
        batchSizes.add(queued.size());
        var out = new ArrayList<Scored>();
        queued.forEach((id, labels) -> {
          Map<Integer, Double> logprobs = new HashMap<>();
          if (!dropLabels) for (int label : labels) logprobs.put(label, -label / 100.0);
          out.add(new Scored(id, logprobs));
        });
        queued.clear();
        return out;
      }
    }

    @Override
    public synchronized boolean busy() {
      return !queued.isEmpty();
    }

    @Override
    public synchronized void abort(String requestId) {
      queued.remove(requestId);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @Test
  void a_custom_model_reports_its_codes_and_chat_template() {
    var engine = new FakeEngine();
    try (var backend = new VllmLabelBackend(engine, List.of("A", "B", "C"))) {
      assertThat(backend.family()).isEqualTo(DecisionFamily.CUSTOM);
      assertThat(backend.labelTexts()).containsExactly("A", "B", "C");
      assertThat(backend.maxOptions()).isEqualTo(3);
      assertThat(backend.chatTemplate()).isEqualTo("{{ messages }}");
      assertThat(backend.decisionTemplate()).isNull();
      assertThat(backend.temperatures()).isEmpty();
      assertThat(backend.tokenize("Hi")).containsExactly('H', 'i');
    }
    assertThat(engine.closed).isTrue();
  }

  @Test
  void codes_must_be_distinct_single_tokens() {
    var multi = new FakeEngine();
    assertThatThrownBy(() -> new VllmLabelBackend(multi, List.of("A", "BC")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("'BC' is 2 tokens");
    assertThat(multi.closed).isTrue();

    assertThatThrownBy(() ->
      new VllmLabelBackend(new FakeEngine(), List.of("A", "A"))
    ).hasMessageContaining("repeats");
    assertThatThrownBy(() ->
      new VllmLabelBackend(new FakeEngine(), List.of())
    ).hasMessageContaining("at least one code");
  }

  @Test
  void scores_each_prompt_on_its_first_labels() {
    var engine = new FakeEngine();
    engine.release.countDown();
    try (var backend = new VllmLabelBackend(engine, List.of("A", "B", "C"))) {
      var scores = backend.score(
        List.of(
          new BackendPrompt.Text(new int[] { 1, 2 }, 2),
          new BackendPrompt.Text(new int[] { 3 }, 3)
        )
      );

      assertThat(scores.get(0)).containsExactly(-0.65f, -0.66f);
      assertThat(scores.get(1)).containsExactly(-0.65f, -0.66f, -0.67f);
    }
  }

  @Test
  void concurrent_callers_share_a_step() throws Exception {
    var engine = new FakeEngine();
    try (
      var backend = new VllmLabelBackend(engine, List.of("A", "B"));
      var pool = Executors.newFixedThreadPool(4)
    ) {
      var calls = new ArrayList<Future<List<float[]>>>();
      for (int i = 0; i < 4; i++) {
        calls.add(
          pool.submit(() -> backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2))))
        );
      }
      // Let every caller queue before the first step runs.
      Thread.sleep(300);
      engine.release.countDown();
      for (var call : calls) assertThat(call.get(5, TimeUnit.SECONDS)).hasSize(1);

      assertThat(engine.batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);
      assertThat(engine.batchSizes.size()).isLessThan(4);
    }
  }

  @Test
  void closing_waits_for_the_running_step_before_closing_the_engine() throws Exception {
    var engine = new FakeEngine();
    var backend = new VllmLabelBackend(engine, List.of("A", "B"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var call = pool.submit(() ->
        backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2)))
      );
      while (!engine.busy()) Thread.sleep(10);
      var closing = pool.submit(backend::close);
      Thread.sleep(200);
      assertThat(engine.closed).as("closed under a running step").isFalse();

      engine.release.countDown();
      closing.get(5, TimeUnit.SECONDS);

      assertThat(engine.closed).isTrue();
      assertThat(call.get(5, TimeUnit.SECONDS)).hasSize(1);
    }
    assertThatThrownBy(() ->
      backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2)))
    ).hasMessageContaining("closed");
  }

  @Test
  void a_failing_abort_still_fails_the_callers_and_keeps_serving() throws Exception {
    var engine = new FakeEngine() {
      private boolean failed;

      @Override
      public List<Scored> step() {
        if (!failed) {
          failed = true;
          throw new IllegalStateException("device lost");
        }
        return super.step();
      }

      @Override
      public synchronized void abort(String requestId) {
        super.abort(requestId);
        throw new IllegalStateException("abort failed");
      }
    };
    engine.release.countDown();
    try (var backend = new VllmLabelBackend(engine, List.of("A", "B"))) {
      assertThatThrownBy(() ->
        backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2)))
      ).hasMessageContaining("device lost");
      assertThat(backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2)))).hasSize(1);
    }
  }

  @Test
  void a_missing_label_fails_the_call_and_too_many_options_are_refused() {
    var engine = new FakeEngine();
    engine.release.countDown();
    engine.dropLabels = true;
    try (var backend = new VllmLabelBackend(engine, List.of("A", "B"))) {
      assertThatThrownBy(() ->
        backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 2)))
      ).hasMessageContaining("no log probability");
      assertThatThrownBy(() ->
        backend.score(List.of(new BackendPrompt.Text(new int[] { 1 }, 3)))
      ).hasMessageContaining("3 options, but the model has 2 codes");
    }
  }
}
