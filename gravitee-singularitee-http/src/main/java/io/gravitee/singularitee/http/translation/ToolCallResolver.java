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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.engine.tools.ToolCallExtractor;
import io.gravitee.singularitee.http.json.Utils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts structured tool calls from raw model output — wire calls, bare TOOL-channel
 * payloads and legacy tagged text — and applies schema-driven argument coercion.
 */
public final class ToolCallResolver {

  private static final Logger log = LoggerFactory.getLogger(ToolCallResolver.class);

  private static final ThreadLocal<ObjectMapper> OBJECT_MAPPER = Utils.OBJECT_MAPPER;

  /** OpenAI call ids are {@code call_} + 24 hex-ish chars. */
  private static final int TOOL_CALL_ID_LENGTH = 24;

  /**
   * Argument strings at or below this length (e.g. {@code "{}"}) are too short to prove the
   * content is the call payload itself — narration containing them is kept.
   */
  private static final int NARRATION_ARGS_MIN_LENGTH = 5;

  /**
   * Tool-markup openers recognized by {@link #parseToolCalls(String, Map)}. Content suffixes
   * that are a prefix of one of these are held back during streaming until disambiguated.
   */
  static final List<String> TOOL_MARKUP_OPENERS = List.of(
    "<tool_call>",
    "<function=",
    "<|tool_call>"
  );

  private ToolCallResolver() {}

  /**
   * Parses tool calls from raw model output into structured tool calls, each assigned a
   * {@code call_<uuid>} id, by rendering the built-in Jinja extraction templates (chatml-json,
   * xml-function, gemma-call — see {@code ToolCallExtractor}) in order until one yields calls.
   * Returns an empty list if nothing extracts (fail-open — the caller surfaces raw text).
   */
  public static List<ParsedToolCall> parseToolCalls(String content) {
    return parseToolCalls(content, Map.of());
  }

  /** True when the accumulated stream should be checked for tool calls. */
  static boolean isToolCandidate(SequenceAccumulator accumulator) {
    return (
      "tool_calls".equals(accumulator.finishReason()) ||
      !accumulator.tool().isEmpty() ||
      !accumulator.wireToolCalls().isEmpty()
    );
  }

  /**
   * Fail-open content: the regular content plus any unparseable bare tool payload, so a parse
   * failure surfaces the raw text to the client instead of silently dropping it.
   */
  static String contentWithToolFallback(SequenceAccumulator accumulator) {
    return accumulator.content() + accumulator.tool();
  }

  /**
   * The assistant's NARRATION accompanying tool calls — the visible text a model
   * writes before calling ("I'll create the engine module now"), which OpenAI
   * delivers as content alongside tool_calls (Chat) or a message item before the
   * function_call items (Responses). Returns null when there is none, or when
   * the text IS the call payload (markerless dialects put the call in the
   * content — echoing it as narration would duplicate every call as prose).
   */
  static String narrationText(SequenceAccumulator accumulator, List<ParsedToolCall> calls) {
    String content = accumulator.content();
    if (content == null || content.isBlank()) {
      return null;
    }
    for (ParsedToolCall tc : calls) {
      String args = tc.arguments();
      if (args != null && args.length() > NARRATION_ARGS_MIN_LENGTH && content.contains(args)) {
        return null;
      }
      if (tc.name() != null && content.trim().startsWith(tc.name())) {
        return null;
      }
    }
    return content.trim();
  }

  /**
   * Resolves tool calls for a fully-accumulated stream: structured wire calls from the final
   * COMPLETED event win (engine-side extraction already ran the configured template); otherwise
   * the client-side fallback extraction runs over the bare tool payload, then the full content.
   */
  static List<ParsedToolCall> resolveToolCalls(
    SequenceAccumulator accumulator,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    List<ParsedToolCall> wire = fromWireToolCalls(
      accumulator.wireToolCalls(),
      toolParameterSchemas
    );
    if (!wire.isEmpty()) {
      return wire;
    }
    return resolveToolCalls(accumulator.tool(), accumulator.content(), toolParameterSchemas);
  }

