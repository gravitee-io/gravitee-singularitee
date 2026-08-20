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

/**
 * {@code GET /v1/models} and {@code GET /v1/models/{id}} — lists models (and pipelines).
 *
 * <p>Pipelines are listed as models, described by their task, and never labelled as
 * pipelines: a caller picks an id by the surface it serves, and how the answer is
 * produced — one model or a guarded, routed DAG of them — is the server's business.
 * The workspace loader only admits the five task slugs, so {@code type} is a closed
 * set here and {@code "pipeline"} is never one of its values.
 *
 * <p>Hidden models and pipelines are absent from the listing and 404 on the
 * single-id route, matching what {@link io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver}
 * does on the inference routes.
 */
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
      .onSuccess(m -> {
        if (m.getHidden()) {
          notFound(rc, id);
          return;
        }
        JsonResponses.writeJson(
          rc,
          modelNode(m.getModelId(), m.getTask(), m.getInputModalitiesList())
        );
      })
      .onFailure(err -> {
        if (!exposePipelines) {
          notFound(rc, id);
          return;
        }
        pipelines
          .getPipeline(GetPipelineRequest.newBuilder().setPipelineId(id).build())
          .onSuccess(p -> {
            if (p.getPipeline().getHidden()) {
              notFound(rc, id);
              return;
            }
            JsonResponses.writeJson(
              rc,
              modelNode(
                p.getPipeline().getPipelineId(),
                p.getPipeline().getTask(),
                p.getPipeline().getInputModalitiesList()
              )
            );
          })
          .onFailure(e2 -> notFound(rc, id));
      });
  }

  private ObjectNode buildList(ListModelsResponse ml, ListPipelinesResponse pl) {
    ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
    root.put("object", "list");
    ArrayNode data = root.putArray("data");
    for (var m : ml.getModelsList()) {
      data.add(modelNode(m.getModelId(), m.getTask(), m.getInputModalitiesList()));
    }
    if (pl != null) {
      for (var p : pl.getPipelinesList()) {
        data.add(
          modelNode(
            p.getPipeline().getPipelineId(),
            p.getPipeline().getTask(),
            p.getPipeline().getInputModalitiesList()
          )
        );
      }
    }
    return root;
  }

  /**
   * Renders one catalogue entry.
   *
   * <p>{@code input_modalities} is emitted only when the entry reads more than text:
   * text-only is the overwhelming majority and the assumption every OpenAI client
   * already makes, so listing it on every entry would be noise that says nothing.
   */
  private static final java.util.List<String> TEXT_ONLY = java.util.List.of("text");

  private ObjectNode modelNode(String id, String task, java.util.List<String> inputModalities) {
    ObjectNode node = Utils.OBJECT_MAPPER.get().createObjectNode();
    node.put("id", id);
    node.put("object", "model");
    node.put("created", created);
    node.put("owned_by", OWNER);
    if (task != null && !task.isBlank()) {
      node.put("type", task);
    }
    if (!TEXT_ONLY.equals(inputModalities)) {
      ArrayNode modalities = node.putArray("input_modalities");
      inputModalities.forEach(modalities::add);
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
