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
package io.gravitee.singularitee.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.Modalities;
import io.gravitee.singularitee.engine.ModelTasks;
import io.gravitee.singularitee.protocol.ClassifyStepConfig;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.Pipeline;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.StepType;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

/**
 * What a pipeline advertises — its task and its input modalities — either declared
 * by the workspace or derived: the task from the model behind its output step, the
 * modalities from every model the DAG feeds.
 */
class PipelineDerivationTest {

  @Test
  void derives_the_task_from_the_model_behind_the_output_step() {
    var registry = registryWith("pii", ModelTasks.TOKEN_CLASSIFICATION);

    registry.register(
      Pipeline.newBuilder()
        .setPipelineId("redact")
        .setEntryStepId("scan")
        .addSteps(classifyStep("scan", "pii", StepRole.STEP_ROLE_OUTPUT))
        .build()
    );

    assertThat(taskOf(registry, "redact")).isEqualTo(ModelTasks.TOKEN_CLASSIFICATION);
  }

  @Test
  void falls_back_to_the_entry_step_when_no_step_claims_the_output_role() {
    var registry = registryWith("topics", ModelTasks.TEXT_CLASSIFICATION);

    registry.register(
      Pipeline.newBuilder()
        .setPipelineId("triage")
        .setEntryStepId("classify")
        .addSteps(classifyStep("classify", "topics", StepRole.STEP_ROLE_UNSPECIFIED))
        .build()
    );

    assertThat(taskOf(registry, "triage")).isEqualTo(ModelTasks.TEXT_CLASSIFICATION);
  }

  @Test
  void keeps_a_declared_task_instead_of_deriving_one() {
    var registry = registryWith("pii", ModelTasks.TOKEN_CLASSIFICATION);

    registry.register(
      Pipeline.newBuilder()
        .setPipelineId("redact")
        .setEntryStepId("scan")
        .setTask(ModelTasks.TEXT_GENERATION)
        .addSteps(classifyStep("scan", "pii", StepRole.STEP_ROLE_OUTPUT))
        .build()
    );

    assertThat(taskOf(registry, "redact")).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void leaves_the_task_blank_when_nothing_resolves() {
    var registry = new PipelineRegistry(new ModelRegistry());

    registry.register(
      Pipeline.newBuilder()
        .setPipelineId("passthrough")
        .setEntryStepId("start")
        .addSteps(
          PipelineStep.newBuilder()
            .setStepId("start")
            .setType(StepType.STEP_TYPE_BREAK)
            .setRole(StepRole.STEP_ROLE_OUTPUT)
            .build()
        )
        .build()
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
      Pipeline.newBuilder()
        .setPipelineId("agent")
        .setEntryStepId("generate")
        .addSteps(
          PipelineStep.newBuilder()
            .setStepId("generate")
            .setType(StepType.STEP_TYPE_INFER)
            .setRole(StepRole.STEP_ROLE_OUTPUT)
            .setInferConfig(InferStepConfig.newBuilder().setModelId("llm"))
            .build()
        )
        .build()
    );

    assertThat(taskOf(registry, "agent")).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void inherits_the_input_modalities_of_the_output_model() {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register("vlm", "vlm", new StubVisionEngine(), token -> {});
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(inferPipeline("describe", "vlm"));

    assertThat(
      registry.get("describe").orElseThrow().pipeline().getInputModalitiesList()
    ).containsExactly("text", "image");
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
      Pipeline.newBuilder()
        .setPipelineId("caption")
        .setEntryStepId("describe")
        .addSteps(
          PipelineStep.newBuilder()
            .setStepId("describe")
            .setType(StepType.STEP_TYPE_INFER)
            .setInferConfig(InferStepConfig.newBuilder().setModelId("vlm"))
            .build()
        )
        .addSteps(
          PipelineStep.newBuilder()
            .setStepId("polish")
            .setType(StepType.STEP_TYPE_INFER)
            .setRole(StepRole.STEP_ROLE_OUTPUT)
            .setInferConfig(InferStepConfig.newBuilder().setModelId("llm"))
            .build()
        )
        .build()
    );

    var caption = registry.get("caption").orElseThrow().pipeline();
    assertThat(caption.getInputModalitiesList()).containsExactly("text", "image");
    // the task still follows the step that answers
    assertThat(caption.getTask()).isEqualTo(ModelTasks.TEXT_GENERATION);
  }

  @Test
  void keeps_declared_modalities_instead_of_inheriting() {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register("vlm", "vlm", new StubVisionEngine(), token -> {});
    var registry = new PipelineRegistry(modelRegistry);

    registry.register(
      inferPipeline("text-only-front", "vlm").toBuilder().addInputModalities("text").build()
    );

    assertThat(
      registry.get("text-only-front").orElseThrow().pipeline().getInputModalitiesList()
    ).containsExactly("text");
  }

  @Test
  void falls_back_to_text_only_when_no_model_backs_the_output_step() {
    var registry = new PipelineRegistry(new ModelRegistry());

    registry.register(
      Pipeline.newBuilder()
        .setPipelineId("passthrough")
        .setEntryStepId("start")
        .addSteps(
          PipelineStep.newBuilder()
            .setStepId("start")
            .setType(StepType.STEP_TYPE_BREAK)
            .setRole(StepRole.STEP_ROLE_OUTPUT)
            .build()
        )
        .build()
    );

    assertThat(
      registry.get("passthrough").orElseThrow().pipeline().getInputModalitiesList()
    ).containsExactly("text");
  }

  private static Pipeline inferPipeline(String pipelineId, String modelId) {
    return Pipeline.newBuilder()
      .setPipelineId(pipelineId)
      .setEntryStepId("generate")
      .addSteps(
        PipelineStep.newBuilder()
          .setStepId("generate")
          .setType(StepType.STEP_TYPE_INFER)
          .setRole(StepRole.STEP_ROLE_OUTPUT)
          .setInferConfig(InferStepConfig.newBuilder().setModelId(modelId))
          .build()
      )
      .build();
  }

  private static String taskOf(PipelineRegistry registry, String pipelineId) {
    return registry.get(pipelineId).orElseThrow().pipeline().getTask();
  }

  private static PipelineRegistry registryWith(String modelId, String task) {
    var modelRegistry = new ModelRegistry();
    modelRegistry.register(modelId, modelId, new StubClassifier(task), token -> {});
    return new PipelineRegistry(modelRegistry);
  }

  private static PipelineStep classifyStep(String stepId, String modelId, StepRole role) {
    return PipelineStep.newBuilder()
      .setStepId(stepId)
      .setType(StepType.STEP_TYPE_CLASSIFY)
      .setRole(role)
      .setClassifyConfig(ClassifyStepConfig.newBuilder().setModelId(modelId))
      .build();
  }

  /** A text-gen engine whose projector reads images. */
  private record StubVisionEngine() implements io.gravitee.singularitee.engine.TextGenEngine {
    @Override
    public java.util.List<String> inputModalities() {
      return java.util.List.of(Modalities.TEXT, Modalities.IMAGE);
    }

    @Override
    public void start(
      java.util.function.Consumer<io.gravitee.singularitee.engine.ModelEngineToken> tokenConsumer
    ) {}

    @Override
    public io.reactivex.rxjava3.core.Flowable<
      io.gravitee.singularitee.engine.ModelEngineToken
    > rxStream(int seqId) {
      return io.reactivex.rxjava3.core.Flowable.never();
    }

    @Override
    public io.reactivex.rxjava3.core.Completable rxAddSequence(
      int seqId,
      io.gravitee.singularitee.engine.TextGenRequest request
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
