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
package io.gravitee.singularitee.http.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.validation.SchemaName;
import io.gravitee.singularitee.protocol.TextRerankRequest;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/** {@code POST /v1/rerank} — Cohere-style reranking (cross-encoder, or bi-encoder fallback). */
public final class RerankHandler implements Handler<RoutingContext> {

  private final GraviteeVectorServiceImpl vector;

  public RerankHandler(GraviteeVectorServiceImpl vector) {
    this.vector = vector;
  }

  @Override
  public void handle(RoutingContext rc) {
    JsonNode payload = HandlerSupport.parseOrFail(rc);
    if (payload == null) {
      return;
    }
    String model = HandlerSupport.requireModel(rc, payload);
    if (model == null) {
      return;
    }
    if (!HandlerSupport.validate(rc, payload, SchemaName.RERANK)) {
      return;
    }
    String query = payload.at("/query").asText(null);
    if (query == null || query.isBlank()) {
      HandlerSupport.badRequest(rc, "Missing required parameter: 'query'", "query");
      return;
    }
    List<String> documents = HandlerSupport.stringOrArray(payload.at("/documents"));
    if (documents.isEmpty()) {
      HandlerSupport.badRequest(rc, "'documents' must be a non-empty array", "documents");
      return;
    }
    int topK = payload.at("/top_k").asInt(0);
    boolean returnDocuments = payload.at("/return_documents").asBoolean(true);

    var request = TextRerankRequest.newBuilder()
      .setModelId(model)
      .setQuery(query)
      .addAllDocuments(documents)
      .setTopK(topK)
      .build();

    vector
      .textRerank(request)
      .onSuccess(resp -> {
        ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
        root.put("object", "rerank");
        root.put("model", model);
        ArrayNode results = root.putArray("results");
        for (var r : resp.getResultsList()) {
          ObjectNode entry = results.addObject();
          entry.put("index", r.getIndex());
          entry.put("score", r.getScore());
          if (returnDocuments && r.getIndex() >= 0 && r.getIndex() < documents.size()) {
            entry.put("document", documents.get(r.getIndex()));
          }
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("total_tokens", resp.getTotalTokens());
        JsonResponses.writeJson(rc, root);
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }
}
