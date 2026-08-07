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
import io.gravitee.singularitee.engine.EmbeddingEngine;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote proxy for {@link EmbeddingEngine} that calls the server's {@code Embed} RPC.
 *
 * <p>Fully non-blocking: delegates directly to the gRPC {@link Single} returned by the client
 * — no {@code blockingGet()} required.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemoteEmbeddingEngine implements EmbeddingEngine {

  private final SingulariteeClient client;
  private final String modelId;

  public RemoteEmbeddingEngine(SingulariteeClient client, String modelId) {
    this.client = client;
    this.modelId = modelId;
  }

  @Override
  public Single<io.gravitee.singularitee.engine.EmbedResponse> rxEmbed(
    io.gravitee.singularitee.engine.EmbedRequest request
  ) {
    var protoReq = io.gravitee.singularitee.protocol.EmbedRequest.newBuilder()
      .setModelId(modelId)
      .setText(request.text())
      .build();

    return client
      .embed(protoReq)
      .map(protoResp -> {
        var protoVec = protoResp.getEmbedding();
        float[] embedding = new float[protoVec.getValuesCount()];
        for (int i = 0; i < embedding.length; i++) {
          embedding[i] = protoVec.getValues(i);
        }
        return new io.gravitee.singularitee.engine.EmbedResponse(
          embedding,
          protoResp.getTokenCount()
        );
      });
  }

  @Override
  public Single<List<io.gravitee.singularitee.engine.EmbedResponse>> rxEmbedBatch(
    List<String> texts
  ) {
    var protoReq = io.gravitee.singularitee.protocol.EmbedBatchRequest.newBuilder()
      .setModelId(modelId)
      .addAllTexts(texts)
      .build();

    return client
      .embedBatch(protoReq)
      .map(batchResp -> {
        List<io.gravitee.singularitee.engine.EmbedResponse> responses = new ArrayList<>(
          batchResp.getItemsCount()
        );
        for (var item : batchResp.getItemsList()) {
          var protoVec = item.getEmbedding();
          float[] embedding = new float[protoVec.getValuesCount()];
          for (int i = 0; i < embedding.length; i++) {
            embedding[i] = protoVec.getValues(i);
          }
          responses.add(
            new io.gravitee.singularitee.engine.EmbedResponse(embedding, item.getTokenCount())
          );
        }
        return responses;
      });
  }

  @Override
  public void close() {
    // Nothing to close — the client is shared
  }
}
