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
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.validation.SchemaName;
import io.gravitee.singularitee.protocol.ClassifyBatchRequest;
import io.gravitee.singularitee.protocol.ClassifyLabel;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/classify}: Gravitee classification extension.
 *
 * <p>Accepts {@code model}, {@code input} (string or array of strings) and an optional
 * {@code labels} array of {@code {name, description}} for zero-shot classifiers. Supports
 * fixed-label classifiers, token-level NER spans, and zero-shot GLiNER and decision classifiers.
 * Emits 400 {@code invalid_request_error} for a malformed payload or labels a decision classifier
 * refuses, 400 {@code model_not_found} for an unknown or hidden model, and 500
 * {@code internal_error} on engine failure.
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
        boolean reported = false;
        int promptTokens = 0;
        int completionTokens = 0;
        for (var result : resp.getResultsList()) {
          if (result.hasUsage()) {
            reported = true;
            promptTokens += result.getUsage().getPromptTokens();
            completionTokens += result.getUsage().getCompletionTokens();
          }
          ObjectNode entry = results.addObject();
          entry.put("top_label", result.getTopLabel());
          entry.put("top_score", result.getTopScore());
          ObjectNode scores = entry.putObject("scores");
          result.getAllScoresMap().forEach((label, score) -> scores.put(label, score.floatValue()));
          // Spans are a token-classification (NER) concept: emit them only for entries that
          // carry character offsets. Sequence text classification has no spans.
          ArrayNode spans = null;
          Map<String, List<Float>> spanScores = new LinkedHashMap<>();
          for (var span : result.getResultsList()) {
            if (!span.hasStart()) {
              continue;
            }
            if (spans == null) {
              spans = entry.putArray("spans");
            }
            spanScores
              .computeIfAbsent(span.getLabel(), l -> new ArrayList<>())
              .add(span.getScore());
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
            if (span.getMetadataCount() > 0) {
              ObjectNode metadata = s.putObject("metadata");
              span.getMetadataMap().forEach(metadata::put);
            }
          }
          // A label found in several spans scores each one, in span order; a label with a
          // single span, and every sequence-classification label, keeps a plain number.
          spanScores.forEach((label, values) -> {
            if (values.size() > 1) {
              ArrayNode perSpan = scores.putArray(label);
              values.forEach(perSpan::add);
            }
          });
        }
        // Only engines that count tokens report them; an absent usage means not reported, not zero.
        if (reported) {
          ObjectNode usage = root.putObject("usage");
          usage.put("prompt_tokens", promptTokens);
          usage.put("completion_tokens", completionTokens);
          usage.put("total_tokens", promptTokens + completionTokens);
        }
        JsonResponses.writeJson(rc, root);
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }
}
