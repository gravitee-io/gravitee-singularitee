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

/**
 * {@code POST /v1/responses}: OpenAI Responses API, streaming and buffered.
 *
 * <p>Accepts {@code model}, {@code input} (string or item array), {@code instructions},
 * {@code tools}, {@code stream}, {@code store}, {@code previous_response_id} and sampling
 * fields. Pipeline targets support stored-conversation continuation: every response gets a
 * {@code resp_...} id it is stored under (unless {@code store: false}), and
 * {@code previous_response_id} resumes the server-curated transcript, so the client sends only
 * the new {@code input}. Direct model targets remain stateless. Emits 400
 * {@code invalid_request_error} for a malformed payload, 400 {@code model_not_found} for an
 * unknown or hidden target, 400 {@code unsupported_modality} for media the target cannot
 * read, and 500 {@code internal_error} on engine failure.
 */
public final class ResponsesHandler implements Handler<RoutingContext> {

  private final GraviteeInferenceServiceImpl inference;
  private final ModelOrPipelineResolver resolver;

  public ResponsesHandler(
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.RESPONSES)) {
      return;
    }
    java.util.Optional<Resolution> resolution;
    try {
      resolution = resolver.resolve(model, payload, EndpointType.RESPONSES);
    } catch (IllegalArgumentException e) {
      HandlerSupport.badRequest(rc, e.getMessage(), "input");
      return;
    }
    if (resolution.isEmpty()) {
      HandlerSupport.modelNotFound(rc, model);
      return;
    }
    Resolution res = resolution.get();
    if (!HandlerSupport.requireSupportedModalities(rc, payload, res, "input")) {
      return;
    }

    boolean stream = payload.at("/stream").asBoolean(false);
    var toolSchemas = InferenceResponseFormatter.toolParameterSchemas(payload.at("/tools"));
    // Stored-conversation continuation: the emitted response must carry the same id the
    // pipeline request stores it under, so the client can continue via previous_response_id.
    String responseId = res.pipeline() && !res.pipelineRequest().getRequestId().isBlank()
      ? res.pipelineRequest().getRequestId()
      : null;
    var tokens = Dispatch.drive(inference, res, rc);

    if (stream) {
      var events = res.hasTools()
        ? InferenceResponseFormatter.responsesBufferedStreamEvents(
          tokens,
          res.modelName(),
          null,
          toolSchemas,
          responseId
        )
        : InferenceResponseFormatter.responsesStreamEvents(
          tokens,
          res.modelName(),
          null,
          responseId
        );
      VertxSseWriter.write(rc.response(), events);
    } else {
      tokens
        .collect(SequenceAccumulator::new, SequenceAccumulator::add)
        .map(acc ->
          InferenceResponseFormatter.buildResponsesResponse(
            res.modelName(),
            acc,
            toolSchemas,
            responseId
          )
        )
        .subscribe(
          node -> JsonResponses.writeJson(rc, node),
          err -> Dispatch.failInternal(rc, err)
        );
    }
  }
}
