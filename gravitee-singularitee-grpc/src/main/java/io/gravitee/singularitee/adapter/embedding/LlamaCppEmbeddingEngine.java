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
package io.gravitee.singularitee.adapter.embedding;

import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.engine.EmbedRequest;
import io.gravitee.singularitee.engine.EmbedResponse;
import io.gravitee.singularitee.engine.EmbeddingEngine;
import io.gravitee.singularitee.inference.api.embedding.EmbeddingTokenCount;
import io.gravitee.singularitee.inference.llama.cpp.encoder.LlamaCppEmbeddingModel;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.List;

/**
 * {@link EmbeddingEngine} backed by a llama.cpp embedding model (GGUF).
 *
 * <p>Supports both encoder-only (BERT, ModernBERT, Jina) and decoder-based
 * (Qwen3-Embedding) architectures. Pooling strategy (CLS, MEAN, LAST) and any
 * instruction template are configured at factory time via
 * {@link LlamaCppEmbeddingFactory}.
 *
 * <p>This class and {@link LlamaCppEmbeddingFactory} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-llama-cpp} embedding types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppEmbeddingEngine
  extends BlockingEngineAdapter<LlamaCppEmbeddingModel>
  implements EmbeddingEngine {

  LlamaCppEmbeddingEngine(LlamaCppEmbeddingModel delegate, Vertx vertx) {
    super(delegate, vertx);
  }

  @Override
  public Single<EmbedResponse> rxEmbed(EmbedRequest request) {
    return rxInfer(() -> {
      EmbeddingTokenCount result = delegate.infer(request.text());
      return new EmbedResponse(result.embedding(), result.tokenCount());
    });
  }

  @Override
  public Single<List<EmbedResponse>> rxEmbedBatch(List<String> texts) {
    return rxInfer(() ->
      delegate
        .inferAll(texts)
        .stream()
        .map(r -> new EmbedResponse(r.embedding(), r.tokenCount()))
        .toList()
    );
  }

  @Override
  public void close() throws Exception {
    delegate.close();
  }
}
