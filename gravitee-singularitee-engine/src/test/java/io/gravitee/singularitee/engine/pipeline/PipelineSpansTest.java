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

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.TracingOptions;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.pipeline.executor.StepDispatcher;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferPipelineRequest;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.StepRole;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The spans one pipeline run exports: names, parents, attributes and status. */
class PipelineSpansTest {

  private RecordingTracer tracing;

  @BeforeEach
  void setUp() throws Exception {
    tracing = new RecordingTracer();
  }

  @AfterEach
  void tearDown() {
    TracingOptions.configure(true, false);
    tracing.close();
  }

  private static final class Discarding implements WriteStream<InferResponse> {

    @Override
    public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public Future<Void> write(InferResponse data) {
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return false;
    }

    @Override
    public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
      return this;
    }
  }

  private static final class Classifier implements ClassifierEngine {

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.just(new ClassifyResponse("safe", 1f, Map.of("safe", 1f), List.of())).delay(
        2,
        TimeUnit.MILLISECONDS
      );
    }

    @Override
    public void close() {}
  }

  /** Classifies the prompt; blocks the run when {@code block} is set. */
  private static final class GuardStepExecutor
    extends ModelBoundStepExecutor<String, ClassifierEngine> {

    private final boolean block;

    GuardStepExecutor(StepExecutionContext execContext, boolean block) {
      super(execContext);
      this.block = block;
    }

    @Override
    protected String getModelId(String config) {
      return config;
    }

    @Override
    protected Class<ClassifierEngine> engineType() {
      return ClassifierEngine.class;
    }

    @Override
    protected Maybe<String> rxExecuteWithEngine(
      String stepId,
      String config,
      ClassifierEngine engine,
      StepContext ctx
    ) {
      return engine
        .rxClassify(new ClassifyRequest("hello"))
        .flatMapMaybe(verdict -> {
          if (block) {
            ctx.pipelineContext().set(stepId + ".output", "blocked");
            ctx
              .pipelineContext()
              .signalHalt(stepId + ".output", FinishReason.FINISH_REASON_GUARD_BLOCKED);
            return Maybe.empty();
          }
          return ctx.rxNextStep(stepId);
        });
    }
  }

  private void run(boolean block, StepExecutor<Object> answer) {
    var models = new ModelRegistry();
    models.register("pii", "pii", new Classifier(), null);
    var pipelines = new PipelineRegistry(models);
    pipelines.register(
      new PipelineModel(
        "guarded",
        "guarded",
        "pii_guard",
        List.of(
          new StepModel("pii_guard", "guard", StepRole.STEP_ROLE_INTERNAL, "pii"),
          new StepModel("answer", "fake", StepRole.STEP_ROLE_OUTPUT, null)
        ),
        Map.of("pii_guard", "answer"),
        "",
        false,
        List.of()
      )
    );
    var dispatcher = new StepDispatcher(
      Map.of(
        "guard",
        new GuardStepExecutor(new StepExecutionContext(models, null, null), block),
        "fake",
        answer
      )
    );
    var executor = new PipelineExecutor(
      pipelines,
      dispatcher,
      tracing.tracer(),
      null,
      null,
      null,
      null
    );
    var request = InferPipelineRequest.newBuilder().setPipelineId("guarded").setPrompt("hello");
    executor.executePipeline(request.build(), new Discarding(), tracing.context()).blockingAwait();
  }

  private void run() {
    run(false, (id, config, ctx) -> {
      ctx.pipelineContext().set("answer.output", "hi");
      return Maybe.empty();
    });
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  @Test
  void every_span_is_named_after_what_it_runs() {
    run();

    assertThat(tracing.spans())
      .extracting(SpanData::getName)
      .containsExactlyInAnyOrder(
        "pipeline guarded",
        "step pii_guard (guard)",
        "classify pii",
        "step answer (fake)"
      );
  }

  @Test
  void each_span_hangs_under_the_one_that_opened_it() {
    run();

    var pipeline = tracing.span("pipeline guarded");
    var guard = tracing.span("step pii_guard (guard)");
    assertThat(guard.getParentSpanId()).isEqualTo(pipeline.getSpanId());
    assertThat(tracing.span("step answer (fake)").getParentSpanId()).isEqualTo(
      pipeline.getSpanId()
    );
    assertThat(tracing.span("classify pii").getParentSpanId()).isEqualTo(guard.getSpanId());
    assertThat(tracing.spans())
      .extracting(SpanData::getTraceId)
      .containsOnly(pipeline.getTraceId());
  }

  @Test
  void pipeline_and_step_spans_carry_their_ids() {
    run();

    var pipeline = tracing.span("pipeline guarded");
    assertThat(attr(pipeline, "singularitee.pipeline.id")).isEqualTo("guarded");
    assertThat(attr(pipeline, "pipeline.id")).isEqualTo("guarded");
    var guard = tracing.span("step pii_guard (guard)");
    assertThat(attr(guard, "singularitee.step.id")).isEqualTo("pii_guard");
    assertThat(attr(guard, "singularitee.step.type")).isEqualTo("guard");
    assertThat(attr(guard, "singularitee.pipeline.id")).isEqualTo("guarded");
    assertThat(attr(guard, "step.id")).isEqualTo("pii_guard");
    assertThat(attr(guard, "step.type")).isEqualTo("guard");
    assertThat(attr(guard, "model.id")).isEqualTo("pii");
  }

  @Test
  void a_successful_run_is_ok_throughout() {
    run();

    assertThat(tracing.spans()).allSatisfy(span ->
      assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK)
    );
  }

  @Test
  void no_span_carries_events_an_exporter_could_store_as_unnamed_spans() {
    TracingOptions.configure(true, true);

    run();

    assertThat(tracing.spans()).allSatisfy(span -> assertThat(span.getEvents()).isEmpty());
    assertThat(attr(tracing.span("pipeline guarded"), "singularitee.turn.input")).isEqualTo(
      "hello"
    );
  }

  @Test
  void the_turn_content_stays_off_the_spans_unless_verbose() {
    run();

    assertThat(attr(tracing.span("pipeline guarded"), "singularitee.turn.input")).isNull();
  }

  @Test
  void a_step_ends_before_the_next_one_starts() {
    run();

    var guard = tracing.span("step pii_guard (guard)");
    var model = tracing.span("classify pii");
    var answer = tracing.span("step answer (fake)");
    assertThat(model.getEndEpochNanos()).isLessThanOrEqualTo(guard.getEndEpochNanos());
    assertThat(guard.getEndEpochNanos()).isLessThanOrEqualTo(answer.getStartEpochNanos());
  }

  @Test
  void a_guard_that_blocks_the_run_is_ok_and_marked_halted() {
    run(true, (id, config, ctx) -> Maybe.empty());

    var guard = tracing.span("step pii_guard (guard)");
    assertThat(guard.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    assertThat(
      guard.getAttributes().get(AttributeKey.booleanKey("singularitee.step.halted"))
    ).isTrue();
    assertThat(tracing.span("pipeline guarded").getStatus().getStatusCode()).isEqualTo(
      StatusCode.OK
    );
    assertThat(tracing.spans()).extracting(SpanData::getName).doesNotContain("step answer (fake)");
  }

  @Test
  void a_step_that_runs_through_is_not_marked_halted() {
    run();

    assertThat(
      tracing
        .span("step pii_guard (guard)")
        .getAttributes()
        .get(AttributeKey.booleanKey("singularitee.step.halted"))
    ).isNull();
  }

  @Test
  void a_failed_step_is_an_error_with_its_exception_as_attributes() {
    run(false, (id, config, ctx) -> Maybe.error(new IllegalStateException("model unreachable")));

    var answer = tracing.span("step answer (fake)");
    assertThat(answer.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(attr(answer, "exception.type")).isEqualTo(IllegalStateException.class.getName());
    assertThat(attr(answer, "exception.message")).isEqualTo("model unreachable");
    assertThat(answer.getEvents()).isEmpty();
  }
}
