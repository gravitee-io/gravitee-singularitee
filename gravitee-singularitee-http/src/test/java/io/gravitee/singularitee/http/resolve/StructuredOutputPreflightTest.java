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
package io.gravitee.singularitee.http.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.http.translation.EndpointType;
import io.gravitee.singularitee.http.translation.ResponseFormatParser.InvalidResponseFormatException;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import io.gravitee.singularitee.protocol.StepRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pre-flight in {@link ModelOrPipelineResolver}: a format the target cannot honour is refused
 * while resolving, so nothing is queued and the caller gets a request error naming the field.
 */
class StructuredOutputPreflightTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SCHEMA_REQUEST = """
    {"model":"agent","messages":[{"role":"user","content":"hi"}],
     "response_format":{"type":"json_schema","json_schema":{"name":"p",
       "schema":{"type":"object","properties":{"a":{"type":"string"}}}}}}""";

  /** A text-gen engine that either accepts every format or refuses with the given reason. */
  private static TextGenEngine engine(String refusal) {
    TextGenEngine engine = mock(TextGenEngine.class);
    if (refusal != null) {
      org.mockito.Mockito.doThrow(new UnsupportedStructuredOutputException(refusal))
        .when(engine)
        .checkStructuredOutput(org.mockito.ArgumentMatchers.any(StructuredOutput.class));
    }
    return engine;
  }

  private record InferConfig(String modelId) implements ModelBoundConfig {}

  private static ModelOrPipelineResolver resolver(List<TextGenEngine> outputEngines) {
    ModelRegistry models = new ModelRegistry();
    var steps = new java.util.ArrayList<StepModel>();
    for (int i = 0; i < outputEngines.size(); i++) {
      String modelId = "m" + i;
      models.register(modelId, modelId, outputEngines.get(i), token -> {});
      steps.add(
        new StepModel("s" + i, "infer", StepRole.STEP_ROLE_OUTPUT, new InferConfig(modelId))
      );
    }
    if (steps.isEmpty()) {
      // A pipeline whose only output step produces labels: nothing can carry the constraint.
      models.register("clf", "clf", mock(ClassifierEngine.class), token -> {});
      steps.add(new StepModel("s0", "classify", StepRole.STEP_ROLE_OUTPUT, new InferConfig("clf")));
    }
    PipelineRegistry pipelines = new PipelineRegistry(models);
    pipelines.register(
      new PipelineModel(
        "agent",
        "agent",
        steps.getFirst().id(),
        steps,
        Map.of(),
        "",
        false,
        List.of()
      )
    );
    return new ModelOrPipelineResolver(models, pipelines);
  }

  private static void resolve(ModelOrPipelineResolver resolver, String payload) throws Exception {
    resolver.resolve("agent", MAPPER.readTree(payload), EndpointType.CHAT);
  }

  @Test
  void acceptsAFormatEveryOutputEngineCanEnforce() {
    var resolver = resolver(List.of(engine(null), engine(null)));

    assertThatCode(() -> resolve(resolver, SCHEMA_REQUEST)).doesNotThrowAnyException();
  }

  @Test
  void refusesWhenAnyOutputEngineCannotEnforceTheFormat() {
    // The second step is the one that cannot: checking only the first would queue the request.
    var resolver = resolver(List.of(engine(null), engine("keyword `pattern` cannot be enforced")));

    assertThatThrownBy(() -> resolve(resolver, SCHEMA_REQUEST))
      .isInstanceOf(InvalidResponseFormatException.class)
      .hasMessageContaining("`pattern` cannot be enforced")
      .extracting(e -> ((InvalidResponseFormatException) e).param())
      .isEqualTo("response_format");
  }

  @Test
  void refusesAPipelineWithNoTextGenerationOutputStep() {
    var resolver = resolver(List.of());

    assertThatThrownBy(() -> resolve(resolver, SCHEMA_REQUEST))
      .isInstanceOf(InvalidResponseFormatException.class)
      .hasMessageContaining("no text-generation output step");
  }

  @Test
  void refusesAFormatCombinedWithTools() {
    var resolver = resolver(List.of(engine(null)));
    String withTools = SCHEMA_REQUEST.replaceFirst(
      "\\{\"model\":\"agent\",",
      "{\"model\":\"agent\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}],"
    );

    assertThatThrownBy(() -> resolve(resolver, withTools))
      .isInstanceOf(InvalidResponseFormatException.class)
      .hasMessageContaining("cannot be combined with `tools`");
  }

  @Test
  void namesTheResponsesFieldWhenTheRequestCameFromThatEndpoint() throws Exception {
    var resolver = resolver(List.of(engine("nope")));
    String responses = """
      {"model":"agent","input":"hi","text":{"format":{"type":"json_schema","name":"p",
        "schema":{"type":"object","properties":{"a":{"type":"string"}}}}}}""";

    assertThat(
      org.assertj.core.api.Assertions.catchThrowableOfType(
        () -> resolver.resolve("agent", MAPPER.readTree(responses), EndpointType.RESPONSES),
        InvalidResponseFormatException.class
      ).param()
    ).isEqualTo("text.format");
  }
}
