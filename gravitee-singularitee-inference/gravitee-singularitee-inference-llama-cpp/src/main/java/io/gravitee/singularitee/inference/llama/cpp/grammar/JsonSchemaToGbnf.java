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
package io.gravitee.singularitee.inference.llama.cpp.grammar;

import static io.gravitee.singularitee.inference.llama.cpp.grammar.JsonGbnfRules.SPACE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compiles a JSON Schema into a GBNF grammar whose every derivation validates against the schema.
 *
 * <p>Supported: {@code type} (single or list), {@code properties} / {@code required},
 * {@code items} with {@code minItems} / {@code maxItems}, {@code minLength} / {@code maxLength},
 * {@code enum}, {@code const}, {@code anyOf}, {@code oneOf}, single-entry {@code allOf} and local
 * {@code $ref}, recursion included. Objects are emitted closed (declared properties only, in
 * declared order, required first), which also satisfies a schema that allows extra properties.
 * A keyword that restricts values and is not listed here is rejected rather than ignored, so
 * the grammar never admits an invalid document.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class JsonSchemaToGbnf {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RULES = 4096;

  /** Keywords that describe a schema without restricting the values it accepts. */
  private static final Set<String> ANNOTATIONS = Set.of(
    "$schema",
    "$id",
    "$defs",
    "$comment",
    "definitions",
    "title",
    "description",
    "default",
    "examples",
    "deprecated",
    "readOnly",
    "writeOnly",
    "format",
    "additionalProperties"
  );

  private static final Set<String> SUPPORTED = Set.of(
    "type",
    "properties",
    "required",
    "items",
    "minItems",
    "maxItems",
    "minLength",
    "maxLength",
    "enum",
    "const",
    "anyOf",
    "oneOf",
    "allOf",
    "$ref"
  );

  private final JsonNode document;
  private final Map<String, String> rules = new LinkedHashMap<>();
  private final Map<String, String> refRules = new HashMap<>();
  /** Rules whose body starts with other rules: the only place left recursion can arise. */
  private final Map<String, List<String>> leading = new HashMap<>();

  private JsonSchemaToGbnf(JsonNode document) {
    this.document = document;
  }

  static Gbnf compile(String schema) {
    JsonNode document;
    try {
      document = MAPPER.readTree(schema);
    } catch (JsonProcessingException e) {
      throw new UnsupportedStructuredOutputException(
        "json_schema is not valid JSON: " + e.getOriginalMessage()
      );
    }
    var compiler = new JsonSchemaToGbnf(document);
    String top = compiler.visit(document, "schema");
    compiler.rules.put(StructuredOutput.DEFAULT_ROOT, top);
    compiler.rejectLeftRecursion();
    String text = compiler.rules
      .entrySet()
      .stream()
      .map(e -> e.getKey() + " ::= " + e.getValue())
      .collect(Collectors.joining("\n", "", "\n"));
    return new Gbnf(text, StructuredOutput.DEFAULT_ROOT);
  }

  /** Returns the name of the rule matching {@code schema}. */
  private String visit(JsonNode schema, String hint) {
    if (schema.isBoolean()) {
      if (schema.asBoolean()) {
        return primitive(JsonGbnfRules.VALUE);
      }
      throw unsupported("a `false` schema accepts nothing");
    }
    if (!schema.isObject()) {
      throw unsupported("a schema must be an object, got " + schema.getNodeType());
    }
    schema
      .fieldNames()
      .forEachRemaining(keyword -> {
        if (!SUPPORTED.contains(keyword) && !ANNOTATIONS.contains(keyword)) {
          throw unsupported("keyword `" + keyword + "` cannot be enforced");
        }
      });

    if (schema.has("$ref")) {
      return reference(schema.get("$ref").asText());
    }
    if (schema.has("const")) {
      return define(hint, json(schema.get("const")));
    }
    if (schema.has("enum")) {
      return define(hint, alternatives(schema.get("enum"), this::json));
    }
    for (String combinator : List.of("anyOf", "oneOf")) {
      if (schema.has(combinator)) {
        var names = new ArrayList<String>();
        for (JsonNode option : schema.get(combinator)) {
          names.add(visit(option, hint + "-" + names.size()));
        }
        return alternation(hint, names);
      }
    }
    if (schema.has("allOf")) {
      JsonNode all = schema.get("allOf");
      if (all.size() != 1) {
        throw unsupported("`allOf` with more than one entry cannot be enforced");
      }
      return visit(all.get(0), hint);
    }

    JsonNode type = schema.get("type");
    if (type != null && type.isArray()) {
      var names = new LinkedHashSet<String>();
      for (JsonNode t : type) {
        names.add(typed(t.asText(), schema, hint + "-" + t.asText()));
      }
      return alternation(hint, List.copyOf(names));
    }
    String typeName = type != null ? type.asText() : inferType(schema);
    return typeName == null ? primitive(JsonGbnfRules.VALUE) : typed(typeName, schema, hint);
  }

  private static String inferType(JsonNode schema) {
    if (schema.has("properties") || schema.has("required")) {
      return "object";
    }
    if (schema.has("items") || schema.has("minItems") || schema.has("maxItems")) {
      return "array";
    }
    if (schema.has("minLength") || schema.has("maxLength")) {
      return "string";
    }
    return null;
  }

  private String typed(String type, JsonNode schema, String hint) {
    return switch (type) {
      case "object" -> object(schema, hint);
      case "array" -> array(schema, hint);
      case "string" -> string(schema, hint);
      case "number" -> primitive(JsonGbnfRules.NUMBER);
      case "integer" -> primitive(JsonGbnfRules.INTEGER);
      case "boolean" -> primitive(JsonGbnfRules.BOOLEAN);
      case "null" -> primitive(JsonGbnfRules.NULL);
      default -> throw unsupported("unknown type `" + type + "`");
    };
  }

  private String object(JsonNode schema, String hint) {
    JsonNode properties = schema.get("properties");
    if (properties == null || properties.isEmpty()) {
      if (schema.has("required") && !schema.get("required").isEmpty()) {
        throw unsupported("`required` names properties that `properties` does not declare");
      }
      return primitive(JsonGbnfRules.OBJECT);
    }
    var requiredNames = new LinkedHashSet<String>();
    if (schema.has("required")) {
      schema.get("required").forEach(n -> requiredNames.add(n.asText()));
    }
    String space = primitive(SPACE);
    var required = new ArrayList<String>();
    var optional = new ArrayList<String>();
    for (var entry : properties.properties()) {
      String key = entry.getKey();
      String valueRule = visit(entry.getValue(), hint + "-" + key);
      String pair = define(
        hint + "-" + key + "-kv",
        json(MAPPER.getNodeFactory().textNode(key)) +
          " " +
          space +
          " \":\" " +
          space +
          " " +
          valueRule
      );
      (requiredNames.remove(key) ? required : optional).add(pair);
    }
    if (!requiredNames.isEmpty()) {
      throw unsupported("`required` names undeclared properties " + requiredNames);
    }

    String separator = " " + space + " \",\" " + space + " ";
    var body = new StringBuilder("\"{\" ").append(space);
    if (!required.isEmpty()) {
      body.append(' ').append(String.join(separator, required));
    }
    if (!optional.isEmpty()) {
      String rest = optionalTail(optional, hint, separator);
      body.append(
        required.isEmpty() ? " (" + rest + ")?" : " (" + separator.strip() + " " + rest + ")?"
      );
    }
    return define(hint, body.append(' ').append(space).append(" \"}\"").toString());
  }

  /** A rule deriving every non-empty, order-preserving subset of {@code pairs}. */
  private String optionalTail(List<String> pairs, String hint, String separator) {
    String next = null;
    for (int i = pairs.size() - 1; i >= 0; i--) {
      String pair = pairs.get(i);
      String body = next == null
        ? pair
        : pair + " (" + separator.strip() + " " + next + ")? | " + next;
      next = define(hint + "-rest-" + i, body);
    }
    return next;
  }

  private String array(JsonNode schema, String hint) {
    int min = bound(schema, "minItems", 0);
    int max = bound(schema, "maxItems", -1);
    if (max >= 0 && max < min) {
      throw unsupported("`maxItems` is below `minItems`");
    }
    JsonNode items = schema.get("items");
    if (items != null && items.isArray()) {
      throw unsupported("tuple `items` cannot be enforced");
    }
    if (items == null && min == 0 && max < 0) {
      return primitive(JsonGbnfRules.ARRAY);
    }
    String item = items == null ? primitive(JsonGbnfRules.VALUE) : visit(items, hint + "-item");
    String space = primitive(SPACE);
    String open = "\"[\" " + space + " ";
    String close = " " + space + " \"]\"";
    if (max == 0) {
      return define(hint, open.strip() + " \"]\"");
    }
    String more =
      "(" +
      space +
      " \",\" " +
      space +
      " " +
      item +
      ")" +
      repeat(Math.max(min - 1, 0), max < 0 ? -1 : max - 1);
    String elements = item + " " + more;
    return define(hint, open + (min == 0 ? "(" + elements + ")?" : elements) + close);
  }

  private String string(JsonNode schema, String hint) {
    int min = bound(schema, "minLength", 0);
    int max = bound(schema, "maxLength", -1);
    if (min == 0 && max < 0) {
      return primitive(JsonGbnfRules.STRING);
    }
    if (max >= 0 && max < min) {
      throw unsupported("`maxLength` is below `minLength`");
    }
    String chars = primitive(JsonGbnfRules.CHAR) + repeat(min, max);
    return define(hint, "\"\\\"\" " + chars + " \"\\\"\"");
  }

  private static String repeat(int min, int max) {
    if (max < 0) {
      return min == 0 ? "*" : "{" + min + ",}";
    }
    return "{" + min + "," + max + "}";
  }

  private static int bound(JsonNode schema, String keyword, int fallback) {
    JsonNode node = schema.get(keyword);
    if (node == null) {
      return fallback;
    }
    if (!node.isIntegralNumber() || node.asInt() < 0) {
      throw unsupported("`" + keyword + "` must be a non-negative integer");
    }
    return node.asInt();
  }

  private String reference(String ref) {
    String known = refRules.get(ref);
    if (known != null) {
      return known;
    }
    if (!ref.startsWith("#")) {
      throw unsupported("only local `$ref` is supported, got `" + ref + "`");
    }
    JsonNode target = ref.length() == 1 ? document : document.at(ref.substring(1));
    if (target.isMissingNode()) {
      throw unsupported("`$ref` target `" + ref + "` does not exist");
    }
    // Reserved before the visit so a recursive schema resolves to this rule.
    String name = reserve("ref-" + ref.substring(ref.lastIndexOf('/') + 1));
    refRules.put(ref, name);
    String body = visit(target, name + "-def");
    rules.put(name, body);
    leading.put(name, List.of(body));
    return name;
  }

  private String alternation(String hint, List<String> names) {
    String name = define(hint, String.join(" | ", names));
    leading.put(name, names);
    return name;
  }

  /** llama.cpp refuses a left-recursive grammar, e.g. from a schema that references itself. */
  private void rejectLeftRecursion() {
    for (String start : leading.keySet()) {
      var seen = new LinkedHashSet<String>();
      var pending = new ArrayList<>(leading.get(start));
      while (!pending.isEmpty()) {
        String current = pending.removeLast();
        if (current.equals(start)) {
          throw unsupported("schema is circular without consuming input (`" + start + "`)");
        }
        if (seen.add(current)) {
          pending.addAll(leading.getOrDefault(current, List.of()));
        }
      }
    }
  }

  private String alternatives(JsonNode values, Function<JsonNode, String> render) {
    if (!values.isArray() || values.isEmpty()) {
      throw unsupported("`enum` must be a non-empty array");
    }
    var parts = new ArrayList<String>();
    values.forEach(v -> parts.add(render.apply(v)));
    return "(" + String.join(" | ", parts) + ")";
  }

  private String json(JsonNode value) {
    try {
      return Gbnf.literal(MAPPER.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw unsupported("cannot serialize literal: " + e.getOriginalMessage());
    }
  }

  private String primitive(String name) {
    JsonGbnfRules.include(name, rules);
    return name;
  }

  private String define(String hint, String body) {
    String name = reserve(hint);
    rules.put(name, body);
    return name;
  }

  private String reserve(String hint) {
    if (rules.size() >= MAX_RULES) {
      throw unsupported("schema is too large (more than " + MAX_RULES + " grammar rules)");
    }
    String base = hint.replaceAll("[^a-zA-Z0-9-]+", "-").replaceAll("^-+|-+$", "");
    if (base.isEmpty()) {
      base = "rule";
    }
    String name = base;
    for (
      int i = 1;
      rules.containsKey(name) ||
      JsonGbnfRules.isReserved(name) ||
      name.equals(StructuredOutput.DEFAULT_ROOT);
      i++
    ) {
      name = base + "-" + i;
    }
    // Placeholder so nested definitions cannot take the name before the body is known.
    rules.put(name, "");
    return name;
  }

  private static UnsupportedStructuredOutputException unsupported(String reason) {
    return new UnsupportedStructuredOutputException("json_schema: " + reason);
  }
}
