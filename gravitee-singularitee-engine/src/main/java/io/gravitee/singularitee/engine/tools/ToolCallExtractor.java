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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
  public static final Set<String> BUILTIN_NAMES = Stream.concat(
    BUILTIN_ORDER.stream(),
    Stream.of("glm-name-json", "harmony")
  ).collect(Collectors.toUnmodifiableSet());

  private static final Environment ENV = Jinja4jChatTemplateRenderer.defaultEnvironment();

  /**
   * Compiled-template cache, keyed by template source. Sources normally come from workspace
   * config (a small, fixed set), but the cap keeps a caller-supplied inline template from
   * growing the heap without bound — beyond it, templates compile per call, uncached.
   */
  private static final int TEMPLATE_CACHE_MAX = 256;

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
   * Outcome of an extraction attempt. {@code error} is {@code null} unless the template render
   * or JSON parse threw — an empty {@code calls} with a {@code null} error means the output
   * simply contained no recognizable call.
   */
  public record ExtractionResult(List<ExtractedToolCall> calls, String error) {
    static ExtractionResult of(List<ExtractedToolCall> calls) {
      return new ExtractionResult(calls, null);
    }
  }

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
    return extractResult(output, tools, templateConfig).calls();
  }

  /**
   * Like {@link #extract} but surfaces the failure cause, so pipeline steps can expose it as a
   * context field for repair loops instead of discarding it.
   */
  public static ExtractionResult extractResult(
    String output,
    List<Map<String, Object>> tools,
    String templateConfig
  ) {
    if (output == null || output.isBlank()) {
      return ExtractionResult.of(List.of());
    }
    if (templateConfig == null || templateConfig.isBlank()) {
      String lastError = null;
      for (String builtin : BUILTIN_ORDER) {
        ExtractionResult result = extractWithSource(output, tools, builtinSource(builtin));
        if (!result.calls().isEmpty()) {
          return result;
        }
        if (result.error() != null) {
          lastError = result.error();
        }
      }
      return new ExtractionResult(List.of(), lastError);
    }
    String source = BUILTIN_NAMES.contains(templateConfig.trim())
      ? builtinSource(templateConfig.trim())
      : templateConfig;
    return extractWithSource(output, tools, source);
  }

  private static ExtractionResult extractWithSource(
    String output,
    List<Map<String, Object>> tools,
    String source
  ) {
    if (source == null) {
      return ExtractionResult.of(List.of());
    }
    try {
      Template template = TEMPLATE_CACHE.get(source);
      if (template == null) {
        template = ENV.fromString(source, "<tool-extraction>");
        if (TEMPLATE_CACHE.size() < TEMPLATE_CACHE_MAX) {
          TEMPLATE_CACHE.putIfAbsent(source, template);
        }
      }
      String rendered = template.render(
        Map.of("output", output, "tools", tools == null ? List.of() : tools)
      );
      try {
        return ExtractionResult.of(parseRendered(rendered));
      } catch (IOException first) {
        // Lenient second pass: small models routinely truncate the last
        // closing brace(s) of a call. Balancing the SPAN and re-rendering
        // recovers the call instead of forcing a full repair-loop round trip.
        String balancedOutput = balanceJson(output);
        if (balancedOutput.equals(output)) {
          throw first;
        }
        String reRendered = template.render(
          Map.of("output", balancedOutput, "tools", tools == null ? List.of() : tools)
        );
        var calls = parseRendered(reRendered);
        LOGGER.debug(
          "[singularitee] Tool-call JSON balanced ({} closer(s) appended to the span)",
          balancedOutput.length() - output.length()
        );
        return ExtractionResult.of(calls);
      }
    } catch (Exception e) {
      LOGGER.debug("[singularitee] Tool-call extraction template failed (fail-open)", e);
      return new ExtractionResult(List.of(), e.getMessage());
    }
  }

  /**
   * Appends the closers for any unclosed {@code {}/{@code [} (outside string
   * literals) so a truncated call like {@code {"name":"f","arguments":{"a":[1]}
   * } parses. Returns the input unchanged when it is already balanced.
   */
  static String balanceJson(String text) {
    Deque<Character> open = new ArrayDeque<>();
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (c == '{' || c == '[') {
        open.push(c);
      } else if (
        (c == '}' && !open.isEmpty() && open.peek() == '{') ||
        (c == ']' && !open.isEmpty() && open.peek() == '[')
      ) {
        open.pop();
      }
    }
    if (open.isEmpty() && !inString) {
      return text;
    }
    StringBuilder sb = new StringBuilder(text);
    if (inString) {
      sb.append('"');
    }
    while (!open.isEmpty()) {
      sb.append(open.pop() == '{' ? '}' : ']');
    }
    return sb.toString();
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