  /**
   * Resolves tool calls with the channel-signal-first strategy: the BARE tool payload
   * (accumulated from {@code STEP_ROLE_TOOL} deltas, tag markers suppressed engine-side) is
   * parsed first via {@link #parseBareToolCalls}; when it is empty or unparseable, the legacy
   * marker-based {@link #parseToolCalls(String, Map)} runs over the full content (older
   * engines still emit literal tags in the text). Returns an empty list when neither
   * yields calls — callers fail open by flushing the raw text as content.
   */
  public static List<ParsedToolCall> resolveToolCalls(
    String toolContent,
    String fullContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    List<ParsedToolCall> calls = parseBareToolCalls(toolContent, toolParameterSchemas);
    if (!calls.isEmpty()) {
      return calls;
    }
    return parseToolCalls(fullContent, toolParameterSchemas);
  }

  /**
   * Parses a BARE (marker-less) tool payload, as delivered on the TOOL channel by engines
   * that suppress tag markers. Currently the same template chain as
   * {@link #parseToolCalls(String, Map)} handles both shapes; this entry point exists so
   * bare-payload parsing can diverge without touching callers.
   */
  public static List<ParsedToolCall> parseBareToolCalls(
    String toolContent,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    return parseToolCalls(toolContent, toolParameterSchemas);
  }

  /**
   * Same as {@link #parseToolCalls(String)}, but coerces string-valued arguments recovered from
   * untyped dialect text (XML / Gemma flavors — flagged by the extraction template) to their
   * declared JSON-schema types. {@code toolParameterSchemas} maps function name → the tool's
   * {@code parameters} JSON schema (see {@link #toolParameterSchemas(JsonNode)}); an empty map
   * disables coercion. JSON-flavor tool calls already carry native types and are left untouched.
   */
  public static List<ParsedToolCall> parseToolCalls(
    String content,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    var extracted = ToolCallExtractor.extract(content, toolsData(toolParameterSchemas), null);
    List<ParsedToolCall> result = new ArrayList<>(extracted.size());
    for (var call : extracted) {
      result.add(
        toParsedToolCall(
          call.name(),
          call.argumentsJson(),
          call.coercibleArgs(),
          toolParameterSchemas
        )
      );
    }
    return result;
  }

  /**
   * Builds a {@link ParsedToolCall} from extracted data, applying schema-driven coercion to the
   * argument names flagged coercible (string values only; unknown types and parse failures keep
   * the string — fail-open, identical to the legacy XML/Gemma behavior).
   */
  private static ParsedToolCall toParsedToolCall(
    String name,
    String argumentsJson,
    List<String> coercibleArgs,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    String arguments = argumentsJson;
    if (coercibleArgs != null && !coercibleArgs.isEmpty()) {
      try {
        JsonNode argsNode = OBJECT_MAPPER.get().readTree(argumentsJson);
        if (argsNode.isObject()) {
          JsonNode parametersSchema = toolParameterSchemas.get(name);
          ObjectNode coerced = OBJECT_MAPPER.get().createObjectNode();
          var fields = argsNode.fields();
          while (fields.hasNext()) {
            var field = fields.next();
            if (coercibleArgs.contains(field.getKey()) && field.getValue().isTextual()) {
              putCoercedArgument(
                coerced,
                field.getKey(),
                field.getValue().asText(),
                parametersSchema
              );
            } else {
              coerced.set(field.getKey(), field.getValue());
            }
          }
          arguments = coerced.toString();
        }
      } catch (Exception e) {
        log.debug("[singularitee] Failed to coerce tool-call arguments: {}", argumentsJson, e);
      }
    }
    return new ParsedToolCall(generateToolCallId(), name, arguments);
  }

  /** Converts structured wire tool calls into {@link ParsedToolCall}s, applying schema coercion. */
  public static List<ParsedToolCall> fromWireToolCalls(
    List<WireToolCall> wireToolCalls,
    Map<String, JsonNode> toolParameterSchemas
  ) {
    if (wireToolCalls == null || wireToolCalls.isEmpty()) {
      return List.of();
    }
    List<ParsedToolCall> result = new ArrayList<>(wireToolCalls.size());
    for (WireToolCall call : wireToolCalls) {
      ParsedToolCall parsed = toParsedToolCall(
        call.name(),
        call.argumentsJson(),
        call.coercibleArgs(),
        toolParameterSchemas
      );
      // The engine-born id is authoritative: the stored conversation replays
      // the call under it, so re-minting one here would break the pairing.
      result.add(
        call.id() != null && !call.id().isBlank()
          ? new ParsedToolCall(call.id(), parsed.name(), parsed.arguments())
          : parsed
      );
    }
    return result;
  }

