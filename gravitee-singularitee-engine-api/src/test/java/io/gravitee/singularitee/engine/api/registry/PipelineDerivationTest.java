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
package io.gravitee.singularitee.engine.api.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.Modalities;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a pipeline advertises (its task and its input modalities) is either declared
 * by the workspace or derived: the task from the model behind its output step, the
 * modalities from every model the DAG feeds.
 */
class PipelineDerivationTest {

  @Test
  void derives_the_task_from_the_model_behind_the_output_step() {
    var registry = registryWith("pii", ModelTasks.TOKEN_CLASSIFICATION);

    registry.register(
      pipeline("redact", null, null, classifyStep("scan", "pii", StepRole.STEP_ROLE_OUTPUT))
    );

    assertThat(taskOf(registry, "redact")).isEqualTo(ModelTasks.TOKEN_CLASSIFICATION);
  }

  @Test
  void falls_back_to_the_entry_step_when_no_step_claims_the_output_role() {
    var registry = registryWith("topics", ModelTasks.TEXT_CLASSIFICATION);

    registry.register(
      pipeline(
        "triage",
        null,
        null,
        classifyStep("classify", "topics", StepRole.STEP_ROLE_UNSPECIFIED)
      )
    );

    assertThat(taskOf(registry, "triage")).isEqualTo(ModelTasks.TEXT_CLASSIFICATION);
  }

  @Test
  void keeps_a_declared_task_instead_of_deriving_one() {
    var registry = registryWith("pii", ModelTasks.TOKEN_CLASSIFICATION);

    registry.register(
      pipeline(
        "redact",
        ModelTasks.TEXT_GENERATION,
        null,
        classifyStep("scan", "pii", StepRole.STEP_ROLE_OUTPUT)
      )
    );

    assertThat(taskOf(registry, "redact")).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void leaves_the_task_blank_when_nothing_resolves() {
    var registry = new PipelineRegistry(new ModelRegistry());

    registry.register(
      pipeline(
        "passthrough",
        null,
        null,
        new StepModel("start", StepTypes.BREAK, StepRole.STEP_ROLE_OUTPUT, null)
      )
    );

    assertThat(taskOf(registry, "passthrough")).isEmpty();
  }

  @Test
  void derives_from_the_model_s_declared_task_rather_than_its_engine_s() {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register(
      "llm",
      "llm",
      new StubClassifier(ModelTasks.TEXT_CLASSIFICATION),
      token -> {},
      ModelTasks.TEXT_GENERATION,
      true
    );
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(
      pipeline("agent", null, null, inferStep("generate", "llm", StepRole.STEP_ROLE_OUTPUT))
    );

    assertThat(taskOf(registry, "agent")).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void inherits_the_input_modalities_of_the_output_model() {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register("vlm", "vlm", new StubVisionEngine(), token -> {});
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(inferPipeline("describe", "vlm"));

    assertThat(registry.get("describe").orElseThrow().pipeline().inputModalities()).containsExactly(
      "text",
      "image"
    );
  }

  @Test
  void accepts_what_any_model_bound_step_accepts_not_just_the_output_step() {
    // caption-then-polish: the entry step decodes the image, a text-only model answers
    var modelRegistry = new ModelRegistry();
    modelRegistry.register("vlm", "vlm", new StubVisionEngine(), token -> {});
    modelRegistry.register(
      "llm",
      "llm",
      new StubClassifier(ModelTasks.TEXT_GENERATION),
      token -> {}
    );
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(
      pipeline(
        "caption",
        null,
        null,
        inferStep("describe", "vlm", StepRole.STEP_ROLE_UNSPECIFIED),
        inferStep("polish", "llm", StepRole.STEP_ROLE_OUTPUT)
      )
    );

    var caption = registry.get("caption").orElseThrow().pipeline();
    assertThat(caption.inputModalities()).containsExactly("text", "image");
    // the task still follows the step that answers
    assertThat(caption.task()).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void keeps_declared_modalities_instead_of_inheriting() {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register("vlm", "vlm", new StubVisionEngine(), token -> {});
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(
      pipeline(
        "text-only-front",
        null,
        List.of("text"),
        inferStep("generate", "vlm", StepRole.STEP_ROLE_OUTPUT)
      )
    );

    assertThat(
      registry.get("text-only-front").orElseThrow().pipeline().inputModalities()
    ).containsExactly("text");
  }

  @Test
  void falls_back_to_text_only_when_no_model_backs_the_output_step() {
    var registry = new PipelineRegistry(new ModelRegistry());

    registry.register(
      pipeline(
        "passthrough",
        null,
        null,
        new StepModel("start", StepTypes.BREAK, StepRole.STEP_ROLE_OUTPUT, null)
      )
    );

    assertThat(
      registry.get("passthrough").orElseThrow().pipeline().inputModalities()
    ).containsExactly("text");
  }

  private static PipelineModel pipeline(
    String pipelineId,
    String task,
    List<String> modalities,
    StepModel... steps
  ) {
    return new PipelineModel(
      pipelineId,
      pipelineId,
      steps[0].id(),
      List.of(steps),
      Map.of(),
      task,
      false,
      modalities
    );
  }

  private static StepModel inferStep(String stepId, String modelId, StepRole role) {
    return new StepModel(stepId, StepTypes.INFER, role, new Bound(modelId));
  }

  private static PipelineModel inferPipeline(String pipelineId, String modelId) {
    return pipeline(
      pipelineId,
      null,
      null,
      inferStep("generate", modelId, StepRole.STEP_ROLE_OUTPUT)
    );
  }

  private static String taskOf(PipelineRegistry registry, String pipelineId) {
    return registry.get(pipelineId).orElseThrow().pipeline().task();
  }

  private static PipelineRegistry registryWith(String modelId, String task) {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register(modelId, modelId, new StubClassifier(task), token -> {});
    return new PipelineRegistry(modelRegistry);
  }

  private static StepModel classifyStep(String stepId, String modelId, StepRole role) {
    return new StepModel(stepId, StepTypes.CLASSIFY, role, new Bound(modelId));
  }

  /** A minimal model-bound step config. */
  private record Bound(String modelId) implements ModelBoundConfig {}

  /** A text-gen engine whose projector reads images. */
  private record StubVisionEngine() implements io.gravitee.singularitee.engine.api.TextGenEngine {
    @Override
    public java.util.List<String> inputModalities() {
      return java.util.List.of(Modalities.TEXT, Modalities.IMAGE);
    }

    @Override
    public void start(
      java.util.function.Consumer<
        io.gravitee.singularitee.engine.api.ModelEngineToken
      > tokenConsumer
    ) {}

    @Override
    public io.reactivex.rxjava3.core.Flowable<
      io.gravitee.singularitee.engine.api.ModelEngineToken
    > rxStream(int seqId) {
      return io.reactivex.rxjava3.core.Flowable.never();
    }

    @Override
    public io.reactivex.rxjava3.core.Completable rxAddSequence(
      int seqId,
      io.gravitee.singularitee.engine.api.TextGenRequest request
    ) {
      return io.reactivex.rxjava3.core.Completable.never();
    }

    @Override
    public void close() {}
  }

  /** A classifier that only ever answers which task it serves. */
  private record StubClassifier(String task) implements ClassifierEngine {
    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.never();
    }

    @Override
    public void close() {}
  }
}
