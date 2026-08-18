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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.gravitee.llmbridge4j.core.model.InferenceConfig;
import io.gravitee.llmbridge4j.core.model.ToolSpec;
import io.gravitee.llmbridge4j.core.or.v1.model.ContentPart;
import io.gravitee.llmbridge4j.core.or.v1.model.ContentPart.InputAudio;
import io.gravitee.llmbridge4j.core.or.v1.model.ContentPart.InputImage;
import io.gravitee.llmbridge4j.core.or.v1.model.FunctionCallItem;
import io.gravitee.llmbridge4j.core.or.v1.model.FunctionCallOutputItem;
import io.gravitee.llmbridge4j.core.or.v1.model.Item;
import io.gravitee.llmbridge4j.core.or.v1.model.LlmRequest;
import io.gravitee.llmbridge4j.core.or.v1.model.MessageItem;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferRequest;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.MediaType;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.TagConfig;
import io.gravitee.singularitee.protocol.ToolCall;
import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolParameterDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maps the provider-neutral Chat Completions request into Singularitee's inference protobufs. */
public final class CanonicalChatRequestMapper {

  private static final TagConfig THINK_TAGS = TagConfig.newBuilder()
    .setOpenTag("<think>")
    .setCloseTag("</think>")
    .build();

  private CanonicalChatRequestMapper() {}

  /** Maps a canonical request to the direct text-generation protobuf. */
  public static InferRequest toDirect(String modelId, LlmRequest request) {
    InferRequest.Builder builder = InferRequest.newBuilder()
      .setModelId(modelId)
      .setMessages(toMessages(request));

    SamplingParams sampling = toSampling(request);
    if (sampling != null) {
      builder.setSamplingParams(sampling);
    }
    if (request.tools() != null) {
      request
        .tools()
        .stream()
        .map(CanonicalChatRequestMapper::toToolJson)
        .forEach(builder::addToolsJson);
    }
    if (request.config() != null) {
      for (String stop : request.config().stopSequencesOrEmpty()) {
        builder.addStop(stop);
      }
    }
    builder.setReasoningTags(THINK_TAGS);

    String cacheKey = cacheKey(request);
    if (cacheKey != null) {
      builder.setCacheKey(cacheKey);
    }
    if (request.reasoning() != null && request.reasoning().effort() != null) {
      builder.setTemplateContext(reasoningContext(request.reasoning().effort()));
    }
    builder.setRequestId("req-" + UUID.randomUUID());
    return builder.build();
  }

  /** Maps a canonical request to the pipeline inference protobuf. */
  public static InferPipelineRequest toPipeline(String pipelineId, LlmRequest request) {
    InferPipelineRequest.Builder builder = InferPipelineRequest.newBuilder()
      .setPipelineId(pipelineId)
      .setMessages(toMessages(request));

    SamplingParams sampling = toSampling(request);
    if (sampling != null) {
      builder.setSamplingParams(sampling);
    }
    if (request.tools() != null) {
      request
        .tools()
        .stream()
        .map(CanonicalChatRequestMapper::toToolDefinition)
        .filter(java.util.Objects::nonNull)
        .forEach(builder::addTools);
    }

    String cacheKey = cacheKey(request);
    if (cacheKey != null) {
      builder.setCacheKey(cacheKey);
    }
    if (request.reasoning() != null && request.reasoning().effort() != null) {
      builder.putContext("reasoning_effort", request.reasoning().effort());
    }
    return builder.build();
  }

  private static ChatMessageList toMessages(LlmRequest request) {
    List<ChatMessage.Builder> builders = new ArrayList<>();
    if (request.instructions() != null) {
      builders.add(
        ChatMessage.newBuilder().setRole(Role.ROLE_SYSTEM).setContent(request.instructions())
      );
    }
    if (request.input() != null) {
      for (Item item : request.input()) {
        if (item instanceof MessageItem message) {
          if (!isEmptyShell(message)) {
            builders.add(toMessage(message));
          }
        } else if (item instanceof FunctionCallItem call) {
          addFunctionCall(builders, call);
        } else if (item instanceof FunctionCallOutputItem output) {
          addFunctionOutput(builders, output);
        }
      }
    }
    ChatMessageList.Builder list = ChatMessageList.newBuilder();
    builders.stream().map(ChatMessage.Builder::build).forEach(list::addMessages);
    return list.build();
  }

  private static ChatMessage.Builder toMessage(MessageItem message) {
    ChatMessage.Builder builder = ChatMessage.newBuilder().setRole(toRole(message.role()));
    StringBuilder text = new StringBuilder();
    if (message.content() != null) {
      for (ContentPart part : message.content()) {
        if (part instanceof InputImage image) {
          MediaContent media = toImage(image);
          if (media != null) {
            builder.addMedia(media);
          }
        } else if (part instanceof InputAudio audio) {
          MediaContent media = toAudio(audio);
          if (media != null) {
            builder.addMedia(media);
          }
        } else {
          String partText = ContentPart.textOf(part);
          if (partText != null && !partText.isEmpty()) {
            if (!text.isEmpty()) {
              text.append("\n");
            }
            text.append(partText);
          }
        }
      }
    }
    builder.setContent(text.toString());
    return builder;
  }

