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
package io.gravitee.singularitee.engine.remote;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.RerankRequest;
import io.gravitee.singularitee.engine.RerankResponse;
import io.gravitee.singularitee.engine.RerankResult;
import io.gravitee.singularitee.engine.RerankerEngine;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote proxy for {@link RerankerEngine} that calls the server's {@code TextRerank} RPC.
 *
 * <p>The remote server handles the complete rerank pipeline: cross-encoder scoring
 * (if the target model is a reranker) or bi-encoder embed-plus-cosine fallback
 * (if the target model is an embedder). The client simply forwards query + documents
 * and receives sorted results.
 *
 * <p>Fully non-blocking: delegates directly to the gRPC {@link Single} returned by the client.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemoteRerankerEngine implements RerankerEngine {

  private final SingulariteeClient client;
  private final String modelId;

  public RemoteRerankerEngine(SingulariteeClient client, String modelId) {
    this.client = client;
    this.modelId = modelId;
  }

  @Override
  public Single<RerankResponse> rxRerank(RerankRequest request) {
    var protoReq = io.gravitee.singularitee.protocol.TextRerankRequest.newBuilder()
      .setModelId(modelId)
      .setQuery(request.query())
      .addAllDocuments(request.documents())
      .setTopK(request.topK())
      .build();

    return client
      .textRerank(protoReq)
      .map(protoResp -> {
        List<RerankResult> results = new ArrayList<>(protoResp.getResultsCount());
        for (var r : protoResp.getResultsList()) {
          results.add(new RerankResult(r.getIndex(), r.getScore()));
        }
        return new RerankResponse(results, protoResp.getTotalTokens());
      });
  }

  @Override
  public void close() {
    // Nothing to close — the client is shared
  }
}
