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

import io.gravitee.vllm.engine.CompletionOutput;
import io.gravitee.vllm.engine.LogprobEntry;
import io.gravitee.vllm.engine.RequestOutput;
import io.gravitee.vllm.engine.SamplingParams;
import io.gravitee.vllm.engine.VllmEngine;
import io.gravitee.vllm.engine.VllmRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LabelEngine} over a {@link VllmEngine}: each prompt runs as a one-token greedy request
 * asking for the log probabilities of exactly its label tokens ({@code logprob_token_ids}). These
 * are raw log probabilities over the vocabulary, so a softmax over them is a softmax over the
 * label logits.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class VllmLabelEngine implements LabelEngine {

  private final VllmEngine engine;
  /** Sampling parameters of each running request, closed once it finishes. */
  private final Map<String, SamplingParams> running = new HashMap<>();

  VllmLabelEngine(VllmEngine engine) {
    this.engine = engine;
  }

  @Override
  public int[] encode(String text, boolean specials) {
    return engine.encode(text, specials).stream().mapToInt(Integer::intValue).toArray();
  }

  @Override
  public String chatTemplate() {
    try {
      return engine.getChatTemplate();
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public void add(String requestId, int[] tokens, int[] labels) {
    var params = new SamplingParams(engine.arena())
      .temperature(0.0)
      .maxTokens(1)
      .logprobTokenIds(labels);
    try {
      engine.addRequest(
        VllmRequest.ofTokens(requestId, "", Arrays.stream(tokens).boxed().toList(), params)
      );
    } catch (RuntimeException e) {
      params.close();
      throw e;
    }
    running.put(requestId, params);
  }

  @Override
  public List<Scored> step() {
    var scored = new ArrayList<Scored>();
    for (RequestOutput out : engine.step()) {
      if (!out.finished()) continue;
      SamplingParams params = running.remove(out.requestId());
      if (params != null) params.close();
      Map<Integer, Double> logprobs = new HashMap<>();
      for (CompletionOutput completion : out.outputs()) {
        if (completion.logprobs() == null || completion.logprobs().isEmpty()) continue;
        for (LogprobEntry entry : completion.logprobs().getFirst().values()) {
          logprobs.put(entry.tokenId(), entry.logprob());
        }
      }
      scored.add(new Scored(out.requestId(), logprobs));
    }
    return scored;
  }

  @Override
  public boolean busy() {
    return engine.hasUnfinishedRequests();
  }

  @Override
  public void abort(String requestId) {
    engine.abortRequest(requestId);
    SamplingParams params = running.remove(requestId);
    if (params != null) params.close();
  }

  @Override
  public void close() {
    running.values().forEach(SamplingParams::close);
    running.clear();
    engine.close();
  }
}