  private static Role toRole(MessageItem.Role role) {
    if (role == null) {
      return Role.ROLE_USER;
    }
    return switch (role) {
      case SYSTEM -> Role.ROLE_SYSTEM;
      case ASSISTANT -> Role.ROLE_ASSISTANT;
      case DEVELOPER, USER -> Role.ROLE_USER;
    };
  }

  private static void addFunctionCall(List<ChatMessage.Builder> builders, FunctionCallItem call) {
    ChatMessage.Builder assistant;
    if (!builders.isEmpty() && builders.getLast().getRole() == Role.ROLE_ASSISTANT) {
      assistant = builders.getLast();
    } else {
      assistant = ChatMessage.newBuilder().setRole(Role.ROLE_ASSISTANT).setContent("");
      builders.add(assistant);
    }
    ToolCall.Builder tool = ToolCall.newBuilder()
      .setName(call.name() == null ? "" : call.name())
      .setArgumentsJson(
        call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments()
      );
    if (call.callId() != null && !call.callId().isEmpty()) {
      tool.setId(call.callId());
    }
    assistant.addToolCalls(tool.build());
  }

  private static void addFunctionOutput(
    List<ChatMessage.Builder> builders,
    FunctionCallOutputItem output
  ) {
    ChatMessage.Builder tool = ChatMessage.newBuilder()
      .setRole(Role.ROLE_TOOL)
      .setContent(output.outputValue() == null ? "" : output.outputValue().asText());
    if (output.callId() != null && !output.callId().isEmpty()) {
      tool.setToolCallId(output.callId());
    }
    String name = metadataText(output.providerMetadata(), "name");
    if (name != null && !name.isEmpty()) {
      tool.setName(name);
    }
    builders.add(tool);
  }

  private static boolean isEmptyShell(MessageItem message) {
    String shape = metadataText(message.providerMetadata(), "message_content_shape");
    return "null".equals(shape) || "absent".equals(shape);
  }

