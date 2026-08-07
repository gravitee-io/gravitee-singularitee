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
package io.gravitee.singularitee.adapter.reranker;

import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.engine.RerankRequest;
import io.gravitee.singularitee.engine.RerankResponse;
import io.gravitee.singularitee.engine.RerankResult;
import io.gravitee.singularitee.engine.RerankerEngine;
import io.gravitee.singularitee.inference.api.InferenceModel;
import io.gravitee.singularitee.inference.api.reranker.RerankPair;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.api.reranker.RerankTokenCount;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Base {@link RerankerEngine} for cross-encoder reranker models. Backend-specific
 * subclasses ({@link OnnxRerankerEngine}, {@link LlamaCppRerankerEngine}) only differ
 * by their concrete delegate type — the scoring, sorting and {@code topK} logic is
 * identical and lives here.
 *
 * <p>The delegate is bound to {@link InferenceModel} (a {@code gravitee-inference-api}
 * type), so this class needs no backend-specific imports; subclasses remain the only
 * files permitted to import the ONNX / llama.cpp reranker types.
 *
 * @param <D> the blocking reranker delegate type
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract sealed class AbstractRerankerEngine<
  D extends InferenceModel<?, RerankPair, RerankTokenCount>
>
  extends BlockingEngineAdapter<D>
  implements RerankerEngine
  permits OnnxRerankerEngine, LlamaCppRerankerEngine {

  protected AbstractRerankerEngine(D delegate, Vertx vertx) {
    super(delegate, vertx);
  }

  @Override
  public Single<RerankResponse> rxRerank(RerankRequest request) {
    return rxInfer(() -> {
      var pairs = request
        .documents()
        .stream()
        .map(doc -> new RerankPair(request.query(), doc))
        .toList();
      List<RerankTokenCount> results = delegate.inferAll(pairs);

      List<RerankResult> ranked = new ArrayList<>(results.size());
      int totalTokens = 0;
      for (int i = 0; i < results.size(); i++) {
        RerankTokenCount r = results.get(i);
        ranked.add(new RerankResult(i, r.score()));
        totalTokens += r.tokenCount();
      }
      ranked.sort(Comparator.comparingDouble(RerankResult::score).reversed());
      int topK = request.topK();
      if (topK > 0 && topK < ranked.size()) {
        ranked = new ArrayList<>(ranked.subList(0, topK));
      }
      return new RerankResponse(ranked, totalTokens);
    });
  }

  @Override
  public void close() throws Exception {
    delegate.close();
  }

  /**
   * Parses a configured scoring mode name into a {@link RerankScoring}, returning
   * {@code null} (auto-detect from the model output) when blank or unrecognised.
   *
   * @param value the configured scoring name (case-insensitive); may be {@code null}
   * @return the matching {@link RerankScoring}, or {@code null} to auto-detect
   */
  static RerankScoring parseScoring(String value) {
    if (value == null || value.isBlank()) return null; // auto-detect from output shape
    try {
      return RerankScoring.valueOf(value.toUpperCase(Locale.ENGLISH));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
