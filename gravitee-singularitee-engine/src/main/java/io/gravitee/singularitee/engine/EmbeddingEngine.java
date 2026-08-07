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
import java.util.List;

/**
 * A non-blocking dense-vector embedding engine (ONNX BERT backend).
 *
 * <p>Embedding is fully reactive: the caller submits an {@link EmbedRequest} and
 * receives a {@link Single} that emits an {@link EmbedResponse} containing a
 * {@code float[]} vector when the model has finished encoding.
 * Implementations are responsible for offloading CPU-bound ONNX work
 * (e.g. via {@code vertx.rxExecuteBlocking}) so that the Vert.x event loop
 * is never blocked.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public non-sealed interface EmbeddingEngine extends ModelEngine {
  @Override
  default ModelEngineType type() {
    return ModelEngineType.EMBEDDING;
  }

  @Override
  default String task() {
    return ModelTasks.FEATURE_EXTRACTION;
  }

  /**
   * Encodes a single text into a dense vector.
   *
   * @param request the embedding input
   * @return a {@link Single} that emits the embedding vector and token count
   */
  Single<EmbedResponse> rxEmbed(EmbedRequest request);

  /**
   * Encodes a batch of texts, emitting one {@link EmbedResponse} per input in order.
   * Default implementation flat-maps {@link #rxEmbed} sequentially; implementations
   * may override for true batch inference.
   *
   * @param texts the input texts
   * @return a {@link Single} emitting one {@link EmbedResponse} per input, in the same order
   */
  default Single<List<EmbedResponse>> rxEmbedBatch(List<String> texts) {
    return Single.concat(
      texts
        .stream()
        .map(t -> rxEmbed(new EmbedRequest(t)))
        .toList()
    ).toList();
  }
}
