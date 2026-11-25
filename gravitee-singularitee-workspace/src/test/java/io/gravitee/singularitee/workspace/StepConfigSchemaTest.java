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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the step config schemas the plugins publish: every example step config must satisfy the
 * schema of its type, and every schema must be a valid 2020-12 schema that only uses the
 * Singularitee annotations the control plane understands.
 */
class StepConfigSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
    SpecVersion.VersionFlag.V202012
  );

  private static final Set<String> REFS = Set.of("step", "model", "pipeline", "template", "server");
  private static final Set<String> MODEL_TASKS = Set.of(
    "text-generation",
    "text-classification",
    "token-classification",
    "feature-extraction",
    "reranking",
    "decision"
  );
  private static final Set<String> CONTEXT = Set.of("reads", "writes");
  private static final Set<String> SCHEMA_MAPS = Set.of(
    "properties",
    "patternProperties",
    "$defs",
    "dependentSchemas"
  );

  private static final String SYNTHETIC = """
    {"type":"object","properties":{"x":{"type":"string"}},"additionalProperties":false}
    """;

  static Stream<Path> examples() throws IOException {
    return Stream.concat(
      ExamplesWorkspaceTest.standaloneWorkspaces(),
      ExamplesWorkspaceTest.pipelineFragments()
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  void every_example_step_config_matches_its_schema(Path example) throws IOException {
    var violations = violations(
      example,
      YAML.readTree(Files.readString(example)),
      compile(TestStepPlugins.plugins().configSchemas())
    );

    assertThat(violations).isEmpty();
  }

  @Test
  void every_step_schema_is_a_valid_2020_12_schema_with_known_annotations() throws IOException {
    var problems = new ArrayList<String>();
    for (var entry : new TreeMap<>(TestStepPlugins.plugins().configSchemas()).entrySet()) {
      problems.addAll(problems(entry.getKey(), JSON.readTree(entry.getValue())));
    }

    assertThat(problems).isEmpty();
  }

  @Test
  void every_core_step_type_has_a_schema() {
    assertThat(TestStepPlugins.plugins().configSchemas()).containsKeys(
      StepTypes.CORE.toArray(String[]::new)
    );
  }

  @Test
  void the_harness_reports_file_pipeline_step_and_error_of_a_bad_config() throws IOException {
    var workspace = YAML.readTree(
      """
      workspace:
        pipelines:
          - id: chat
            steps:
              - id: ask
                type: infer
                config:
                  x: fine
              - id: answer
                type: infer
                config:
                  typo: oops
      """
    );

    var violations = violations(
      Path.of("examples/demo.yaml"),
      workspace,
      compile(Map.of("infer", SYNTHETIC))
    );

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0))
      .contains("examples/demo.yaml")
      .contains("pipeline chat")
      .contains("step answer")
      .contains("typo");
  }

  @Test
  void route_loop_and_break_require_the_settings_they_cannot_run_without() throws IOException {
    var workspace = YAML.readTree(
      """
      workspace:
        pipelines:
          - id: p
            steps:
              - id: r
                type: route
                config:
                  model_id: m
                  rules:
                    - label: spam
                    - next_step: done
              - id: l
                type: loop
                config:
                  max_iterations: 2
                  condition: { type: not_empty }
              - id: b
                type: break
                config:
                  condition: { type: empty }
      """
    );

    var violations = violations(
      Path.of("w.yaml"),
      workspace,
      compile(TestStepPlugins.plugins().configSchemas())
    );

    assertThat(violations)
      .anyMatch(v -> v.contains("step r") && v.contains("next_step"))
      .anyMatch(v -> v.contains("step r") && v.contains("label"))
      .anyMatch(v -> v.contains("step l") && v.contains("loopback_step"))
      .anyMatch(v -> v.contains("step l") && v.contains("input_field"))
      .anyMatch(v -> v.contains("step b") && v.contains("input_field"));
  }

  @Test
  void the_harness_leaves_steps_without_a_schema_or_a_config_alone() throws IOException {
    var workspace = YAML.readTree(
      """
      workspace:
        pipelines:
          - id: chat
            steps:
              - id: a
                type: classify
                config:
                  anything: goes
              - id: b
                type: infer
      """
    );

    assertThat(
      violations(Path.of("w.yaml"), workspace, compile(Map.of("infer", SYNTHETIC)))
    ).isEmpty();
  }

  @Test
  void the_schema_check_accepts_known_annotations_and_rejects_unknown_ones() throws IOException {
    String good = """
      {"type":"object","properties":{
        "model_id":{"type":"string","x-singularitee-ref":"model","x-singularitee-model-tasks":["text-generation"]},
        "next":{"type":"string","x-singularitee-ref":"step","x-singularitee-branch":true},
        "retry":{"type":"string","x-singularitee-ref":"step","x-singularitee-branch":"retry"},
        "output_field":{"type":"string","x-singularitee-context":"writes"},
        "x-singularitee-bogus":{"type":"string"}},
       "additionalProperties":false}
      """;

    assertThat(problems("infer", JSON.readTree(good))).isEmpty();
    assertThat(
      problems(
        "infer",
        JSON.readTree(
          "{\"type\":\"object\",\"properties\":{\"a\":{\"x-singularitee-ref\":\"bogus\"}}}"
        )
      )
    ).isNotEmpty();
    assertThat(
      problems(
        "infer",
        JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"x-singularitee-made-up\":1}}}")
      )
    ).isNotEmpty();
    assertThat(
      problems(
        "infer",
        JSON.readTree(
          "{\"type\":\"object\",\"properties\":{\"a\":{\"x-singularitee-model-tasks\":[\"chat\"]}}}"
        )
      )
    ).isNotEmpty();
    assertThat(
      problems(
        "infer",
        JSON.readTree(
          "{\"type\":\"object\",\"properties\":{\"a\":{\"x-singularitee-context\":\"both\"}}}"
        )
      )
    ).isNotEmpty();
    assertThat(
      problems("infer", JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":7}}}"))
    ).isNotEmpty();
    assertThat(problems("infer", JSON.readTree("{\"type\":\"string\"}"))).isNotEmpty();
  }

  private static Map<String, JsonSchema> compile(Map<String, String> schemas) {
    var compiled = new TreeMap<String, JsonSchema>();
    schemas.forEach((type, text) -> {
      try {
        compiled.put(type, FACTORY.getSchema(JSON.readTree(text)));
      } catch (IOException e) {
        throw new IllegalStateException("step schema of " + type, e);
      }
    });
    return compiled;
  }

  private static List<String> violations(
    Path file,
    JsonNode workspace,
    Map<String, JsonSchema> schemas
  ) {
    var out = new ArrayList<String>();
    for (JsonNode pipeline : workspace.path("workspace").path("pipelines")) {
      for (JsonNode step : pipeline.path("steps")) {
        JsonSchema schema = schemas.get(step.path("type").asText());
        JsonNode config = step.get("config");
        if (schema == null || config == null) continue;
        schema
          .validate(config)
          .forEach(error ->
            out.add(
              "%s: pipeline %s, step %s: %s".formatted(
                file,
                pipeline.path("id").asText(),
                step.path("id").asText(),
                error.getMessage()
              )
            )
          );
      }
    }
    return out;
  }

  private static List<String> problems(String type, JsonNode schema) {
    var out = new ArrayList<String>();
    FACTORY.getSchema(SchemaLocation.of(SchemaId.V202012))
      .validate(schema)
      .forEach(e -> out.add(type + ": not a valid 2020-12 schema: " + e.getMessage()));
    if (!"object".equals(schema.path("type").asText())) {
      out.add(type + ": the root of a step config schema must be an object schema");
    }
    checkAnnotations(type, "", schema, out);
    return out;
  }

  private static void checkAnnotations(String type, String path, JsonNode node, List<String> out) {
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) checkAnnotations(
        type,
        path + "/" + i,
        node.get(i),
        out
      );
      return;
    }
    if (!node.isObject()) return;
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      var field = it.next();
      String key = field.getKey();
      JsonNode value = field.getValue();
      String where = type + " " + path + "/" + key;
      if (SCHEMA_MAPS.contains(key) && value.isObject()) {
        for (Iterator<Map.Entry<String, JsonNode>> inner = value.fields(); inner.hasNext(); ) {
          var child = inner.next();
          checkAnnotations(type, path + "/" + key + "/" + child.getKey(), child.getValue(), out);
        }
        continue;
      }
      if (key.startsWith("x-singularitee-")) {
        checkAnnotation(where, key, value, out);
        continue;
      }
      checkAnnotations(type, path + "/" + key, value, out);
    }
  }

  private static void checkAnnotation(String where, String key, JsonNode value, List<String> out) {
    switch (key) {
      case "x-singularitee-ref" -> {
        if (!value.isTextual() || !REFS.contains(value.asText())) {
          out.add(where + ": expected one of " + REFS);
        }
      }
      case "x-singularitee-model-tasks" -> {
        boolean ok = value.isArray() && !value.isEmpty();
        for (JsonNode task : value) ok &= task.isTextual() && MODEL_TASKS.contains(task.asText());
        if (!ok) out.add(where + ": expected a non-empty array of " + MODEL_TASKS);
      }
      case "x-singularitee-branch" -> {
        if (!value.isTextual() && !value.isBoolean()) {
          out.add(where + ": expected an edge label or true");
        }
      }
      case "x-singularitee-context" -> {
        if (!value.isTextual() || !CONTEXT.contains(value.asText())) {
          out.add(where + ": expected one of " + CONTEXT);
        }
      }
      default -> out.add(where + ": unknown annotation");
    }
  }
}