  /** The {@code tools} variable handed to extraction templates: name + parameters schema. */
  private static List<Map<String, Object>> toolsData(Map<String, JsonNode> toolParameterSchemas) {
    if (toolParameterSchemas == null || toolParameterSchemas.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> tools = new ArrayList<>(toolParameterSchemas.size());
    for (var entry : toolParameterSchemas.entrySet()) {
      tools.add(Map.of("name", entry.getKey()));
    }
    return tools;
  }

  /**
   * Builds the function-name → {@code parameters} JSON-schema map from a request's {@code tools}
   * array. Accepts both the Chat Completions shape ({@code {type, function: {name, parameters}}})
   * and the Responses shape ({@code {type, name, parameters}}). Returns an empty map when there are
   * no usable tools (→ no coercion).
   */
  public static Map<String, JsonNode> toolParameterSchemas(JsonNode tools) {
    if (tools == null || !tools.isArray() || tools.isEmpty()) {
      return Map.of();
    }
    Map<String, JsonNode> schemas = new HashMap<>();
    for (JsonNode tool : tools) {
      JsonNode fn = tool.has("function") && tool.get("function").isObject()
        ? tool.get("function")
        : tool;
      String name = fn.path("name").asText("");
      JsonNode parameters = fn.get("parameters");
      if (!name.isEmpty() && parameters != null && parameters.isObject()) {
        schemas.put(name, parameters);
      }
    }
    return Map.copyOf(schemas);
  }

  /**
   * Writes an XML-parsed (string) parameter value into {@code arguments}, coerced to the declared
   * schema type when one exists and the value parses cleanly; otherwise the string is kept as-is
   * (fail-open). String / unknown / missing types keep the exact legacy string behavior.
   */
  private static void putCoercedArgument(
    ObjectNode arguments,
    String key,
    String value,
    JsonNode parametersSchema
  ) {
    String type = parametersSchema == null
      ? ""
      : parametersSchema.path("properties").path(key).path("type").asText("");
    switch (type) {
      case "integer" -> {
        try {
          arguments.put(key, Long.parseLong(value));
          return;
        } catch (NumberFormatException ignored) {}
      }
      case "number" -> {
        try {
          arguments.put(key, Double.parseDouble(value));
          return;
        } catch (NumberFormatException ignored) {}
      }
      case "boolean" -> {
        if ("true".equals(value)) {
          arguments.put(key, true);
          return;
        }
        if ("false".equals(value)) {
          arguments.put(key, false);
          return;
        }
      }
      case "array", "object" -> {
        try {
          JsonNode parsed = OBJECT_MAPPER.get().readTree(value);
          if (
            ("array".equals(type) && parsed.isArray()) ||
            ("object".equals(type) && parsed.isObject())
          ) {
            arguments.set(key, parsed);
            return;
          }
        } catch (Exception ignored) {}
      }
      default -> {}
    }
    arguments.put(key, value);
  }

  private static String generateToolCallId() {
    return (
      "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, TOOL_CALL_ID_LENGTH)
    );
  }

  /** Index of the first complete tool-markup opener in {@code text}, or -1 if none. */
  static int indexOfToolMarkupOpener(CharSequence text) {
    String s = text.toString();
    int best = -1;
    for (String opener : TOOL_MARKUP_OPENERS) {
      int idx = s.indexOf(opener);
      if (idx >= 0 && (best < 0 || idx < best)) {
        best = idx;
      }
    }
    return best;
  }

  /**
   * Length of the longest suffix of {@code text} that is a proper prefix of a tool-markup opener —
   * the number of trailing chars that must be withheld from streaming.
   */
  static int toolMarkupHoldbackLength(CharSequence text) {
    String s = text.toString();
    int maxOpener = 0;
    for (String opener : TOOL_MARKUP_OPENERS) {
      maxOpener = Math.max(maxOpener, opener.length());
    }
    // A suffix equal to a full opener would already have matched as an opener.
    int maxHoldback = maxOpener - 1;
    int limit = Math.min(s.length(), maxHoldback);
    for (int k = limit; k > 0; k--) {
      String suffix = s.substring(s.length() - k);
      for (String opener : TOOL_MARKUP_OPENERS) {
        if (opener.startsWith(suffix)) {
          return k;
        }
      }
    }
    return 0;
  }
}
