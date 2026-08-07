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
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver;
import io.gravitee.singularitee.http.resolve.ModelOrPipelineResolver.Resolution;
import io.gravitee.singularitee.http.sse.VertxSseWriter;
import io.gravitee.singularitee.http.translation.EndpointType;
import io.gravitee.singularitee.http.translation.InferenceResponseFormatter;
import io.gravitee.singularitee.http.translation.SequenceAccumulator;
import io.gravitee.singularitee.http.validation.SchemaName;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** {@code POST /v1/completions} — legacy text completions, streaming and buffered. */
public final class CompletionsHandler implements Handler<RoutingContext> {

  private final GraviteeInferenceServiceImpl inference;
  private final ModelOrPipelineResolver resolver;

  public CompletionsHandler(
    GraviteeInferenceServiceImpl inference,
    ModelOrPipelineResolver resolver
  ) {
    this.inference = inference;
    this.resolver = resolver;
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.COMPLETIONS)) {
      return;
    }
    var resolution = resolver.resolve(model, payload, EndpointType.COMPLETION);
    if (resolution.isEmpty()) {
      HandlerSupport.modelNotFound(rc, model);
      return;
    }
    Resolution res = resolution.get();

    boolean stream = payload.at("/stream").asBoolean(false);
    var tokens = Dispatch.drive(inference, res, rc);

    if (stream) {
      boolean includeUsage = payload.at("/stream_options/include_usage").asBoolean(false);
      var events = InferenceResponseFormatter.completionStreamEvents(
        tokens,
        res.modelName(),
        includeUsage,
        null
      );
      VertxSseWriter.write(rc.response(), events);
    } else {
      tokens
        .collect(SequenceAccumulator::new, SequenceAccumulator::add)
        .map(acc -> InferenceResponseFormatter.buildCompletionResponse(res.modelName(), acc))
        .subscribe(
          node -> JsonResponses.writeJson(rc, node),
          err -> Dispatch.failInternal(rc, err)
        );
    }
  }
}
