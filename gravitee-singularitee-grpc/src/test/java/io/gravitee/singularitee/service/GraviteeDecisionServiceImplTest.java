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
package io.gravitee.singularitee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Value;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry.PipelineEntry;
import io.gravitee.singularitee.engine.pipeline.PipelineExecutor;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.protocol.DecideBatchRequest;
import io.gravitee.singularitee.protocol.DecideBatchState;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecisionQuestion;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.NoulQuestion;
import io.gravitee.singularitee.protocol.RawInput;
import io.gravitee.singularitee.protocol.RawNoul;
import io.gravitee.singularitee.protocol.RawPrompt;
import io.gravitee.singularitee.protocol.TemplatedInput;
import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A decision call to a pipeline: what the pipeline's guards get to see. */
class GraviteeDecisionServiceImplTest {

  private static final String PIPELINE = "triage";

  private static Vertx vertx;

  private final AtomicReference<InferPipelineRequest> ran = new AtomicReference<>();
  private PipelineExecutor executor;
  private GraviteeDecisionServiceImpl service;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    var pipelines = mock(PipelineRegistry.class);
    var model = new PipelineModel(
      PIPELINE,
      PIPELINE,
      "secrets",
      List.of(),
      Map.of(),
      ModelTasks.DECISION,
      false,
      List.of()
    );
    when(pipelines.get(PIPELINE)).thenReturn(
      Optional.of(new PipelineEntry(model, null, new AtomicInteger()))
    );
    executor = mock(PipelineExecutor.class);
    when(executor.executePipeline(any(), any(), any(), any())).thenAnswer(inv -> {
      InferPipelineRequest request = inv.getArgument(0);
      ran.set(request);
      var context = new PipelineContext(
        request.getPrompt(),
        List.of(new ChatTurn(ChatRole.USER, request.getPrompt())),
        null,
        List.of(),
        null
      );
      ((Consumer<PipelineContext>) inv.getArgument(3)).accept(context);
      context.setDecisionResult(
        new DecisionResult(List.of(new Answer.Noul("upset", 0.9, List.of())), 4)
      );
      return Completable.complete();
    });
    service = new GraviteeDecisionServiceImpl(
      vertx,
      new ModelRegistry(),
      pipelines,
      executor,
      mock(Tracer.class),
      new InferenceMetrics(null)
    );
  }

  @Test
  void a_state_becomes_the_prompt_the_guards_check() throws Exception {
    var request = DecideRequest.newBuilder()
      .setPipelineId(PIPELINE)
      .setTemplated(
        TemplatedInput.newBuilder()
          .setText("my card is 4111 1111 1111 1111")
          .addQuestions(
            DecisionQuestion.newBuilder()
              .setId("upset")
              .setInstructions(Value.newBuilder().setStringValue("Upset?"))
              .setNoul(NoulQuestion.getDefaultInstance())
          )
      )
      .build();

    var response = await(service.execute(request));

    assertThat(ran.get().getPrompt()).isEqualTo("my card is 4111 1111 1111 1111");
    assertThat(response.getAnswers(0).getNoul().getProbabilityTrue()).isEqualTo(0.9);
  }

  @Test
  void raw_prompts_are_refused_before_the_pipeline_runs() {
    var request = DecideRequest.newBuilder()
      .setPipelineId(PIPELINE)
      .setRaw(
        RawInput.newBuilder().addPrompts(
          RawPrompt.newBuilder()
            .setQuestionId("upset")
            .setNoul(RawNoul.getDefaultInstance())
            .setText("my card is 4111 1111 1111 1111")
        )
      )
      .build();

    assertThatThrownBy(() -> await(service.execute(request)))
      .isInstanceOf(DecisionException.class)
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.UNSUPPORTED_INPUT);
    verifyNoInteractions(executor);
  }

  @Test
  void a_batch_that_would_fail_is_refused_before_any_state_runs() {
    var question = DecisionQuestion.newBuilder()
      .setId("upset")
      .setInstructions(Value.newBuilder().setStringValue("Upset?"))
      .setNoul(NoulQuestion.getDefaultInstance())
      .build();
    var text = DecideBatchState.newBuilder().setText("hello").build();
    var tooMany = DecideBatchRequest.newBuilder().setPipelineId(PIPELINE).addQuestions(question);
    for (int i = 0; i <= GraviteeDecisionServiceImpl.MAX_BATCH_STATES; i++) tooMany.addStates(text);

    for (var request : List.of(
      tooMany.build(),
      DecideBatchRequest.newBuilder().setPipelineId(PIPELINE).build(),
      DecideBatchRequest.newBuilder()
        .setPipelineId(PIPELINE)
        .addStates(text)
        .addStates(DecideBatchState.newBuilder().setText(" "))
        .build(),
      DecideBatchRequest.newBuilder()
        .setPipelineId(PIPELINE)
        .addStates(text)
        .addQuestions(question)
        .addQuestions(question)
        .build(),
      DecideBatchRequest.newBuilder().setModelId("judge").addStates(text).build()
    )) {
      assertThatThrownBy(() -> await(service.decideBatch(request)))
        .isInstanceOf(StatusException.class)
        .extracting(e -> ((StatusException) e).status())
        .isEqualTo(GrpcStatus.INVALID_ARGUMENT);
    }
    verifyNoInteractions(executor);
  }

  private static <T> T await(Future<T> future) throws Exception {
    try {
      return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw (Exception) e.getCause();
    }
  }
}
