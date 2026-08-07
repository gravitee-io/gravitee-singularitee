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
package io.gravitee.singularitee.engine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.jinja4j.Environment;
import io.gravitee.jinja4j.Template;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts structured tool calls from generated text by rendering a Jinja extraction template.
 *
 * <p>This is the single, template-driven replacement for the hand-coded dialect parsers that used
 * to live in the HTTP layer. An extraction template receives two variables:
 *
 * <ul>
 *   <li>{@code output} — the raw text of the tool span(s): the bare TOOL-channel payload when the
 *       engine stamps channels, or the full generated content on the legacy tagged-text path;</li>
 *   <li>{@code tools} — the request's tool list as data (name/description entries), for optional
 *       name validation inside a template.</li>
 * </ul>
 *
 * <p>The template must render a JSON array of {@code {"name": ..., "arguments": {...}}} objects.
 * An optional per-call {@code "coerce"} member — {@code true} or a list of argument names — flags
 * string-valued arguments recovered from untyped dialect text (XML / Gemma flavors) so the client
 * holding the request's tool schemas can coerce them to their declared JSON types.
 *
 * <p>Built-in dialect templates ship as resources under {@code tool-extraction/} and reproduce the
 * legacy parsers: {@code chatml-json} (Qwen3 JSON), {@code xml-function} (Qwen3.5 XML) and
 * {@code gemma-call} (Gemma {@code call:name{...}}). When no template is configured they are tried
 * in that order until one yields calls. {@code glm-name-json} (GLM-4) and {@code harmony}
 * (gpt-oss) are resolvable by explicit name only. Render or parse failures always fail open (empty list) —
 * the caller surfaces the raw text as plain content.
 */
public final class ToolCallExtractor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallExtractor.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Built-in dialect template names, in the default trial order. */
  public static final List<String> BUILTIN_ORDER = List.of(
    "chatml-json",
    "xml-function",
    "gemma-call"
  );

  /**
   * All built-in template names resolvable by config. Dialects whose span carries no
   * self-identifying wrapper — GLM-4's {@code name\n{json}}, Harmony's {@code name ...
   * <|message|>{json}} — are resolvable by explicit name only: they lean on the tool-name check
   * alone, so they are never part of the speculative {@link #BUILTIN_ORDER} trial.
   */
  public static final java.util.Set<String> BUILTIN_NAMES;

  static {
    var names = new java.util.HashSet<>(BUILTIN_ORDER);
    names.add("glm-name-json");
    names.add("harmony");
    BUILTIN_NAMES = java.util.Set.copyOf(names);
  }

  private static final Environment ENV = Jinja4jChatTemplateRenderer.defaultEnvironment();
  private static final ConcurrentHashMap<String, Template> TEMPLATE_CACHE =
    new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String> BUILTIN_SOURCES =
    new ConcurrentHashMap<>();

  private ToolCallExtractor() {}

  /**
   * A tool call extracted from generated text. {@code argumentsJson} is a JSON object string;
   * {@code coercibleArgs} lists the argument names whose string values came from untyped dialect
   * text and may be coerced via the request's tool schemas.
   */
  public record ExtractedToolCall(String name, String argumentsJson, List<String> coercibleArgs) {}

  /**
   * Extracts tool calls from {@code output} using the given template configuration: {@code null} /
   * blank tries the built-in dialect templates in order; a built-in name uses that template; any
   * other value is treated as inline Jinja source. Returns an empty list when nothing extracts
   * (fail-open).
   */
  public static List<ExtractedToolCall> extract(
    String output,
    List<Map<String, Object>> tools,
    String templateConfig
  ) {
    if (output == null || output.isBlank()) {
      return List.of();
    }
    if (templateConfig == null || templateConfig.isBlank()) {
      for (String builtin : BUILTIN_ORDER) {
        List<ExtractedToolCall> calls = extractWithSource(output, tools, builtinSource(builtin));
        if (!calls.isEmpty()) {
          return calls;
        }
      }
      return List.of();
    }
    String source = BUILTIN_NAMES.contains(templateConfig.trim())
      ? builtinSource(templateConfig.trim())
      : templateConfig;
    return extractWithSource(output, tools, source);
  }

  private static List<ExtractedToolCall> extractWithSource(
    String output,
    List<Map<String, Object>> tools,
    String source
  ) {
    if (source == null) {
      return List.of();
    }
    try {
      Template template = TEMPLATE_CACHE.computeIfAbsent(source, s ->
        ENV.fromString(s, "<tool-extraction>")
      );
      String rendered = template.render(
        Map.of("output", output, "tools", tools == null ? List.of() : tools)
      );
      return parseRendered(rendered);
    } catch (Exception e) {
      LOGGER.debug("[singularitee] Tool-call extraction template failed (fail-open)", e);
      return List.of();
    }
  }

  /** Parses the rendered JSON array into extracted calls; skips nameless entries. */
  private static List<ExtractedToolCall> parseRendered(String rendered) throws IOException {
    JsonNode node = MAPPER.readTree(rendered.trim());
    if (!node.isArray()) {
      return List.of();
    }
    List<ExtractedToolCall> result = new ArrayList<>();
    for (JsonNode call : node) {
      if (call == null || !call.isObject() || !call.has("name")) {
        continue;
      }
      String name = call.get("name").asText();
      if (name.isEmpty()) {
        continue;
      }
      String arguments = "{}";
      JsonNode argsNode = call.get("arguments");
      if (argsNode != null) {
        arguments = argsNode.isTextual() ? argsNode.asText() : argsNode.toString();
      }
      List<String> coercible = List.of();
      JsonNode coerce = call.get("coerce");
      if (coerce != null && coerce.isBoolean() && coerce.asBoolean()) {
        if (argsNode != null && argsNode.isObject()) {
          List<String> names = new ArrayList<>();
          argsNode.fieldNames().forEachRemaining(names::add);
          coercible = List.copyOf(names);
        }
      } else if (coerce != null && coerce.isArray()) {
        List<String> names = new ArrayList<>();
        for (JsonNode n : coerce) {
          names.add(n.asText());
        }
        coercible = List.copyOf(names);
      }
      result.add(new ExtractedToolCall(name, arguments, coercible));
    }
    return result;
  }

  /** Loads (and caches) a built-in template's source from the module resources. */
  private static String builtinSource(String name) {
    return BUILTIN_SOURCES.computeIfAbsent(name, n -> {
      String path = "tool-extraction/" + n + ".jinja";
      try (InputStream in = ToolCallExtractor.class.getClassLoader().getResourceAsStream(path)) {
        if (in == null) {
          throw new IllegalStateException("Missing built-in tool-extraction template: " + path);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load built-in template: " + path, e);
      }
    });
  }
}
