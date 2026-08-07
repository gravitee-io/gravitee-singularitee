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
package io.gravitee.singularitee.adapter.gliner;

import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.ClassifyResult;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-input chunking for the GLiNER engines.
 *
 * <p>GLiNER2 encodes {@code [labels] [SEP] [text]} into a fixed window (~512 tokens), and gliner4j
 * exposes no tokenizer or length API. So we <em>estimate</em> tokens (see {@link EstimatedTokens}),
 * reserve an estimate for the label/entity prompt, and split
 * long inputs with {@link RecursiveTextSplitter} (semantic-boundary first, estimated-token-window
 * fallback) into disjoint chunks that each fit the remaining text budget. Per-chunk character
 * offsets are shifted back to the original text by the caller; scores aggregate as the max per
 * label across chunks.
 *
 * <p>Holds no gliner4j types, so the "only the engine imports gliner4j" rule is preserved and the
 * estimation/aggregation stays unit-testable.
 */
final class GlinerChunking {

  /** Default encoder token window (GLiNER2). Overridable per model via {@code token_cap}. */
  static final int DEFAULT_TOKEN_CAP = 512;

  /** Estimated fixed overhead for [CLS]/[SEP]/separator tokens in the prompt. */
  private static final int SPECIAL_TOKENS = 4;

  /** Estimated per-label marker/separator overhead on top of the label's own tokens. */
  private static final int PER_LABEL_OVERHEAD = 2;

  /** Floor so a huge label set can never collapse the text budget to zero. */
  private static final int MIN_TEXT_BUDGET = 16;

  private GlinerChunking() {}

  /** Estimated token count of a piece (no tokenizer available). */
  static int estimateTokens(String text) {
    return EstimatedTokens.estimateTokens(text);
  }

  /** Estimated tokens consumed by the label/entity prompt GLiNER packs into the window. */
  static int estimatePromptTokens(Collection<String> labelNames) {
    int sum = SPECIAL_TOKENS;
    if (labelNames != null) {
      for (String name : labelNames) {
        sum += estimateTokens(name) + PER_LABEL_OVERHEAD;
      }
    }
    return sum;
  }

  /** Per-chunk text budget (tokens) = cap − label-prompt estimate, floored. */
  static int textBudget(int tokenCap, Collection<String> labelNames) {
    int cap = tokenCap > 0 ? tokenCap : DEFAULT_TOKEN_CAP;
    return Math.max(MIN_TEXT_BUDGET, cap - estimatePromptTokens(labelNames));
  }

  static RecursiveTextSplitter splitter(int textBudgetTokens) {
    return new RecursiveTextSplitter(GlinerChunking::estimatedTokenEndOffsets, textBudgetTokens);
  }

  /** Estimated token cut points (see {@link EstimatedTokens#endOffsets}). */
  static int[] estimatedTokenEndOffsets(String text) {
    return EstimatedTokens.endOffsets(text);
  }

  /** NER: order spans by score (strongest first); all-scores = max per entity type. */
  static ClassifyResponse nerResponse(List<ClassifyResult> spans) {
    Map<String, Float> allScores = new LinkedHashMap<>();
    for (ClassifyResult r : spans) {
      allScores.merge(r.label(), r.score(), Math::max);
    }
    spans.sort((a, b) -> Float.compare(b.score(), a.score()));
    String topLabel = spans.isEmpty() ? "" : spans.getFirst().label();
    float topScore = spans.isEmpty() ? 0f : spans.getFirst().score();
    return new ClassifyResponse(topLabel, topScore, allScores, spans);
  }

  /**
   * Classifier: top = highest-scoring label across chunks (max per label); {@code allScores} is that
   * rollup, and {@code results} keeps the per-chunk rows so each (label, score) stays traceable to
   * the chunk span [start, end] it came from.
   */
  static ClassifyResponse classifierResponse(
    Map<String, Float> maxByLabel,
    List<ClassifyResult> perChunkRows
  ) {
    String topLabel = "";
    float topScore = 0f;
    for (var e : maxByLabel.entrySet()) {
      if (topLabel.isEmpty() || e.getValue() > topScore) {
        topLabel = e.getKey();
        topScore = e.getValue();
      }
    }
    return new ClassifyResponse(topLabel, topScore, new LinkedHashMap<>(maxByLabel), perChunkRows);
  }
}
