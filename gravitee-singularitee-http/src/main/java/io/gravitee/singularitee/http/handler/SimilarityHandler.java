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
import io.gravitee.singularitee.protocol.SimilarityMode;
import io.gravitee.singularitee.protocol.TextSimilarityRequest;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/** {@code POST /v1/similarity} — Gravitee text-similarity extension ({@code cross} or {@code zipped}). */
public final class SimilarityHandler implements Handler<RoutingContext> {

  private final GraviteeVectorServiceImpl vector;
  private final ModelRegistry modelRegistry;

  public SimilarityHandler(GraviteeVectorServiceImpl vector, ModelRegistry modelRegistry) {
    this.vector = vector;
    this.modelRegistry = modelRegistry;
  }

  @Override
  public void handle(RoutingContext rc) {
    JsonNode payload = HandlerSupport.parseOrFail(rc);
    if (payload == null) {
      return;
    }
    String model = HandlerSupport.requireModel(rc, payload, modelRegistry);
    if (model == null) {
      return;
    }
    if (!HandlerSupport.validate(rc, payload, SchemaName.SIMILARITY)) {
      return;
    }
    List<String> inputs = HandlerSupport.stringOrArray(payload.at("/input"));
    List<String> candidates = HandlerSupport.stringOrArray(payload.at("/candidates"));
    if (inputs.isEmpty()) {
      HandlerSupport.badRequest(rc, "Missing required parameter: 'input'", "input");
      return;
    }
    if (candidates.isEmpty()) {
      HandlerSupport.badRequest(rc, "Missing required parameter: 'candidates'", "candidates");
      return;
    }
    String modeStr = payload.at("/mode").asText("cross");
    boolean zipped = "zipped".equalsIgnoreCase(modeStr);
    if (zipped && inputs.size() != candidates.size()) {
      HandlerSupport.badRequest(
        rc,
        "'zipped' mode requires 'input' and 'candidates' to have the same length",
        "candidates"
      );
      return;
    }
    SimilarityMode mode = zipped
      ? SimilarityMode.SIMILARITY_MODE_ZIPPED
      : SimilarityMode.SIMILARITY_MODE_CROSS;

    var request = TextSimilarityRequest.newBuilder()
      .setModelId(model)
      .addAllInput(inputs)
      .addAllCandidates(candidates)
      .setMode(mode)
      .build();

    vector
      .textSimilarity(request)
      .onSuccess(resp -> {
        ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
        root.put("object", "similarity");
        root.put("model", model);
        root.put("mode", zipped ? "zipped" : "cross");
        List<Float> scores = resp.getScoresList();
        if (zipped) {
          ArrayNode results = root.putArray("results");
          for (float s : scores) {
            results.add(s);
          }
        } else {
          int candidateCount = resp.getCandidateCount();
          ArrayNode results = root.putArray("results");
          for (int i = 0; i < resp.getInputCount(); i++) {
            ArrayNode row = results.addArray();
            for (int j = 0; j < candidateCount; j++) {
              row.add(scores.get(i * candidateCount + j));
            }
          }
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("total_tokens", resp.getTotalTokens());
        JsonResponses.writeJson(rc, root);
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }
}
