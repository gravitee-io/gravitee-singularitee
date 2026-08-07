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
package io.gravitee.singularitee.inference.api.textgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared payload parsing utilities for generation requests.
 * Extracts and validates fields from deserialized Map payloads (event bus messages).
 */
public final class PayloadParser {

  private static final Logger LOGGER = LoggerFactory.getLogger(PayloadParser.class);

  private PayloadParser() {}

  public static String stringValue(Object value) {
    return value instanceof String s ? s : null;
  }

  public static Integer intValue(Object value) {
    if (value instanceof Integer i) return i;
    if (value instanceof Number n) return n.intValue();
    return null;
  }

  public static Float floatValue(Object value) {
    if (value instanceof Float f) return f;
    if (value instanceof Number n) return n.floatValue();
    return null;
  }

  @SuppressWarnings("unchecked")
  public static List<String> parseStop(Object value) {
    if (value instanceof String s) {
      return List.of(s);
    }
    if (value instanceof List<?> list) {
      return (List<String>) list;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> parseTools(Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      return null;
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        result.add((Map<String, Object>) map);
      }
    }
    return result.isEmpty() ? null : result;
  }

  public static Role toRole(String role) {
    if (role == null) {
      return Role.USER;
    }

    return switch (role.toLowerCase().trim()) {
      case "assistant" -> Role.ASSISTANT;
      case "system" -> Role.SYSTEM;
      case "user" -> Role.USER;
      default -> {
        LOGGER.warn("Unknown role '{}', defaulting to USER", role);
        yield Role.USER;
      }
    };
  }

  @SuppressWarnings("unchecked")
  public static List<ChatMessage> parseMessages(Object value) {
    if (!(value instanceof List<?> list)) {
      return null;
    }
    List<ChatMessage> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        String role = stringValue(map.get("role"));
        if (role == null) {
          continue;
        }

        Object contentObj = map.get("content");
        String textContent = null;
        List<Content> mediaContent = new ArrayList<>();

        if (contentObj instanceof String str) {
          textContent = str;
        } else if (contentObj instanceof List<?> contentList) {
          for (Object contentItem : contentList) {
            if (contentItem instanceof Map<?, ?> contentMap) {
              String contentType = stringValue(contentMap.get("type"));
              if ("text".equals(contentType)) {
                String text = stringValue(contentMap.get("text"));
                if (text != null) {
                  textContent = (textContent == null) ? text : textContent + "\n" + text;
                }
              } else if ("image_url".equals(contentType)) {
                Object imageUrlObj = contentMap.get("image_url");
                if (imageUrlObj instanceof Map<?, ?> imageUrlMap) {
                  String imageUrl = stringValue(imageUrlMap.get("url"));
                  if (imageUrl != null) {
                    String base64Data = extractBase64Data(imageUrl, "image");
                    if (base64Data != null) {
                      mediaContent.add(
                        new ImageContent(MediaType.APPLICATION_OCTET_STREAM, base64Data)
                      );
                    }
                  }
                }
              } else if ("input_audio".equals(contentType)) {
                Object audioObj = contentMap.get("input_audio");
                if (audioObj instanceof Map<?, ?> audioMap) {
                  String base64Data = stringValue(audioMap.get("data"));
                  if (base64Data != null && !base64Data.trim().isEmpty()) {
                    mediaContent.add(
                      new AudioContent(MediaType.APPLICATION_OCTET_STREAM, base64Data)
                    );
                  }
                }
              }
            }
          }
        }

        if (textContent != null || !mediaContent.isEmpty()) {
          result.add(
            new ChatMessage(toRole(role), textContent != null ? textContent : "", mediaContent)
          );
        }
      }
    }
    return result.isEmpty() ? null : result;
  }

  /**
   * Extracts base64 data from media URLs. Supports:
   * <ul>
   *   <li>Data URLs: "data:image/jpeg;base64,/9j/4AAQSkZJRg..."</li>
   *   <li>Pure base64: "/9j/4AAQSkZJRg..."</li>
   * </ul>
   * Rejects HTTP URLs.
   *
   * @param mediaUrl the URL or base64 data
   * @param mediaType "image" or "audio" for log messages
   * @return base64 data if valid, null otherwise
   */
  public static String extractBase64Data(String mediaUrl, String mediaType) {
    if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
      LOGGER.warn("Empty {} URL provided", mediaType);
      return null;
    }

    if (mediaUrl.startsWith("data:")) {
      int commaIndex = mediaUrl.indexOf(",");
      if (commaIndex <= 0) {
        LOGGER.warn("Invalid data URL for {}: missing base64 data after comma", mediaType);
        return null;
      }
      String base64Data = mediaUrl.substring(commaIndex + 1).trim();
      if (base64Data.isEmpty()) {
        LOGGER.warn("Empty base64 data in {} data URL", mediaType);
        return null;
      }
      return base64Data;
    }

    if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
      LOGGER.warn(
        "HTTP/HTTPS URLs for {} are not supported. " +
          "Please use base64-encoded data or data URLs. Received URL: {}",
        mediaType,
        mediaUrl
      );
      return null;
    }

    try {
      java.util.Base64.getDecoder().decode(mediaUrl);
      return mediaUrl;
    } catch (IllegalArgumentException e) {
      LOGGER.warn(
        "Invalid {} data: not valid base64 and not a data URL. " +
          "Expected format: base64 string or data:mime/type;base64,<data>",
        mediaType
      );
      return null;
    }
  }
}
