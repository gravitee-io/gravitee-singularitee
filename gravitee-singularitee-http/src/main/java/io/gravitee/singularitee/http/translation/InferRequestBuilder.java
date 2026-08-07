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
package io.gravitee.singularitee.http.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.TagConfig;
import java.util.UUID;

/**
 * Builds {@link InferRequest} protobuf messages (the raw single-model path) from OpenAI-compatible
 * JSON, reusing the message / sampling / tool helpers in {@link PipelineRequestBuilder}.
 *
 * <p>Conversational endpoints (chat, responses) get {@code <think>}/{@code </think>} reasoning tags
 * so the engine splits thinking tokens out for {@code reasoning_content}. Tools are forwarded as raw
 * JSON strings ({@code tools_json}) for chat-template injection.
 */
public final class InferRequestBuilder {

  private static final TagConfig THINK_TAGS = TagConfig.newBuilder()
    .setOpenTag("<think>")
    .setCloseTag("</think>")
    .build();

  private InferRequestBuilder() {}

  public static InferRequest build(String modelId, JsonNode payload, EndpointType endpointType) {
    InferRequest.Builder builder = InferRequest.newBuilder().setModelId(modelId);

    if (endpointType == EndpointType.CHAT) {
      builder.setMessages(PipelineRequestBuilder.buildChatMessageList(payload.at("/messages")));
      builder.setReasoningTags(THINK_TAGS);
    } else if (endpointType == EndpointType.RESPONSES) {
      builder.setMessages(PipelineRequestBuilder.buildResponsesMessageList(payload));
      builder.setReasoningTags(THINK_TAGS);
    } else {
      JsonNode promptNode = payload.at("/prompt");
      if (promptNode.isTextual()) {
        builder.setPrompt(promptNode.asText());
      } else if (promptNode.isArray()) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : promptNode) {
          if (item.isTextual()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(item.asText());
          }
        }
        builder.setPrompt(sb.toString());
      }
    }

    // Tools → tools_json (the engine injects them via the chat template).
    JsonNode toolsNode = payload.at("/tools");
    if (toolsNode.isArray()) {
      for (JsonNode toolNode : toolsNode) {
        builder.addToolsJson(toolNode.toString());
      }
    }

    // stop sequences: string or array of strings.
    JsonNode stop = payload.at("/stop");
    if (stop.isTextual()) {
      builder.addStop(stop.asText());
    } else if (stop.isArray()) {
      for (JsonNode s : stop) {
        if (s.isTextual()) {
          builder.addStop(s.asText());
        }
      }
    }

    SamplingParams sp = PipelineRequestBuilder.buildSamplingParams(payload);
    if (sp != null) {
      builder.setSamplingParams(sp);
    }

    String cacheKey = PipelineRequestBuilder.extractCacheKey(payload);
    if (cacheKey != null) {
      builder.setCacheKey(cacheKey);
    }

    String reasoningEffort = PipelineRequestBuilder.extractReasoningEffort(payload);
    if (reasoningEffort != null) {
      builder.setTemplateContext(
        Struct.newBuilder()
          .putFields("reasoning_effort", Value.newBuilder().setStringValue(reasoningEffort).build())
          .build()
      );
    }

    builder.setRequestId("req-" + UUID.randomUUID());
    return builder.build();
  }
}
