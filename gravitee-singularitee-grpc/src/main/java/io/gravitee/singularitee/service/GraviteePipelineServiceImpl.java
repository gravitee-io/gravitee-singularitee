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
package io.gravitee.singularitee.service;

import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.protocol.*;
import io.vertx.core.Future;

/**
 * Vert.x gRPC service implementation for pipeline lifecycle management (read-only: Get / List).
 *
 * <p>Implements the generated {@code GraviteePipelineServiceApi} and delegates all
 * persistence to {@link PipelineRegistry}.
 *
 * <p>Pipelines are registered at startup by {@code WorkspaceLoaderComponent} via
 * {@link PipelineRegistry#register(PipelineModel)} directly; there is no public gRPC
 * endpoint to publish pipelines at runtime.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteePipelineServiceImpl extends GraviteePipelineServiceGrpcService {

  private final PipelineRegistry registry;

  /** Serves pipelines from {@code registry}. */
  public GraviteePipelineServiceImpl(PipelineRegistry registry) {
    this.registry = registry;
  }

  // ---------------------------------------------------------------------------
  // GetPipeline
  // ---------------------------------------------------------------------------

  /** {@code GetPipeline}: definition and status of one pipeline; fails when the id is unknown. */
  @Override
  public Future<GetPipelineResponse> getPipeline(GetPipelineRequest request) {
    var entryOpt = registry.get(request.getPipelineId());
    if (entryOpt.isEmpty()) {
      return Future.failedFuture("Pipeline not found: " + request.getPipelineId());
    }
    var entry = entryOpt.get();
    return Future.succeededFuture(
      GetPipelineResponse.newBuilder()
        .setPipeline(toProto(entry.pipeline()))
        .setStatus(entry.status())
        .build()
    );
  }

  /** Discovery metadata only: the DAG itself is server-internal and never crosses the wire. */
  private static Pipeline toProto(PipelineModel pipeline) {
    return Pipeline.newBuilder()
      .setPipelineId(pipeline.id())
      .setPipelineName(pipeline.name() == null ? "" : pipeline.name())
      .setTask(pipeline.task() == null ? "" : pipeline.task())
      .setHidden(pipeline.hidden())
      .addAllInputModalities(pipeline.inputModalities())
      .build();
  }

  // ---------------------------------------------------------------------------
  // ListPipelines
  // ---------------------------------------------------------------------------

  /** {@code ListPipelines}: every registered pipeline with its status. */
  @Override
  public Future<ListPipelinesResponse> listPipelines(ListPipelinesRequest request) {
    var builder = ListPipelinesResponse.newBuilder();
    for (var kv : registry.entries()) {
      if (kv.getValue().pipeline().hidden()) continue;
      builder.addPipelines(
        GetPipelineResponse.newBuilder()
          .setPipeline(toProto(kv.getValue().pipeline()))
          .setStatus(kv.getValue().status())
          .build()
      );
    }
    return Future.succeededFuture(builder.build());
  }
}
