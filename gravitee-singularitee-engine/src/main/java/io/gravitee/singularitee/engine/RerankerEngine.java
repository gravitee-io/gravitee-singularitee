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
package io.gravitee.singularitee.engine;

import io.reactivex.rxjava3.core.Single;

/**
 * A non-blocking cross-encoder reranker engine (ONNX BERT backend).
 *
 * <p>Reranking is fully reactive: the caller submits a {@link RerankRequest}
 * containing a query and a list of candidate documents; the engine scores all
 * (query, doc) pairs with a single batched forward pass, sorts by descending
 * score, applies {@code topK} if set, and emits a {@link RerankResponse}.
 *
 * <p>Unlike an {@link EmbeddingEngine}, a reranker cannot produce embeddings —
 * the underlying classification head emits scoring logits, not pooled hidden states.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public non-sealed interface RerankerEngine extends ModelEngine {
  @Override
  default ModelEngineType type() {
    return ModelEngineType.RERANKER;
  }

  @Override
  default String task() {
    return ModelTasks.RERANKING;
  }

  /**
   * Scores all documents against the query using cross-encoder scoring,
   * sorts by score descending, and applies topK.
   *
   * @param request the reranker input
   * @return a {@link Single} emitting the ranked results and total token count
   */
  Single<RerankResponse> rxRerank(RerankRequest request);
}
