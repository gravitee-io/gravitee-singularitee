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
package io.gravitee.singularitee.plugin.decide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a decide step asks its model, and what it refuses before the model runs. */
class DecideStepExecutorTest {

  private static final Question UPSET = new Question.Noul(
    "upset",
    "Is the user upset?",
    null,
    null
  );
  private static final DecideStepConfig FROM_REQUEST = new DecideStepConfig(
    "judge",
    null,
    null,
    true,
    null,
    null
  );
  private static final DecideStepConfig OWN_QUESTIONS = new DecideStepConfig(
    "judge",
    null,
    null,
    false,
    List.of(UPSET),
    null
  );

  private final List<DecisionRequest> asked = new ArrayList<>();
  private DecideStepExecutor executor;

  @BeforeEach
  void setUp() {
    var registry = new ModelRegistry();
    registry.register("judge", "Judge", new RecordingEngine(), token -> {});
    executor = new DecideStepExecutor(new StepExecutionContext(registry, null, null), null);
  }

  @Test
  void a_templated_call_asks_the_callers_questions_about_the_prompt() {
    var pc = context("I was charged twice", templated(UPSET));

    assertThat(executor.execute("decide", FROM_REQUEST, step(pc)).blockingGet()).isEqualTo("next");

    var input = (DecisionInput.Templated) asked.getFirst().input();
    assertThat(input.state()).isEqualTo("I was charged twice");
    assertThat(input.questions()).containsExactly(UPSET);
    assertThat(pc.decisionResult()).isNotNull();
  }

  @Test
  void caller_rendered_prompts_never_reach_the_model() {
    var raw = new DecisionRequest(
      new DecisionInput.Raw(
        List.of(new DecisionInput.RawPrompt("q", QuestionKind.NOUL, List.of(), "4111 1111", null))
      ),
      false
    );
    var pc = context("", raw);

    assertThatThrownBy(() -> executor.execute("decide", FROM_REQUEST, step(pc)).blockingGet())
      .isInstanceOf(DecisionException.class)
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.UNSUPPORTED_INPUT);
    assertThat(pc.decisionFailure()).isInstanceOf(DecisionException.class);
    assertThat(asked).isEmpty();
  }

  @Test
  void a_decision_call_without_a_state_is_refused_like_a_model_refuses_it() {
    for (var cfg : List.of(FROM_REQUEST, OWN_QUESTIONS)) {
      var pc = context("", templated(cfg == FROM_REQUEST ? List.of(UPSET) : List.of()));

      assertThatThrownBy(() -> executor.execute("decide", cfg, step(pc)).blockingGet())
        .isInstanceOf(DecisionException.class)
        .hasMessage("\"state\" must be provided")
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(Reason.INVALID_REQUEST);
    }
    assertThat(asked).isEmpty();
  }

  @Test
  void outside_a_decision_call_an_empty_state_skips_the_step() {
    var pc = context("", null);

    assertThat(executor.execute("decide", OWN_QUESTIONS, step(pc)).blockingGet()).isEqualTo("next");
    assertThat(asked).isEmpty();
  }

  @Test
  void a_decision_call_skips_a_step_reading_another_empty_field() {
    var cfg = new DecideStepConfig("judge", "summary", null, false, List.of(UPSET), null);
    var pc = context("I was charged twice", templated(List.of()));

    assertThat(executor.execute("decide", cfg, step(pc)).blockingGet()).isEqualTo("next");
    assertThat(asked).isEmpty();
  }

  private static DecisionRequest templated(Question... questions) {
    return templated(List.of(questions));
  }

  private static DecisionRequest templated(List<Question> questions) {
    return new DecisionRequest(new DecisionInput.Templated("ignored", questions), false);
  }

  private static PipelineContext context(String prompt, DecisionRequest caller) {
    var pc = new PipelineContext(
      prompt,
      List.of(new ChatTurn(ChatRole.USER, prompt)),
      null,
      List.of(),
      null
    );
    if (caller != null) pc.setDecisionRequest(caller);
    return pc;
  }

  private static StepContext step(PipelineContext pc) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pc);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    when(ctx.activeStepSpan()).thenReturn(new AtomicReference<>());
    var tracer = mock(Tracer.class);
    var span = mock(Span.class, RETURNS_SELF);
    when(tracer.startSpanFrom(any(), any())).thenReturn(span);
    when(ctx.tracer()).thenReturn(tracer);
    when(ctx.callerContext()).thenReturn(mock(Context.class));
    when(ctx.stepScribe()).thenReturn(SpanScribe.of(null));
    return ctx;
  }

  /** Records what it is asked and answers every noul question with 0.5. */
  private final class RecordingEngine implements DecisionEngine {

    @Override
    public DecisionCapabilities capabilities() {
      return new DecisionCapabilities(DecisionFamily.CUSTOM, 26, true, true);
    }

    @Override
    public Single<DecisionResult> rxDecide(DecisionRequest request) {
      asked.add(request);
      var input = (DecisionInput.Templated) request.input();
      List<Answer> answers = input
        .questions()
        .stream()
        .<Answer>map(q -> new Answer.Noul(q.id(), 0.5, List.of()))
        .toList();
      return Single.just(new DecisionResult(answers, 3));
    }

    @Override
    public void close() {}
  }
}