  private static String metadataText(Map<String, JsonNode> metadata, String suffix) {
    JsonNode value = metadataValue(metadata, suffix);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static JsonNode metadataValue(Map<String, JsonNode> metadata, String suffix) {
    if (metadata == null) return null;
    for (Map.Entry<String, JsonNode> entry : metadata.entrySet()) {
      if (entry.getKey().equals(suffix) || entry.getKey().endsWith(":" + suffix)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static MediaContent toImage(InputImage image) {
    String base64 = PipelineRequestBuilder.extractBase64Data(image.url(), "image");
    if (base64 == null) {
      return null;
    }
    String mime = image.mediaType() != null ? image.mediaType() : mimeFromDataUrl(image.url());
    return MediaContent.newBuilder()
      .setMediaType(imageMediaType(mime))
      .setData(com.google.protobuf.ByteString.copyFromUtf8(base64))
      .build();
  }

  private static MediaContent toAudio(InputAudio audio) {
    String base64 = PipelineRequestBuilder.extractBase64Data(audio.data(), "audio");
    if (base64 == null) {
      return null;
    }
    String mime = audio.format() == null || audio.format().isBlank()
      ? mimeFromDataUrl(audio.data())
      : "audio/" + audio.format();
    return MediaContent.newBuilder()
      .setMediaType(audioMediaType(mime))
      .setData(com.google.protobuf.ByteString.copyFromUtf8(base64))
      .build();
  }

  private static SamplingParams toSampling(LlmRequest request) {
    InferenceConfig config = request.config();
    Integer topLogprobs = request.topLogprobs();
    Integer maxTokens = legacyMaxTokens(request);
    if (config == null && maxTokens == null && (topLogprobs == null || topLogprobs <= 0)) {
      return null;
    }
    SamplingParams.Builder builder = SamplingParams.newBuilder();
    boolean any = false;
    if (maxTokens != null) {
      builder.setMaxTokens(maxTokens);
      any = true;
    }
    if (config != null) {
      if (config.temperature() != null) {
        builder.setTemperature(config.temperature().floatValue());
        any = true;
      }
      if (config.topP() != null) {
        builder.setTopP(config.topP().floatValue());
        any = true;
      }
      if (config.presencePenalty() != null) {
        builder.setPresencePenalty(config.presencePenalty().floatValue());
        any = true;
      }
      if (config.frequencyPenalty() != null) {
        builder.setFrequencyPenalty(config.frequencyPenalty().floatValue());
        any = true;
      }
      if (config.seed() != null) {
        builder.setSeed(config.seed().intValue());
        any = true;
      }
    }
    if (topLogprobs != null && topLogprobs > 0) {
      builder.setTopLogprobs(topLogprobs);
      any = true;
    }
    return any ? builder.build() : null;
  }

  /** Reconstructs the legacy max-token precedence from 0.6's spelling metadata. */
  private static Integer legacyMaxTokens(LlmRequest request) {
    JsonNode spelling = metadataValue(request.metadata(), "max_tokens_spelling");
    if (spelling != null && spelling.isObject()) {
      JsonNode maxTokens = spelling.path("max_tokens");
      if (maxTokens.isIntegralNumber()) return maxTokens.asInt();
    }
    if (spelling != null && spelling.isTextual()) {
      if ("max_tokens".equals(spelling.asText()) && request.config() != null) {
        return request.config().maxOutputTokens();
      }
      if ("max_completion_tokens".equals(spelling.asText())) {
        JsonNode fallback = metadataValue(request.metadata(), "max_output_tokens");
        return fallback != null && fallback.isIntegralNumber() ? fallback.asInt() : null;
      }
    }
    JsonNode fallback = metadataValue(request.metadata(), "max_output_tokens");
    if (fallback != null && fallback.isIntegralNumber()) return fallback.asInt();
    return spelling == null && request.config() != null ? request.config().maxOutputTokens() : null;
  }

  private static Struct reasoningContext(String effort) {
    return Struct.newBuilder()
      .putFields("reasoning_effort", Value.newBuilder().setStringValue(effort).build())
      .build();
  }

  private static String cacheKey(LlmRequest request) {
    if (request.promptCacheKey() != null && !request.promptCacheKey().isEmpty()) {
      return request.promptCacheKey();
    }
    Map<String, JsonNode> metadata = request.metadata();
    if (metadata == null) {
      return null;
    }
    for (Map.Entry<String, JsonNode> entry : metadata.entrySet()) {
      if (
        (entry.getKey().equals("user") || entry.getKey().endsWith(":user")) &&
        entry.getValue().isTextual()
      ) {
        String value = entry.getValue().asText();
        if (!value.isEmpty()) return value;
      }
    }
    return null;
  }

  private static String toToolJson(ToolSpec tool) {
    ObjectNode function = JsonNodeFactory.instance.objectNode();
    function.put("name", tool.name());
    if (tool.description() != null) function.put("description", tool.description());
    if (tool.parameters() != null) function.set("parameters", tool.parameters());
    if (tool.strict() != null) function.put("strict", tool.strict());
    ObjectNode outer = JsonNodeFactory.instance.objectNode();
    outer.put("type", "function");
    outer.set("function", function);
    return outer.toString();
  }

  private static ToolDefinition toToolDefinition(ToolSpec tool) {
    if (tool.name() == null || tool.name().isEmpty()) return null;
    ToolDefinition.Builder builder = ToolDefinition.newBuilder()
      .setName(tool.name())
      .setDescription(tool.description() == null ? "" : tool.description())
      .setTemplate(toToolJson(tool));
    JsonNode parameters = tool.parameters();
    if (parameters == null || !parameters.isObject()) return builder.build();
    Set<String> required = new HashSet<>();
    JsonNode requiredNode = parameters.path("required");
    if (requiredNode.isArray()) requiredNode.forEach(node -> required.add(node.asText()));
    JsonNode properties = parameters.path("properties");
    if (properties.isObject()) {
      properties
        .fields()
        .forEachRemaining(entry -> {
          JsonNode definition = entry.getValue();
          builder.addParameters(
            ToolParameterDef.newBuilder()
              .setName(entry.getKey())
              .setType(definition.path("type").asText("string"))
              .setDescription(definition.path("description").asText(""))
              .setRequired(required.contains(entry.getKey()))
              .build()
          );
        });
    }
    return builder.build();
  }

  private static String mimeFromDataUrl(String value) {
    if (value == null || !value.startsWith("data:")) return null;
    int semicolon = value.indexOf(';');
    int comma = value.indexOf(',');
    int end = semicolon > 0 ? semicolon : comma;
    return end > 5 ? value.substring(5, end).trim().toLowerCase(Locale.ROOT) : null;
  }

  private static MediaType imageMediaType(String mime) {
    if (mime == null) return MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    return switch (mime.toLowerCase(Locale.ROOT)) {
      case "image/jpeg", "image/jpg" -> MediaType.MEDIA_TYPE_IMAGE_JPEG;
      case "image/png" -> MediaType.MEDIA_TYPE_IMAGE_PNG;
      case "image/gif" -> MediaType.MEDIA_TYPE_IMAGE_GIF;
      case "image/bmp" -> MediaType.MEDIA_TYPE_IMAGE_BMP;
      default -> MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    };
  }

  private static MediaType audioMediaType(String mime) {
    if (mime == null) return MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    return switch (mime.toLowerCase(Locale.ROOT)) {
      case "audio/wav", "audio/x-wav", "audio/wave" -> MediaType.MEDIA_TYPE_AUDIO_WAV;
      default -> MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    };
  }
}
