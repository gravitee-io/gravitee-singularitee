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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the published {@code workspace.schema.json} honest: it must match the generator fed
 * with the step schemas of the real plugins, and every shipped example must validate against it.
 *
 * <p>Regenerate the committed file (main code cannot see the plugins) with
 * {@code mvn -o -pl gravitee-singularitee-workspace test -Dtest=WorkspaceSchemaTest -Dsingularitee.regenerateSchema=true}.
 */
class WorkspaceSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static JsonNode committed() throws IOException {
    try (InputStream in = WorkspaceSchema.class.getResourceAsStream(WorkspaceSchema.RESOURCE)) {
      assertThat(in).as("committed " + WorkspaceSchema.RESOURCE).isNotNull();
      return JSON.readTree(in);
    }
  }

  private static JsonSchema schema() throws IOException {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(committed());
  }

  private static final String REGENERATE = "singularitee.regenerateSchema";

  private static final Path COMMITTED_FILE = Path.of(
    "src",
    "main",
    "resources",
    WorkspaceSchema.RESOURCE.substring(1)
  );

  private static final String INFER_SCHEMA = """
    {"type":"object","properties":{"x":{"type":"string"}},"additionalProperties":false}
    """;

  @Test
  @DisabledIfSystemProperty(named = REGENERATE, matches = "true")
  void committed_schema_matches_the_generator() throws IOException {
    assertThat(committed())
      .as(
        "workspace.schema.json is stale: regenerate it with -D%s=true (see the class javadoc)",
        REGENERATE
      )
      .isEqualTo(WorkspaceSchema.generate(TestStepPlugins.plugins().configSchemas()));
  }

  @Test
  @EnabledIfSystemProperty(named = REGENERATE, matches = "true")
  void regenerate_the_committed_schema() throws IOException {
    Files.writeString(
      COMMITTED_FILE,
      WorkspaceSchema.toJson(WorkspaceSchema.generate(TestStepPlugins.plugins().configSchemas()))
    );
  }

  @Test
  void without_step_schemas_the_config_stays_open() throws IOException {
    assertThat(WorkspaceSchema.generate(Map.of())).isEqualTo(WorkspaceSchema.generate());
    assertThat(WorkspaceSchema.generate().toString()).doesNotContain("step.");
  }

  @Test
  void a_step_schema_constrains_the_config_of_its_own_type_only() throws IOException {
    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
      WorkspaceSchema.generate(Map.of("infer", INFER_SCHEMA))
    );

    assertThat(schema.validate(workspaceWithStep("infer", "x: hello"))).isEmpty();
    assertThat(schema.validate(workspaceWithStep("infer", "y: hello"))).isNotEmpty();
    assertThat(schema.validate(workspaceWithStep("infer", "x: 1"))).isNotEmpty();
    assertThat(schema.validate(workspaceWithStep("classify", "y: hello"))).isEmpty();
  }

  @Test
  void a_step_without_a_config_block_still_validates() throws IOException {
    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
      WorkspaceSchema.generate(Map.of("infer", INFER_SCHEMA))
    );
    JsonNode bare = YAML.readTree(
      "workspace:\n  pipelines:\n    - id: p\n      entry: s\n      steps:\n        - id: s\n          type: infer\n"
    );

    assertThat(schema.validate(bare)).isEmpty();
  }

  @Test
  void an_embedded_step_schema_keeps_its_local_references_working() throws IOException {
    String withDefs = """
      {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://example.test/infer.json",
        "type": "object",
        "properties": {"x": {"$ref": "#/$defs/name"}},
        "$defs": {"name": {"type": "string", "minLength": 2}},
        "additionalProperties": false
      }
      """;
    var generated = WorkspaceSchema.generate(Map.of("infer", withDefs));
    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
      generated
    );

    assertThat(generated.at("/$defs/step.infer/$schema").isMissingNode()).isTrue();
    assertThat(generated.at("/$defs/step.infer/$id").isMissingNode()).isTrue();
    assertThat(schema.validate(workspaceWithStep("infer", "x: ab"))).isEmpty();
    assertThat(schema.validate(workspaceWithStep("infer", "x: a"))).isNotEmpty();
  }

  @Test
  void a_malformed_step_schema_names_its_type() {
    assertThatThrownBy(() -> WorkspaceSchema.generate(Map.of("infer", "{not json")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("infer");
  }

  private static JsonNode workspaceWithStep(String type, String configYaml) throws IOException {
    return YAML.readTree(
      "workspace:\n  pipelines:\n    - id: p\n      entry: s\n      steps:\n        - id: s\n          type: " +
        type +
        "\n          config:\n            " +
        configYaml +
        "\n"
    );
  }

  @Test
  void rejects_a_misspelled_key_and_an_unknown_model_type() throws IOException {
    var schema = schema();
    JsonNode typo = YAML.readTree(
      "workspace:\n  models:\n    - id: llm\n      type: llama_cpp\n      nmae: a/b\n"
    );
    JsonNode badType = YAML.readTree(
      "workspace:\n  models:\n    - id: llm\n      type: llama_cpps\n"
    );

    assertThat(schema.validate(typo)).isNotEmpty();
    assertThat(schema.validate(badType)).isNotEmpty();
  }

  @Test
  void accepts_a_minimal_workspace() throws IOException {
    var schema = schema();
    JsonNode minimal = YAML.readTree(
      "workspace:\n  models:\n    - id: llm\n      name: a/b\n      type: llama_cpp\n"
    );

    assertThat(schema.validate(minimal)).isEmpty();
  }

  static Stream<Path> examples() throws IOException {
    return ExamplesWorkspaceTest.standaloneWorkspaces();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  void every_example_validates(Path example) throws IOException {
    var errors = schema().validate(YAML.readTree(Files.readString(example)));

    assertThat(errors).as(example.toString()).isEmpty();
  }
}
