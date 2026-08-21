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
import io.gravitee.singularitee.protocol.EmbedBatchRequest;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

/**
 * {@code POST /v1/embeddings}: OpenAI embeddings.
 *
 * <p>Accepts {@code model}, {@code input} (string or array of strings) and
 * {@code encoding_format} ({@code float}, the default, or {@code base64} little-endian
 * float32). Emits 400 {@code invalid_request_error} for a malformed payload, 400
 * {@code model_not_found} for an unknown or hidden model, and 500 {@code internal_error} on
 * engine failure.
 */
public final class EmbeddingsHandler implements Handler<RoutingContext> {

  private final GraviteeVectorServiceImpl vector;
  private final ModelRegistry modelRegistry;

  public EmbeddingsHandler(GraviteeVectorServiceImpl vector, ModelRegistry modelRegistry) {
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.EMBEDDINGS)) {
      return;
    }
    JsonNode inputNode = payload.at("/input");
    if (inputNode.isMissingNode() || inputNode.isNull()) {
      HandlerSupport.badRequest(rc, "Missing required parameter: 'input'", "input");
      return;
    }
    List<String> inputs = HandlerSupport.stringOrArray(inputNode);
    if (inputs.isEmpty()) {
      HandlerSupport.badRequest(
        rc,
        "'input' must be a non-empty string or array of strings",
        "input"
      );
      return;
    }
    boolean base64 = "base64".equals(payload.at("/encoding_format").asText("float"));

    var request = EmbedBatchRequest.newBuilder().setModelId(model).addAllTexts(inputs).build();
    vector
      .embedBatch(request)
      .onSuccess(resp -> {
        ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
        root.put("object", "list");
        root.put("model", model);
        ArrayNode data = root.putArray("data");
        int totalTokens = 0;
        for (int i = 0; i < resp.getItemsCount(); i++) {
          var item = resp.getItems(i);
          List<Float> values = item.getEmbedding().getValuesList();
          ObjectNode entry = data.addObject();
          entry.put("object", "embedding");
          entry.put("index", i);
          if (base64) {
            entry.put("embedding", toBase64(values));
          } else {
            ArrayNode vec = entry.putArray("embedding");
            for (float v : values) {
              vec.add(v);
            }
          }
          totalTokens += item.getTokenCount();
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", totalTokens);
        usage.put("total_tokens", totalTokens);
        JsonResponses.writeJson(rc, root);
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }

  private static String toBase64(List<Float> values) {
    ByteBuffer buf = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (Float v : values) {
      buf.putFloat(v);
    }
    return Base64.getEncoder().encodeToString(buf.array());
  }
}
