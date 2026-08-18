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
import io.gravitee.singularitee.protocol.ClassifyBatchRequest;
import io.gravitee.singularitee.protocol.ClassifyLabel;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /v1/classify} — Gravitee classification extension. Supports fixed-label classifiers,
 * token-level NER spans, and GLiNER zero-shot via a caller-supplied {@code labels} array.
 */
public final class ClassifyHandler implements Handler<RoutingContext> {

  private final GraviteeInferenceServiceImpl inference;
  private final ModelRegistry modelRegistry;

  public ClassifyHandler(GraviteeInferenceServiceImpl inference, ModelRegistry modelRegistry) {
    this.inference = inference;
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.CLASSIFY)) {
      return;
    }
    JsonNode inputNode = payload.at("/input");
    List<String> inputs = HandlerSupport.stringOrArray(inputNode);
    if (inputs.isEmpty()) {
      HandlerSupport.badRequest(rc, "Missing required parameter: 'input'", "input");
      return;
    }

    var request = ClassifyBatchRequest.newBuilder().setModelId(model).addAllTexts(inputs);
    JsonNode labels = payload.at("/labels");
    if (labels.isArray()) {
      for (JsonNode label : labels) {
        String name = label.at("/name").asText(null);
        if (name != null && !name.isBlank()) {
          request.addLabels(
            ClassifyLabel.newBuilder()
              .setName(name)
              .setDescription(label.at("/description").asText(""))
              .build()
          );
        }
      }
    }

    inference
      .classifyBatch(request.build())
      .onSuccess(resp -> {
        ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
        root.put("id", "cls-" + Instant.now().getEpochSecond());
        root.put("object", "classification");
        root.put("model", model);
        ArrayNode results = root.putArray("results");
        for (var result : resp.getResultsList()) {
          ObjectNode entry = results.addObject();
          entry.put("top_label", result.getTopLabel());
          entry.put("top_score", result.getTopScore());
          ObjectNode scores = entry.putObject("scores");
          result.getAllScoresMap().forEach((label, score) -> scores.put(label, score.floatValue()));
          // Spans are a token-classification (NER) concept: emit them only for entries that
          // carry character offsets. Sequence text classification has no spans.
          ArrayNode spans = null;
          for (var span : result.getResultsList()) {
            if (!span.hasStart()) {
              continue;
            }
            if (spans == null) {
              spans = entry.putArray("spans");
            }
            ObjectNode s = spans.addObject();
            s.put("label", span.getLabel());
            s.put("score", span.getScore());
            if (span.hasToken()) {
              s.put("token", span.getToken());
            }
            s.put("start", span.getStart());
            if (span.hasEnd()) {
              s.put("end", span.getEnd());
            }
          }
        }
        JsonResponses.writeJson(rc, root);
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }
}
