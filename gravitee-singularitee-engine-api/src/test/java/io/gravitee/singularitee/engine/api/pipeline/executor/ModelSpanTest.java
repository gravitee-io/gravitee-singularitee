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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ModelEngineToken;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.tracing.PropagatesTraceContext;
import io.gravitee.singularitee.engine.api.tracing.RecordingTracer;
import io.gravitee.singularitee.engine.api.tracing.TraceContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The span {@link ModelBoundStepExecutor} opens around each model call, as exported. */
class ModelSpanTest {

  private RecordingTracer tracing;

  @BeforeEach
  void setUp() throws Exception {
    tracing = new RecordingTracer();
  }

  @AfterEach
  void tearDown() {
    tracing.close();
  }

  private static class StubClassifier implements ClassifierEngine {

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.just(new ClassifyResponse("safe", 1f, Map.of("safe", 1f), List.of()));
    }

    @Override
    public void close() {}
  }

  /** A remote engine: records the trace context it would send with each call. */
  private static final class RemoteClassifier
    extends StubClassifier
    implements PropagatesTraceContext {

    final List<Map<String, String>> sent = new CopyOnWriteArrayList<>();

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      sent.add(TraceContext.current());
      return super.rxClassify(request);
    }

    @Override
    public boolean acceptsRequestLabels() {
      return true;
    }
  }

  private static final class StubGenerator implements TextGenEngine {

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      return Completable.complete();
    }

    @Override
    public void close() {}
  }

  private static final class GuardStepExecutor
    extends ModelBoundStepExecutor<String, ClassifierEngine> {

    private final Function<ClassifierEngine, Maybe<String>> body;

    GuardStepExecutor(
      StepExecutionContext execContext,
      Function<ClassifierEngine, Maybe<String>> body
    ) {
      super(execContext);
      this.body = body;
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
      return body.apply(engine);
    }
  }

  private static final class InferStepExecutor
    extends ModelBoundStepExecutor<String, TextGenEngine> {

    InferStepExecutor(StepExecutionContext execContext) {
      super(execContext);
    }

    @Override
    protected String getModelId(String config) {
      return config;
    }

    @Override
    protected Class<TextGenEngine> engineType() {
      return TextGenEngine.class;
    }

    @Override
    protected Maybe<String> rxExecuteWithEngine(
      String stepId,
      String config,
      TextGenEngine engine,
      StepContext ctx
    ) {
      recordTokenUsage(ctx, 32, 2);
      return Maybe.just("next");
    }
  }

  private static StepExecutionContext models(
    String id,
    io.gravitee.singularitee.engine.api.ModelEngine engine
  ) {
    var registry = new ModelRegistry();
    registry.register(id, id, engine, null);
    return new StepExecutionContext(registry, null, null);
  }

  private StepContext stepContext(Span stepSpan) {
    return new StepContext(
      new PipelineContext("hello", null, null, null, null),
      null,
      null,
      tracing.context(),
      tracing.tracer(),
      null,
      null,
      new AtomicReference<>(stepSpan)
    );
  }

  private SpanData runGuard(
    ClassifierEngine engine,
    Function<ClassifierEngine, Maybe<String>> body
  ) {
    Span step = tracing.start("step pii_guard (guard)");
    new GuardStepExecutor(models("pii", engine), body)
      .execute("pii_guard", "pii", stepContext(step))
      .onErrorComplete()
      .blockingGet();
    tracing.end(step);
    return tracing.span("classify pii");
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  @Test
  void the_model_span_is_named_after_the_operation_and_the_model() {
    var span = runGuard(new StubClassifier(), e -> Maybe.just("next"));

    assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    assertThat(span.getParentSpanId()).isEqualTo(
      tracing.span("step pii_guard (guard)").getSpanId()
    );
    assertThat(attr(span, "singularitee.model.id")).isEqualTo("pii");
    assertThat(attr(span, "singularitee.model.type")).isEqualTo("classifier");
    assertThat(attr(span, "model.id")).isEqualTo("pii");
    assertThat(attr(span, "op")).isEqualTo("guard");
  }

  @Test
  void a_successful_model_call_is_ok() {
    var span = runGuard(new StubClassifier(), e -> Maybe.just("next"));

    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void a_failed_model_call_is_an_error_with_the_exception_recorded() {
    var span = runGuard(new StubClassifier(), e -> Maybe.error(new IllegalStateException("boom")));

    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getStatus().getDescription()).isEqualTo("boom");
    assertThat(attr(span, "exception.type")).isEqualTo(IllegalStateException.class.getName());
    assertThat(attr(span, "exception.message")).isEqualTo("boom");
    assertThat(span.getEvents()).isEmpty();
  }

  @Test
  void the_model_span_ends_before_the_step_moves_on() {
    var exportedWhenTheStepResumed = new ArrayList<String>();
    Span step = tracing.start("step pii_guard (guard)");

    new GuardStepExecutor(models("pii", new StubClassifier()), e -> Maybe.just("next"))
      .execute("pii_guard", "pii", stepContext(step))
      .doOnSuccess(next ->
        tracing.spans().forEach(s -> exportedWhenTheStepResumed.add(s.getName()))
      )
      .blockingGet();

    assertThat(exportedWhenTheStepResumed).containsExactly("classify pii");
  }

  @Test
  void a_generation_carries_the_gen_ai_attributes_and_its_token_usage() {
    Span step = tracing.start("step generate (infer)");
    new InferStepExecutor(models("llm", new StubGenerator()))
      .execute("generate", "llm", stepContext(step))
      .blockingGet();
    tracing.end(step);

    var span = tracing.span("infer llm");
    assertThat(attr(span, "gen_ai.request.model")).isEqualTo("llm");
    assertThat(attr(span, "gen_ai.operation.name")).isEqualTo("chat");
    assertThat(attr(span, "singularitee.model.type")).isEqualTo("text_gen");
    assertThat(
      span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))
    ).isEqualTo(32L);
    assertThat(
      span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))
    ).isEqualTo(2L);
  }

  @Test
  void a_remote_engine_sends_the_model_span_as_parent_on_every_call() {
    var remote = new RemoteClassifier();

    var span = runGuard(remote, engine ->
      engine
        .rxClassify(new ClassifyRequest("first"))
        .delay(5, TimeUnit.MILLISECONDS)
        .flatMap(first -> engine.rxClassify(new ClassifyRequest("second")))
        .map(second -> "next")
        .toMaybe()
    );

    String traceparent = "00-" + span.getTraceId() + "-" + span.getSpanId() + "-01";
    assertThat(remote.sent)
      .hasSize(2)
      .allSatisfy(headers -> assertThat(headers).containsEntry("traceparent", traceparent));
    assertThat(TraceContext.current()).isEmpty();
  }

  @Test
  void a_bound_remote_engine_still_answers_as_itself() {
    var remote = new RemoteClassifier();
    var seen = new AtomicReference<Boolean>();

    runGuard(remote, engine -> {
      seen.set(engine.acceptsRequestLabels());
      return Maybe.just("next");
    });

    assertThat(seen.get()).isTrue();
  }

  @Test
  void a_local_engine_is_called_without_trace_context() {
    var local = new StubClassifier();
    var passed = new AtomicReference<ClassifierEngine>();

    runGuard(local, engine -> {
      passed.set(engine);
      return Maybe.just("next");
    });

    assertThat(passed.get()).isSameAs(local);
  }
}
