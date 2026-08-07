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
package io.gravitee.singularitee.http.router;

import io.gravitee.singularitee.http.handler.ChatCompletionsHandler;
import io.gravitee.singularitee.http.handler.ClassifyHandler;
import io.gravitee.singularitee.http.handler.CompletionsHandler;
import io.gravitee.singularitee.http.handler.EmbeddingsHandler;
import io.gravitee.singularitee.http.handler.ModelsHandler;
import io.gravitee.singularitee.http.handler.RerankHandler;
import io.gravitee.singularitee.http.handler.ResponsesHandler;
import io.gravitee.singularitee.http.handler.SimilarityHandler;
import io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Mounts the OpenAI-compatible (+ Gravitee extension) routes on a vert.x-web {@link Router}. Each
 * path is mounted both bare and under {@code /v1} (OpenAI SDKs use {@code /v1/...}).
 */
public final class OpenAiRoutes {

  private OpenAiRoutes() {}

  public static void mount(
    Router router,
    GraviteeInferenceServiceImpl inference,
    GraviteeVectorServiceImpl vector,
    GraviteeModelServiceImpl models,
    GraviteePipelineServiceImpl pipelines,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    boolean exposePipelines
  ) {
    var resolver = new ModelOrPipelineResolver(modelRegistry, pipelineRegistry);

    var chat = new ChatCompletionsHandler(inference, resolver);
    var completions = new CompletionsHandler(inference, resolver);
    var responses = new ResponsesHandler(inference, resolver);
    var embeddings = new EmbeddingsHandler(vector);
    var classify = new ClassifyHandler(inference);
    var rerank = new RerankHandler(vector);
    var similarity = new SimilarityHandler(vector);
    var modelsHandler = new ModelsHandler(models, pipelines, exposePipelines);

    post(router, "/chat/completions", chat);
    post(router, "/completions", completions);
    post(router, "/responses", responses);
    post(router, "/embeddings", embeddings);
    post(router, "/classify", classify);
    post(router, "/rerank", rerank);
    post(router, "/similarity", similarity);

    router.get("/v1/models").handler(modelsHandler::list);
    router.get("/models").handler(modelsHandler::list);
    router.get("/v1/models/:model").handler(modelsHandler::getOne);
    router.get("/models/:model").handler(modelsHandler::getOne);
  }

  private static void post(Router router, String path, Handler<RoutingContext> handler) {
    router.post("/v1" + path).handler(handler);
    router.post(path).handler(handler);
  }
}
