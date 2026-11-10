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
package io.gravitee.singularitee.workspace;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the JSON Schema (draft 2020-12) of a workspace YAML from {@link WorkspaceDefinition}.
 *
 * <p>The records stay the single source of truth: the committed {@code workspace.schema.json}
 * resource is checked against this generator by a test, so it cannot drift. Regenerate it with
 * {@code WorkspaceSchema <path>} (see {@link #main}).
 *
 * <p>Strictness: a record without a catch-all section gets {@code additionalProperties: false},
 * so editors flag a misspelled key. The node itself ignores unknown keys, so a consumer should
 * report schema errors as warnings unless it wants to be stricter than the node. A step's
 * {@code config} block is an open object: its schema belongs to the plugin providing the step
 * type.
 */
public final class WorkspaceSchema {

  /** Classpath location of the committed schema. */
  public static final String RESOURCE = "/workspace.schema.json";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Keys the loader requires, beyond what the record types say. */
  private static final Map<String, List<String>> REQUIRED = Map.of(
    "WorkspaceDefinition",
    List.of("workspace"),
    "ModelDefinition",
    List.of("type"),
    "StepDefinition",
    List.of("type")
  );

  private final ObjectNode defs = JSON.createObjectNode();

  private WorkspaceSchema() {}

  /** Builds the schema tree. */
  public static ObjectNode generate() {
    var generator = new WorkspaceSchema();
    ObjectNode root = JSON.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.put("$id", "https://gravitee.io/schemas/singularitee/workspace.schema.json");
    root.put("title", "Gravitee Singularitee workspace");
    root.put("$ref", "#/$defs/WorkspaceDefinition");
    generator.define(WorkspaceDefinition.class);
    root.set("$defs", generator.sorted());
    return root;
  }

  /** Writes the schema, pretty-printed, to the path given as the first argument. */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: WorkspaceSchema <output-file>");
    }
    Files.writeString(
      Path.of(args[0]),
      JSON.writerWithDefaultPrettyPrinter().writeValueAsString(generate()) + "\n"
    );
  }

  private ObjectNode sorted() {
    var out = JSON.createObjectNode();
    new TreeMap<String, com.fasterxml.jackson.databind.JsonNode>(toMap(defs)).forEach(out::set);
    return out;
  }

  private static Map<String, com.fasterxml.jackson.databind.JsonNode> toMap(ObjectNode node) {
    var map = new TreeMap<String, com.fasterxml.jackson.databind.JsonNode>();
    node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue()));
    return map;
  }

  private void define(Class<?> record) {
    String name = record.getSimpleName();
    if (defs.has(name)) {
      return;
    }
    defs.set(name, JSON.createObjectNode()); // reserve: breaks cycles
    ObjectNode def = JSON.createObjectNode();
    def.put("type", "object");
    ObjectNode props = def.putObject("properties");
    boolean open = false;
    for (RecordComponent rc : record.getRecordComponents()) {
      Field field = field(record, rc.getName());
      if (field.isAnnotationPresent(JsonAnySetter.class)) {
        open = true;
        continue;
      }
      JsonProperty jp = field.getAnnotation(JsonProperty.class);
      String key = jp != null && !jp.value().isEmpty() ? jp.value() : rc.getName();
      ObjectNode schema = typeSchema(rc.getGenericType());
      if (
        field.isAnnotationPresent(JsonFormat.class) && "array".equals(schema.path("type").asText())
      ) {
        schema = oneOf(schema.get("items").deepCopy(), schema);
      }
      props.set(key, schema);
    }
    if (name.equals("ModelDefinition")) {
      ArrayNode types = JSON.createArrayNode();
      Arrays.stream(ModelType.values()).forEach(t -> types.add(t.yamlKey()));
      ((ObjectNode) props.get("type")).set("enum", types);
    }
    if (!open) {
      def.put("additionalProperties", false);
    }
    List<String> required = REQUIRED.get(name);
    if (required != null) {
      ArrayNode req = def.putArray("required");
      required.forEach(req::add);
    }
    defs.set(name, hasStringCreator(record) ? oneOf(stringType(), def) : def);
  }

  private ObjectNode typeSchema(Type type) {
    if (type instanceof Class<?> c) {
      if (c == String.class) return stringType();
      if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
        return JSON.createObjectNode().put("type", "integer");
      }
      if (c == float.class || c == Float.class || c == double.class || c == Double.class) {
        return JSON.createObjectNode().put("type", "number");
      }
      if (c == boolean.class || c == Boolean.class) {
        return JSON.createObjectNode().put("type", "boolean");
      }
      if (c.isRecord()) {
        define(c);
        return JSON.createObjectNode().put("$ref", "#/$defs/" + c.getSimpleName());
      }
      return JSON.createObjectNode(); // Object: anything
    }
    if (type instanceof ParameterizedType p) {
      Class<?> raw = (Class<?>) p.getRawType();
      Type[] args = p.getActualTypeArguments();
      if (List.class.isAssignableFrom(raw)) {
        ObjectNode array = JSON.createObjectNode().put("type", "array");
        array.set("items", typeSchema(args[0]));
        return array;
      }
      if (Map.class.isAssignableFrom(raw)) {
        ObjectNode map = JSON.createObjectNode().put("type", "object");
        map.set("additionalProperties", typeSchema(args[1]));
        return map;
      }
    }
    return JSON.createObjectNode();
  }

  private static ObjectNode stringType() {
    return JSON.createObjectNode().put("type", "string");
  }

  private static ObjectNode oneOf(ObjectNode... alternatives) {
    ObjectNode node = JSON.createObjectNode();
    ArrayNode any = node.putArray("anyOf");
    Arrays.stream(alternatives).forEach(any::add);
    return node;
  }

  /** A record with a delegating {@code @JsonCreator(String)} may be written as a bare string. */
  private static boolean hasStringCreator(Class<?> record) {
    return Arrays.stream(record.getDeclaredMethods()).anyMatch(m -> {
      JsonCreator c = m.getAnnotation(JsonCreator.class);
      return (
        c != null &&
        c.mode() == JsonCreator.Mode.DELEGATING &&
        m.getParameterCount() == 1 &&
        m.getParameterTypes()[0] == String.class
      );
    });
  }

  private static Field field(Class<?> record, String name) {
    try {
      return record.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }
}
