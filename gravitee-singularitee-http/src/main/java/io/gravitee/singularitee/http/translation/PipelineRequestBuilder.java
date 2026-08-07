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
import com.google.protobuf.ByteString;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.MediaContent;
import io.gravitee.singularitee.protocol.MediaType;
import io.gravitee.singularitee.protocol.Role;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolParameterDef;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@link InferPipelineRequest} protobuf messages from OpenAI-compatible JSON payloads.
 *
 * <p>The message / sampling / tool helpers are {@code public} so the raw-model
 * {@link InferRequestBuilder} can reuse them.
 */
public final class PipelineRequestBuilder {

  private static final Logger log = LoggerFactory.getLogger(PipelineRequestBuilder.class);

  private PipelineRequestBuilder() {}

  /** Builds an {@link InferPipelineRequest} for the given pipeline id, payload and endpoint type. */
  public static InferPipelineRequest build(
    String pipelineId,
    JsonNode payload,
    EndpointType endpointType
  ) {
    InferPipelineRequest.Builder builder = InferPipelineRequest.newBuilder().setPipelineId(
      pipelineId
    );

    if (endpointType == EndpointType.CHAT) {
      builder.setMessages(buildChatMessageList(payload.at("/messages")));
    } else if (endpointType == EndpointType.RESPONSES) {
      builder.setMessages(buildResponsesMessageList(payload));
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

    // Map OpenAI tools array to ToolDefinition protos for {{tools}} prompt injection.
    JsonNode toolsNode = payload.at("/tools");
    if (toolsNode.isArray()) {
      for (JsonNode toolNode : toolsNode) {
        ToolDefinition toolDef = buildToolDefinition(toolNode);
        if (toolDef != null) {
          builder.addTools(toolDef);
        }
      }
    }

    SamplingParams sp = buildSamplingParams(payload);
    if (sp != null) {
      builder.setSamplingParams(sp);
    }

    String cacheKey = extractCacheKey(payload);
    if (cacheKey != null) {
      builder.setCacheKey(cacheKey);
    }

    String reasoningEffort = extractReasoningEffort(payload);
    if (reasoningEffort != null) {
      builder.putContext("reasoning_effort", reasoningEffort);
    }

    return builder.build();
  }

  /**
   * Extracts the reasoning effort, accepting both OpenAI spellings on every endpoint: the Chat
   * Completions flat {@code reasoning_effort} and the Responses nested {@code reasoning.effort}
   * (flat wins when both are present). It is a chat-template variable, not a sampling parameter:
   * allowed values are enforced by the request schema, and the string is passed through verbatim
   * for the model's template to consume. Returns {@code null} when absent.
   */
  public static String extractReasoningEffort(JsonNode payload) {
    JsonNode effort = payload.path("reasoning_effort");
    if (!effort.isTextual() || effort.asText().isEmpty()) {
      effort = payload.path("reasoning").path("effort");
    }
    return effort.isTextual() && !effort.asText().isEmpty() ? effort.asText() : null;
  }

  /**
   * Extracts the client cache-affinity key: OpenAI {@code prompt_cache_key},
   * falling back to {@code user}. Returns {@code null} when neither is present.
   */
  public static String extractCacheKey(JsonNode payload) {
    JsonNode key = payload.path("prompt_cache_key");
    if (!key.isTextual() || key.asText().isEmpty()) {
      key = payload.path("user");
    }
    return key.isTextual() && !key.asText().isEmpty() ? key.asText() : null;
  }

  /**
   * Extracts OpenAI-compatible sampling parameters ({@code max_tokens}/{@code max_output_tokens},
   * {@code temperature}, {@code top_p}, {@code presence_penalty}, {@code frequency_penalty},
   * {@code seed}). Returns {@code null} when none are present.
   */
  public static SamplingParams buildSamplingParams(JsonNode payload) {
    SamplingParams.Builder builder = SamplingParams.newBuilder();
    boolean any = false;

    JsonNode maxTokens = payload.path("max_tokens");
    if (!maxTokens.isNumber()) {
      maxTokens = payload.path("max_output_tokens");
    }
    if (maxTokens.isNumber()) {
      builder.setMaxTokens(maxTokens.asInt());
      any = true;
    }

    JsonNode temperature = payload.path("temperature");
    if (temperature.isNumber()) {
      builder.setTemperature(temperature.floatValue());
      any = true;
    }

    JsonNode topP = payload.path("top_p");
    if (topP.isNumber()) {
      builder.setTopP(topP.floatValue());
      any = true;
    }

    JsonNode presencePenalty = payload.path("presence_penalty");
    if (presencePenalty.isNumber()) {
      builder.setPresencePenalty(presencePenalty.floatValue());
      any = true;
    }

    JsonNode frequencyPenalty = payload.path("frequency_penalty");
    if (frequencyPenalty.isNumber()) {
      builder.setFrequencyPenalty(frequencyPenalty.floatValue());
      any = true;
    }

    JsonNode seed = payload.path("seed");
    if (seed.isNumber()) {
      builder.setSeed(seed.asInt());
      any = true;
    }

    int topLogprobs = resolveTopLogprobs(payload);
    if (topLogprobs > 0) {
      builder.setTopLogprobs(topLogprobs);
      any = true;
    }

    return any ? builder.build() : null;
  }

  /**
   * OpenAI semantics: {@code logprobs} (boolean) turns collection on;
   * {@code top_logprobs} (0-20) sets how many alternatives to return and is
   * only meaningful when {@code logprobs} is true. With {@code logprobs: true}
   * and no {@code top_logprobs}, only the chosen token's logprob is returned —
   * internally that is a collection depth of 1.
   */
  public static int resolveTopLogprobs(JsonNode payload) {
    if (!payload.path("logprobs").asBoolean(false)) {
      return 0;
    }
    JsonNode top = payload.path("top_logprobs");
    return top.isNumber() ? Math.max(1, top.asInt()) : 1;
  }

  public static ChatMessageList buildChatMessageList(JsonNode messagesNode) {
    ChatMessageList.Builder listBuilder = ChatMessageList.newBuilder();
    if (!messagesNode.isArray()) {
      return listBuilder.build();
    }
    for (JsonNode msgNode : messagesNode) {
      String role = msgNode.at("/role").asText(null);
      JsonNode contentNode = msgNode.at("/content");
      JsonNode toolCallsNode = msgNode.at("/tool_calls");
      boolean hasToolCalls = toolCallsNode.isArray() && !toolCallsNode.isEmpty();
      // A tool-call turn is the OpenAI-correct {"content": null, "tool_calls": [...]}. Its null
      // content must never reach applyContent, whose toString() fallback would render the four
      // characters "null" as the assistant's words — a long agent session then teaches the model,
      // few-shot from its own transcript, that assistant turns say "null". But the turn itself
      // has to survive: drop it and the transcript records what the model said and never what it
      // did, so on the next pass it sees an unanswered question and calls the same tool again.
      if (role == null) {
        continue;
      }
      boolean noContent = contentNode.isMissingNode() || contentNode.isNull();
      if (noContent && !hasToolCalls) {
        continue;
      }
      Role chatRole = switch (role) {
        case "system" -> Role.ROLE_SYSTEM;
        case "assistant" -> Role.ROLE_ASSISTANT;
        case "tool", "function" -> Role.ROLE_TOOL;
        default -> Role.ROLE_USER;
      };
      ChatMessage.Builder msgBuilder = ChatMessage.newBuilder().setRole(chatRole);
      if (!noContent) {
        applyContent(msgBuilder, contentNode);
      }
      if (hasToolCalls) {
        applyToolCalls(msgBuilder, toolCallsNode);
      }
      if (chatRole == Role.ROLE_TOOL) {
        String callId = msgNode.at("/tool_call_id").asText("");
        if (!callId.isEmpty()) {
          msgBuilder.setToolCallId(callId);
        }
        String name = msgNode.at("/name").asText("");
        if (!name.isEmpty()) {
          msgBuilder.setName(name);
        }
      }
      listBuilder.addMessages(msgBuilder.build());
    }
    return listBuilder.build();
  }

  /**
   * Carries the assistant's {@code tool_calls} into the transcript.
   *
   * <p>Arguments stay as their JSON text here; the renderer parses them, because templates
   * generally want a mapping (Gemma's raises on a string).
   */
  static void applyToolCalls(ChatMessage.Builder msgBuilder, JsonNode toolCallsNode) {
    for (JsonNode call : toolCallsNode) {
      JsonNode fn = call.path("function");
      String name = fn.path("name").asText("");
      if (name.isEmpty()) {
        continue;
      }
      JsonNode args = fn.path("arguments");
      // OpenAI sends arguments as a JSON *string*; some clients send the object directly.
      String argumentsJson = args.isTextual()
        ? args.asText()
        : (args.isMissingNode() || args.isNull() ? "{}" : args.toString());
      var callBuilder = io.gravitee.singularitee.protocol.ToolCall.newBuilder()
        .setName(name)
        .setArgumentsJson(argumentsJson.isBlank() ? "{}" : argumentsJson);
      String id = call.path("id").asText("");
      if (!id.isEmpty()) {
        callBuilder.setId(id);
      }
      msgBuilder.addToolCalls(callBuilder.build());
    }
  }

  /**
   * Applies an OpenAI-compatible {@code content} node onto a {@link ChatMessage.Builder}.
   *
   * <p>A plain string sets the text content. An array of content parts is walked: {@code text} /
   * {@code input_text} / {@code output_text} parts are concatenated (newline-separated) into the
   * text content, while {@code image_url} / {@code input_image} and {@code input_audio} parts are
   * extracted as base64 and added as {@link MediaContent}. The base64 payload is stored as UTF-8
   * bytes to match the downstream gRPC/engine contract ({@code getData().toStringUtf8()} → base64
   * decode). Non-array, non-textual content falls back to its JSON string form.
   */
  static void applyContent(ChatMessage.Builder msgBuilder, JsonNode contentNode) {
    if (contentNode.isTextual()) {
      msgBuilder.setContent(contentNode.asText());
      return;
    }
    if (!contentNode.isArray()) {
      // Anything else (object, number, boolean) has no sensible text form — its JSON source
      // would be injected verbatim as the message's words, exactly as `null` was.
      return;
    }

    StringBuilder text = new StringBuilder();
    for (JsonNode part : contentNode) {
      if (!part.isObject()) {
        continue;
      }
      String partType = part.at("/type").asText("");
      switch (partType) {
        case "text", "input_text", "output_text" -> {
          String t = part.at("/text").asText(null);
          if (t != null && !t.isEmpty()) {
            if (!text.isEmpty()) text.append("\n");
            text.append(t);
          }
        }
        case "image_url", "input_image" -> {
          // Chat Completions nests the url under image_url.url; Responses input_image may
          // provide image_url as a bare string.
          JsonNode imageUrlNode = part.at("/image_url");
          String url = imageUrlNode.isObject()
            ? imageUrlNode.at("/url").asText(null)
            : (imageUrlNode.isTextual() ? imageUrlNode.asText(null) : null);
          MediaContent media = buildImageMedia(url);
          if (media != null) {
            msgBuilder.addMedia(media);
          }
        }
        case "input_audio" -> {
          JsonNode audioNode = part.at("/input_audio");
          if (audioNode.isObject()) {
            String data = audioNode.at("/data").asText(null);
            String format = audioNode.at("/format").asText(null);
            MediaContent media = buildAudioMedia(data, format);
            if (media != null) {
              msgBuilder.addMedia(media);
            }
          }
        }
        default -> {
          // Unknown content part types are ignored.
        }
      }
    }
    msgBuilder.setContent(text.toString());
  }

  private static MediaContent buildImageMedia(String url) {
    String base64 = extractBase64Data(url, "image");
    if (base64 == null) {
      return null;
    }
    return MediaContent.newBuilder()
      .setMediaType(imageMediaType(mimeFromDataUrl(url)))
      .setData(ByteString.copyFromUtf8(base64))
      .build();
  }

  private static MediaContent buildAudioMedia(String data, String format) {
    String base64 = extractBase64Data(data, "audio");
    if (base64 == null) {
      return null;
    }
    // OpenAI input_audio carries a format hint ("wav", "mp3"); fall back to the data URL MIME.
    String mime = format != null && !format.isBlank() ? "audio/" + format : mimeFromDataUrl(data);
    return MediaContent.newBuilder()
      .setMediaType(audioMediaType(mime))
      .setData(ByteString.copyFromUtf8(base64))
      .build();
  }

  /**
   * Extracts base64 data from a media URL. Supports {@code data:} URLs and bare base64 strings.
   * Remote HTTP(S) URLs are rejected with an {@link IllegalArgumentException} (mapped to a 400
   * {@code invalid_request_error}) since the gateway does not fetch remote content. Returns the
   * base64 string (unchanged) or {@code null} when the value is empty/absent.
   */
  static String extractBase64Data(String mediaUrl, String mediaType) {
    if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
      return null;
    }
    if (mediaUrl.startsWith("data:")) {
      int commaIndex = mediaUrl.indexOf(',');
      if (commaIndex <= 0) {
        throw new IllegalArgumentException(
          "Invalid " + mediaType + " data URL: missing base64 payload"
        );
      }
      String base64 = mediaUrl.substring(commaIndex + 1).trim();
      if (base64.isEmpty()) {
        throw new IllegalArgumentException("Empty base64 payload in " + mediaType + " data URL");
      }
      return base64;
    }
    if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
      throw new IllegalArgumentException(
        "Remote " +
          mediaType +
          " URLs are not supported; provide the " +
          mediaType +
          " as base64-encoded data or a data URL"
      );
    }
    try {
      java.util.Base64.getDecoder().decode(mediaUrl);
      return mediaUrl;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Invalid " + mediaType + " data: expected a data URL or base64-encoded content"
      );
    }
  }

  /** Returns the MIME type declared in a {@code data:<mime>;base64,...} URL, or {@code null}. */
  private static String mimeFromDataUrl(String url) {
    if (url == null || !url.startsWith("data:")) {
      return null;
    }
    int semi = url.indexOf(';');
    int comma = url.indexOf(',');
    int end = semi > 0 ? semi : comma;
    if (end <= 5) {
      return null;
    }
    return url.substring(5, end).trim().toLowerCase(Locale.ROOT);
  }

  private static MediaType imageMediaType(String mime) {
    if (mime == null) {
      return MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    }
    return switch (mime) {
      case "image/jpeg", "image/jpg" -> MediaType.MEDIA_TYPE_IMAGE_JPEG;
      case "image/png" -> MediaType.MEDIA_TYPE_IMAGE_PNG;
      case "image/gif" -> MediaType.MEDIA_TYPE_IMAGE_GIF;
      case "image/bmp" -> MediaType.MEDIA_TYPE_IMAGE_BMP;
      // image/webp and image/tiff intentionally absent — stb_image cannot decode
      // them, so they fall through to APPLICATION_OCTET like any other unknown type.
      default -> MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    };
  }

  private static MediaType audioMediaType(String mime) {
    if (mime == null) {
      return MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    }
    return switch (mime) {
      case "audio/wav", "audio/x-wav", "audio/wave" -> MediaType.MEDIA_TYPE_AUDIO_WAV;
      // Compressed audio (mp3/ogg/flac/aac/m4a) is intentionally absent:
      // javax.sound.sampled has no reader for it, so it fell through to the
      // engine and was dropped silently.
      default -> MediaType.MEDIA_TYPE_APPLICATION_OCTET;
    };
  }

  /**
   * Translates an OpenAI Responses payload ({@code instructions} + {@code input}) into a
   * {@link ChatMessageList}: {@code instructions} becomes a leading system message, {@code input}
   * a string (single user message) or an array of message objects / bare strings.
   */
  public static ChatMessageList buildResponsesMessageList(JsonNode payload) {
    ChatMessageList.Builder listBuilder = ChatMessageList.newBuilder();

    JsonNode instructions = payload.at("/instructions");
    if (instructions.isTextual() && !instructions.asText().isBlank()) {
      listBuilder.addMessages(
        ChatMessage.newBuilder().setRole(Role.ROLE_SYSTEM).setContent(instructions.asText()).build()
      );
    }

    JsonNode input = payload.at("/input");
    if (input.isTextual()) {
      listBuilder.addMessages(
        ChatMessage.newBuilder().setRole(Role.ROLE_USER).setContent(input.asText()).build()
      );
    } else if (input.isArray()) {
      for (JsonNode item : input) {
        if (item.isTextual()) {
          listBuilder.addMessages(
            ChatMessage.newBuilder().setRole(Role.ROLE_USER).setContent(item.asText()).build()
          );
        } else if (item.isObject()) {
          String role = item.at("/role").asText("user");
          JsonNode contentNode = item.at("/content");
          Role chatRole = switch (role) {
            case "system", "developer" -> Role.ROLE_SYSTEM;
            case "assistant" -> Role.ROLE_ASSISTANT;
            default -> Role.ROLE_USER;
          };
          ChatMessage.Builder msgBuilder = ChatMessage.newBuilder().setRole(chatRole);
          if (!contentNode.isMissingNode()) {
            applyContent(msgBuilder, contentNode);
          }
          listBuilder.addMessages(msgBuilder.build());
        }
      }
    }

    return listBuilder.build();
  }

  /**
   * Maps a single OpenAI tool JSON object to a {@link ToolDefinition}. The full tool JSON is stored
   * as the {@code template} field (preserving the exact schema for {@code {{tools}}} injection);
   * {@code name}/{@code description}/{@code parameters} are also populated. Supports both the nested
   * Chat Completions shape and the flat Responses shape.
   */
  public static ToolDefinition buildToolDefinition(JsonNode toolNode) {
    JsonNode functionNode = toolNode.at("/function");
    if (functionNode.isMissingNode() || !functionNode.isObject()) {
      functionNode = toolNode;
    }

    String name = functionNode.at("/name").asText("");
    if (name.isEmpty()) {
      return null;
    }
    String description = functionNode.at("/description").asText("");

    ToolDefinition.Builder builder = ToolDefinition.newBuilder()
      .setName(name)
      .setDescription(description);

    try {
      builder.setTemplate(Utils.OBJECT_MAPPER.get().writeValueAsString(toolNode));
    } catch (Exception e) {
      log.debug(
        "[singularitee] Failed to serialize tool JSON for template, using default rendering",
        e
      );
    }

    JsonNode parametersNode = functionNode.at("/parameters");
    if (parametersNode.isObject()) {
      JsonNode propertiesNode = parametersNode.at("/properties");
      JsonNode requiredNode = parametersNode.at("/required");
      Set<String> requiredSet = new HashSet<>();
      if (requiredNode.isArray()) {
        for (JsonNode r : requiredNode) {
          requiredSet.add(r.asText());
        }
      }
      if (propertiesNode.isObject()) {
        var fields = propertiesNode.fields();
        while (fields.hasNext()) {
          var entry = fields.next();
          String paramName = entry.getKey();
          JsonNode paramSchema = entry.getValue();
          builder.addParameters(
            ToolParameterDef.newBuilder()
              .setName(paramName)
              .setType(paramSchema.at("/type").asText("string"))
              .setDescription(paramSchema.at("/description").asText(""))
              .setRequired(requiredSet.contains(paramName))
              .build()
          );
        }
      }
    }

    return builder.build();
  }
}
