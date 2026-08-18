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

import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x gRPC service implementation for pipeline lifecycle management (read-only: Get / List).
 *
 * <p>Implements the generated {@code GraviteePipelineServiceApi} and delegates all
 * persistence to {@link PipelineRegistry}.
 *
 * <p>Pipelines are registered at startup by {@code WorkspaceLoaderComponent} via
 * {@link PipelineRegistry#register(Pipeline)} directly — there is no public gRPC
 * endpoint to publish pipelines at runtime.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteePipelineServiceImpl extends GraviteePipelineServiceGrpcService {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraviteePipelineServiceImpl.class);

  private final PipelineRegistry registry;

  public GraviteePipelineServiceImpl(PipelineRegistry registry) {
    this.registry = registry;
  }

  // ---------------------------------------------------------------------------
  // GetPipeline
  // ---------------------------------------------------------------------------

  @Override
  public Future<GetPipelineResponse> getPipeline(GetPipelineRequest request) {
    var entryOpt = registry.get(request.getPipelineId());
    if (entryOpt.isEmpty()) {
      return Future.failedFuture("Pipeline not found: " + request.getPipelineId());
    }
    var entry = entryOpt.get();
    return Future.succeededFuture(
      GetPipelineResponse.newBuilder()
        .setPipeline(entry.pipeline())
        .setStatus(entry.status())
        .build()
    );
  }

  // ---------------------------------------------------------------------------
  // ListPipelines
  // ---------------------------------------------------------------------------

  @Override
  public Future<ListPipelinesResponse> listPipelines(ListPipelinesRequest request) {
    var builder = ListPipelinesResponse.newBuilder();
    for (var kv : registry.entries()) {
      if (kv.getValue().pipeline().getHidden()) continue;
      builder.addPipelines(
        GetPipelineResponse.newBuilder()
          .setPipeline(kv.getValue().pipeline())
          .setStatus(kv.getValue().status())
          .build()
      );
    }
    return Future.succeededFuture(builder.build());
  }
}
