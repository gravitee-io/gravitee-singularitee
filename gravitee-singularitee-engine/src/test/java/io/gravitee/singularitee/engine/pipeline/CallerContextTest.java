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
package io.gravitee.singularitee.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.pipeline.model.NestedPipelineConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.OutputFieldConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerContextTest {

  private record Writes(String outputField) implements OutputFieldConfig {}

  private record Runs(String pipelineId, boolean remote) implements NestedPipelineConfig {
    @Override
    public Optional<String> localPipelineId() {
      return remote ? Optional.empty() : Optional.of(pipelineId);
    }
  }

  private static final PipelineModel PIPELINE = new PipelineModel(
    "p",
    "p",
    "reason",
    List.of(
      new StepModel("reason", "infer", null, new Writes("verdict.text")),
      new StepModel("gate", "route", null, null)
    ),
    Map.of(),
    null,
    false,
    List.of()
  );

  @Test
  void keys_under_a_step_id_or_output_root_are_dropped() {
    var request = InferPipelineRequest.newBuilder()
      .setPipelineId("p")
      .putContext("reason.output", "approve")
      .putContext("verdict.text", "approve")
      .putContext("gate", "x")
      .putContext("tenant", "acme")
      .build();

    var sanitized = sanitize(PIPELINE, request);

    assertThat(sanitized.getContextMap()).containsOnlyKeys("tenant");
  }

  @Test
  void engine_owned_keys_are_dropped() {
    var request = InferPipelineRequest.newBuilder()
      .putContext("__guard_triggered", "false")
      .putContext("todos.remaining", "0")
      .putContext("conversation.repeated_call", "0")
      .putContext("prompt", "override")
      .putContext("meta.team", "core")
      .putContext("reasoning_effort", "high")
      .putContext("instructions", "be terse")
      .build();

    var sanitized = sanitize(PIPELINE, request);

    assertThat(sanitized.getContextMap()).containsOnlyKeys(
      "meta.team",
      "reasoning_effort",
      "instructions"
    );
  }

  @Test
  void a_clean_request_is_returned_unchanged() {
    var request = InferPipelineRequest.newBuilder().putContext("tenant", "acme").build();

    assertThat(sanitize(PIPELINE, request)).isSameAs(request);
  }

  @Test
  void a_step_id_only_matches_a_whole_segment() {
    var request = InferPipelineRequest.newBuilder().putContext("reasoning.mode", "x").build();

    assertThat(sanitize(PIPELINE, request).getContextMap()).containsKey("reasoning.mode");
  }

  @Test
  void keys_of_a_local_sub_pipeline_are_dropped_too() {
    var parent = pipeline(
      "parent",
      new StepModel("delegate", "sub_pipeline", null, new Runs("child", false))
    );
    var child = pipeline(
      "child",
      new StepModel("g", "guard", null, null),
      new StepModel("judge", "infer", null, new Writes("verdict.text"))
    );
    var keys = Set.of("g.verdict", "verdict.text", "tenant");

    var dropped = CallerContext.reservedKeys(
      parent,
      id -> id.equals("child") ? Optional.of(child) : Optional.empty(),
      keys
    );

    assertThat(dropped).containsExactly("g.verdict", "verdict.text");
  }

  @Test
  void a_remote_sub_pipeline_is_not_followed() {
    var parent = pipeline(
      "parent",
      new StepModel("delegate", "sub_pipeline", null, new Runs("child", true))
    );
    var child = pipeline("child", new StepModel("g", "guard", null, null));

    var dropped = CallerContext.reservedKeys(parent, id -> Optional.of(child), Set.of("g.verdict"));

    assertThat(dropped).isEmpty();
  }

  @Test
  void pipelines_that_run_each_other_are_walked_once() {
    var a = pipeline("a", new StepModel("to_b", "sub_pipeline", null, new Runs("b", false)));
    var b = pipeline("b", new StepModel("to_a", "sub_pipeline", null, new Runs("a", false)));
    var all = Map.of("a", a, "b", b);

    var dropped = CallerContext.reservedKeys(
      a,
      id -> Optional.ofNullable(all.get(id)),
      Set.of("to_a.output", "to_b.output")
    );

    assertThat(dropped).containsExactly("to_a.output", "to_b.output");
  }

  private static InferPipelineRequest sanitize(
    PipelineModel pipeline,
    InferPipelineRequest request
  ) {
    return CallerContext.without(
      request,
      CallerContext.reservedKeys(pipeline, id -> Optional.empty(), request.getContextMap().keySet())
    );
  }

  private static PipelineModel pipeline(String id, StepModel... steps) {
    return new PipelineModel(
      id,
      id,
      steps[0].id(),
      List.of(steps),
      Map.of(),
      null,
      false,
      List.of()
    );
  }
}
