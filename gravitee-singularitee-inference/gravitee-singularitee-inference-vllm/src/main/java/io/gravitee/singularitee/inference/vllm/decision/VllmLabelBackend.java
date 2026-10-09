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

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.decision.BackendPrompt;
import io.gravitee.singularitee.inference.decision.DecisionBackend;
import io.gravitee.singularitee.inference.vllm.EngineAdapter;
import io.gravitee.singularitee.inference.vllm.VllmConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@code custom} decision model on vLLM: any causal model vLLM serves, scored on the logits of
 * one label token per option at the end of the prompt, as {@code llama_cpp_decision} does with
 * {@code family: custom}. Prompt layout and answers come from the shared {@code DecisionRunner}.
 *
 * <p>One scheduler thread owns the engine: callers queue their prompts, and the thread adds every
 * queued prompt before each step, so concurrent decisions share batches.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmLabelBackend implements DecisionBackend {

  private static final Logger LOGGER = LoggerFactory.getLogger(VllmLabelBackend.class);

  /** Most label tokens vLLM reports per request ({@code MAX_LOGPROB_TOKEN_IDS}). */
  static final int MAX_CODES = 128;

  private final LabelEngine engine;
  private final List<String> codes;
  private final int[] codeTokens;
  private final String chatTemplate;
  private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
  private final AtomicLong ids = new AtomicLong();
  private final Thread scheduler;
  private volatile boolean closed;

  /** One prompt waiting for its scores. */
  private record Pending(
    String requestId,
    int[] tokens,
    int[] labels,
    CompletableFuture<float[]> result
  ) {}

  /** Loads the model from {@code config} and scores it on {@code codes}. */
  public static VllmLabelBackend load(VllmConfig config, List<String> codes) {
    return new VllmLabelBackend(new VllmLabelEngine(EngineAdapter.buildEngine(config)), codes);
  }

  VllmLabelBackend(LabelEngine engine, List<String> codes) {
    if (codes.isEmpty()) {
      engine.close();
      throw new IllegalArgumentException("a custom decision model needs at least one code");
    }
    if (codes.size() > MAX_CODES) {
      engine.close();
      throw new IllegalArgumentException("at most " + MAX_CODES + " codes, got " + codes.size());
    }
    this.engine = engine;
    this.codes = List.copyOf(codes);
    try {
      this.codeTokens = codeTokens(engine, this.codes);
    } catch (RuntimeException e) {
      engine.close();
      throw e;
    }
    this.chatTemplate = engine.chatTemplate();
    this.scheduler = Thread.ofPlatform()
      .name("vllm-decision-scheduler")
      .daemon(true)
      .start(this::run);
    LOGGER.info("Decision model loaded on vLLM: family=custom, codes={}", codes.size());
  }

  /** The token of each code; each must be one distinct token. */
  private static int[] codeTokens(LabelEngine engine, List<String> codes) {
    int[] tokens = new int[codes.size()];
    var seen = new HashSet<Integer>();
    for (int i = 0; i < codes.size(); i++) {
      int[] encoded = engine.encode(codes.get(i), false);
      if (encoded.length != 1) {
        throw new IllegalArgumentException(
          "code '" +
            codes.get(i) +
            "' is " +
            encoded.length +
            " tokens; each code must be one token"
        );
      }
      if (!seen.add(encoded[0])) {
        throw new IllegalArgumentException(
          "code '" + codes.get(i) + "' repeats another code's token"
        );
      }
      tokens[i] = encoded[0];
    }
    return tokens;
  }

  @Override
  public DecisionFamily family() {
    return DecisionFamily.CUSTOM;
  }

  @Override
  public String decisionTemplate() {
    return null;
  }

  @Override
  public String chatTemplate() {
    return chatTemplate;
  }

  @Override
  public Map<String, Float> temperatures() {
    return Map.of();
  }

  @Override
  public List<String> labelTexts() {
    return codes;
  }

  @Override
  public int maxOptions() {
    return codes.size();
  }

  @Override
  public int[] tokenize(String text) {
    return engine.encode(text, true);
  }

  @Override
  public LayaVocab layaVocab() {
    return null;
  }

  @Override
  public List<float[]> score(List<BackendPrompt> prompts) {
    if (closed) throw new IllegalStateException("the decision model is closed");
    var futures = new ArrayList<CompletableFuture<float[]>>(prompts.size());
    for (BackendPrompt prompt : prompts) {
      if (!(prompt instanceof BackendPrompt.Text text)) {
        throw new IllegalArgumentException("a custom model scores text prompts only");
      }
      if (text.nOutputs() > codeTokens.length) {
        throw new IllegalArgumentException(
          text.nOutputs() + " options, but the model has " + codeTokens.length + " codes"
        );
      }
      var future = new CompletableFuture<float[]>();
      int[] labels = Arrays.copyOf(codeTokens, text.nOutputs());
      queue.add(new Pending("dec-" + ids.incrementAndGet(), text.tokens(), labels, future));
      futures.add(future);
    }
    var scores = new ArrayList<float[]>(futures.size());
    try {
      for (CompletableFuture<float[]> future : futures) scores.add(future.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for scores", e);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof RuntimeException r
        ? r
        : new IllegalStateException(e.getCause());
    }
    return scores;
  }

  /** Adds what is queued, steps, and completes what finished, until closed. */
  private void run() {
    var waiting = new HashMap<String, Pending>();
    while (!closed) {
      try {
        Pending first = engine.busy() ? queue.poll() : queue.poll(100, TimeUnit.MILLISECONDS);
        var added = new ArrayList<Pending>();
        if (first != null) added.add(first);
        queue.drainTo(added);
        for (Pending pending : added) {
          try {
            engine.add(pending.requestId(), pending.tokens(), pending.labels());
            waiting.put(pending.requestId(), pending);
          } catch (RuntimeException e) {
            pending.result().completeExceptionally(e);
          }
        }
        if (!engine.busy()) continue;
        for (LabelEngine.Scored scored : engine.step()) {
          Pending pending = waiting.remove(scored.requestId());
          if (pending != null) complete(pending, scored);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        // A failed step loses every request in flight: fail them, keep serving.
        LOGGER.warn("Decision step failed: {}", e.getMessage());
        waiting
          .values()
          .forEach(p -> {
            engine.abort(p.requestId());
            p.result().completeExceptionally(e);
          });
        waiting.clear();
      }
    }
    var closing = new IllegalStateException("the decision model is closed");
    waiting.values().forEach(p -> p.result().completeExceptionally(closing));
    queue.forEach(p -> p.result().completeExceptionally(closing));
  }

  private static void complete(Pending pending, LabelEngine.Scored scored) {
    float[] out = new float[pending.labels().length];
    for (int i = 0; i < out.length; i++) {
      Double logprob = scored.logprobs().get(pending.labels()[i]);
      if (logprob == null) {
        pending
          .result()
          .completeExceptionally(
            new IllegalStateException("the engine reported no log probability for label " + i)
          );
        return;
      }
      out[i] = logprob.floatValue();
    }
    pending.result().complete(out);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      scheduler.join(TimeUnit.SECONDS.toMillis(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    engine.close();
  }
}
