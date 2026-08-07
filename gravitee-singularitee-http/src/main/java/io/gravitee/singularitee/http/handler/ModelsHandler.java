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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.protocol.GetModelRequest;
import io.gravitee.singularitee.protocol.GetPipelineRequest;
import io.gravitee.singularitee.protocol.ListModelsRequest;
import io.gravitee.singularitee.protocol.ListModelsResponse;
import io.gravitee.singularitee.protocol.ListPipelinesRequest;
import io.gravitee.singularitee.protocol.ListPipelinesResponse;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;

/** {@code GET /v1/models} and {@code GET /v1/models/{id}} — lists models (and pipelines). */
public final class ModelsHandler {

  private static final String OWNER = "gravitee";

  private final GraviteeModelServiceImpl models;
  private final GraviteePipelineServiceImpl pipelines;
  private final boolean exposePipelines;
  private final long created = Instant.now().getEpochSecond();

  public ModelsHandler(
    GraviteeModelServiceImpl models,
    GraviteePipelineServiceImpl pipelines,
    boolean exposePipelines
  ) {
    this.models = models;
    this.pipelines = pipelines;
    this.exposePipelines = exposePipelines;
  }

  public void list(RoutingContext rc) {
    models
      .listModels(ListModelsRequest.getDefaultInstance())
      .onSuccess(ml -> {
        if (exposePipelines) {
          pipelines
            .listPipelines(ListPipelinesRequest.getDefaultInstance())
            .onSuccess(pl -> JsonResponses.writeJson(rc, buildList(ml, pl)))
            .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
        } else {
          JsonResponses.writeJson(rc, buildList(ml, null));
        }
      })
      .onFailure(err -> HandlerSupport.mapServiceError(rc, err));
  }

  public void getOne(RoutingContext rc) {
    String id = rc.pathParam("model");
    models
      .getModel(GetModelRequest.newBuilder().setModelId(id).build())
      .onSuccess(m -> JsonResponses.writeJson(rc, modelNode(m.getModelId(), m.getTask())))
      .onFailure(err -> {
        if (!exposePipelines) {
          notFound(rc, id);
          return;
        }
        pipelines
          .getPipeline(GetPipelineRequest.newBuilder().setPipelineId(id).build())
          .onSuccess(p ->
            JsonResponses.writeJson(rc, modelNode(p.getPipeline().getPipelineId(), "pipeline"))
          )
          .onFailure(e2 -> notFound(rc, id));
      });
  }

  private ObjectNode buildList(ListModelsResponse ml, ListPipelinesResponse pl) {
    ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
    root.put("object", "list");
    ArrayNode data = root.putArray("data");
    for (var m : ml.getModelsList()) {
      data.add(modelNode(m.getModelId(), m.getTask()));
    }
    if (pl != null) {
      for (var p : pl.getPipelinesList()) {
        data.add(modelNode(p.getPipeline().getPipelineId(), "pipeline"));
      }
    }
    return root;
  }

  private ObjectNode modelNode(String id, String type) {
    ObjectNode node = Utils.OBJECT_MAPPER.get().createObjectNode();
    node.put("id", id);
    node.put("object", "model");
    node.put("created", created);
    node.put("owned_by", OWNER);
    if (type != null && !type.isBlank()) {
      node.put("type", type);
    }
    return node;
  }

  private static void notFound(RoutingContext rc, String id) {
    JsonResponses.writeError(
      rc,
      404,
      "The model `" + id + "` does not exist",
      "invalid_request_error",
      "model",
      "model_not_found"
    );
  }
}
