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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.llmbridge4j.core.LlmBridge;
import io.gravitee.llmbridge4j.core.or.v1.model.LlmRequest;
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

/** {@code POST /v1/chat/completions} — OpenAI Chat Completions, streaming and buffered. */
public final class ChatCompletionsHandler implements Handler<RoutingContext> {

  private final GraviteeInferenceServiceImpl inference;
  private final ModelOrPipelineResolver resolver;
  private final LlmBridge bridge;

  public ChatCompletionsHandler(
    GraviteeInferenceServiceImpl inference,
    ModelOrPipelineResolver resolver,
    LlmBridge bridge
  ) {
    this.inference = inference;
    this.resolver = resolver;
    this.bridge = bridge;
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.CHAT)) {
      return;
    }
    java.util.Optional<Resolution> resolution;
    try {
      LlmRequest canonical = bridge.toCanonical("openai-chat", normalizeForBridge(payload));
      resolution = resolver.resolve(model, canonical);
    } catch (IllegalArgumentException e) {
      HandlerSupport.badRequest(rc, e.getMessage(), "messages");
      return;
    }
    if (resolution.isEmpty()) {
      HandlerSupport.modelNotFound(rc, model);
      return;
    }
    Resolution res = resolution.get();

    boolean stream = payload.at("/stream").asBoolean(false);
    var toolSchemas = InferenceResponseFormatter.toolParameterSchemas(payload.at("/tools"));
    var tokens = Dispatch.drive(inference, res, rc);

    if (stream) {
      boolean includeUsage = payload.at("/stream_options/include_usage").asBoolean(false);
      var events = res.hasTools()
        ? InferenceResponseFormatter.chatStreamEventsWithToolHoldback(
          tokens,
          res.modelName(),
          includeUsage,
          null,
          toolSchemas
        )
        : InferenceResponseFormatter.chatStreamEvents(tokens, res.modelName(), includeUsage, null);
      VertxSseWriter.write(rc.response(), events);
    } else {
      tokens
        .collect(SequenceAccumulator::new, SequenceAccumulator::add)
        .map(acc -> InferenceResponseFormatter.buildChatResponse(res.modelName(), acc, toolSchemas))
        .subscribe(
          node -> JsonResponses.writeJson(rc, node),
          err -> Dispatch.failInternal(rc, err)
        );
    }
  }

  /**
   * Keeps representable legacy Chat Completions behavior while the bridge owns wire parsing.
   *
   * <p>The bridge's canonical model intentionally has stricter role semantics than Singularitee's
   * historical loose schema. This defensive copy also spells the two legacy aliases that are
   * otherwise not recoverable after canonicalization.
   */
  static JsonNode normalizeForBridge(JsonNode payload) {
    if (!(payload instanceof ObjectNode copy)) {
      return payload;
    }
    copy = copy.deepCopy();

    JsonNode flatEffort = copy.path("reasoning_effort");
    JsonNode nestedEffort = copy.path("reasoning").path("effort");
    if ((!flatEffort.isTextual() || flatEffort.asText().isEmpty()) && nestedEffort.isTextual()) {
      copy.put("reasoning_effort", nestedEffort.asText());
    }

    if (copy.path("logprobs").asBoolean(false) && copy.path("top_logprobs").isMissingNode()) {
      copy.put("top_logprobs", 1);
    }

    JsonNode messages = copy.path("messages");
    if (messages.isArray()) {
      for (JsonNode message : messages) {
        if (!(message instanceof ObjectNode object)) {
          continue;
        }
        JsonNode role = object.path("role");
        if (
          role.isTextual() &&
          switch (role.asText()) {
            case "system", "developer", "user", "assistant", "tool", "function" -> false;
            default -> true;
          }
        ) {
          object.put("role", "user");
        }
      }
    }
    return copy;
  }
}
