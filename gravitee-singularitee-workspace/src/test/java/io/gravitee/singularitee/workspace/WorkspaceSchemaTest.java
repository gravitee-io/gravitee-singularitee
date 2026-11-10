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
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the published {@code workspace.schema.json} honest: it must match the generator, and
 * every shipped example must validate against it.
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

  @Test
  void committed_schema_matches_the_generator() throws IOException {
    assertThat(committed())
      .as(
        "workspace.schema.json is stale: run WorkspaceSchema with src/main/resources/workspace.schema.json as its argument (see its javadoc)"
      )
      .isEqualTo(WorkspaceSchema.generate());
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
