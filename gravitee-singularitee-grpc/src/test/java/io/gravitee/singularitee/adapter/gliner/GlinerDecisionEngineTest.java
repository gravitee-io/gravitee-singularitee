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
package io.gravitee.singularitee.adapter.gliner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import io.gravitee.lab.gliner4j.schema.Decision;
import io.gravitee.lab.gliner4j.schema.DecisionQuestion;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Typed questions asked of a GLiNER2 decider, and its label probabilities read back as answers. */
class GlinerDecisionEngineTest {

  private static Vertx vertx;

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() {
    vertx.close();
  }

  /** Gives the first label 0.7 and shares the rest, and records what it was asked. */
  private static final class FakeDecider implements GlinerDecisionEngine.Decider {

    final List<String> states = new ArrayList<>();
    final List<DecisionQuestion> asked = new ArrayList<>();

    @Override
    public Decision decide(String state, DecisionQuestion question) {
      states.add(state);
      asked.add(question);
      var probabilities = new LinkedHashMap<String, Float>();
      int n = question.options().size();
      for (int i = 0; i < n; i++) {
        probabilities.put(question.options().get(i).name(), i == 0 ? 0.7f : 0.3f / (n - 1));
      }
      return new Decision(question.id(), probabilities);
    }

    @Override
    public void close() {}
  }

  private static DecisionRequest templated(Object state, Question... questions) {
    return new DecisionRequest(new DecisionInput.Templated(state, List.of(questions)), false);
  }

  private static Question.Choice intent() {
    return new Question.Choice(
      "intent",
      "What does the customer want?",
      List.of(new Question.Option("refund", "Money back"), new Question.Option("other", null))
    );
  }

  @Test
  void answers_each_question_type_from_the_label_probabilities() {
    var decider = new FakeDecider();
    var engine = new GlinerDecisionEngine(decider, vertx);

    var result = engine
      .rxDecide(
        templated(
          "I was charged twice.",
          intent(),
          new Question.Score("urgency", "How urgent?", List.of("low", "high")),
          new Question.Noul("handoff", "Asks for a person?", "they ask", null)
        )
      )
      .blockingGet();

    var choice = (Answer.Choice) result.answers().get(0);
    assertThat(choice.choice()).isEqualTo("refund");
    assertThat(choice.probabilities().getFirst().probability()).isCloseTo(0.7, offset(1e-6));
    var score = (Answer.Score) result.answers().get(1);
    assertThat(score.probabilities())
      .extracting(Answer.OptionProbability::key)
      .containsExactly("0", "1");
    assertThat(score.score()).isCloseTo(0.3, offset(1e-6));
    var noul = (Answer.Noul) result.answers().get(2);
    assertThat(noul.probabilityTrue()).as("yes is listed first").isCloseTo(0.7, offset(1e-6));

    assertThat(decider.states).containsOnly("I was charged twice.");
    var askedChoice = (DecisionQuestion.Choice) decider.asked.get(0);
    assertThat(askedChoice.instructions()).isEqualTo("What does the customer want?");
    assertThat(askedChoice.options().get(1).description()).isEmpty();
    assertThat(((DecisionQuestion.Score) decider.asked.get(1)).levels()).containsExactly(
      "low",
      "high"
    );
    assertThat(((DecisionQuestion.Noul) decider.asked.get(2)).whenTrue()).isEqualTo("they ask");
  }

  @Test
  void a_structured_state_is_read_as_text() {
    var decider = new FakeDecider();
    new GlinerDecisionEngine(decider, vertx).decide(
      templated(Map.of("user", "Pay Derek"), intent())
    );
    assertThat(decider.states).containsExactly("user: Pay Derek");
  }

  @Test
  void refuses_raw_prompts_and_blank_states() {
    var engine = new GlinerDecisionEngine(new FakeDecider(), vertx);
    assertThat(engine.capabilities().family()).isEqualTo(DecisionFamily.GLINER);
    assertThat(engine.capabilities().raw()).isFalse();

    var raw = new DecisionRequest(
      new DecisionInput.Raw(
        List.of(new DecisionInput.RawPrompt("x", QuestionKind.NOUL, List.of(), "hi", null))
      ),
      false
    );
    assertThatThrownBy(() -> engine.decide(raw)).satisfies(e ->
      assertThat(((DecisionException) e).reason()).isEqualTo(Reason.UNSUPPORTED_INPUT)
    );
    assertThatThrownBy(() -> engine.decide(templated("  ", intent()))).hasMessageContaining(
      "must not be blank"
    );
  }

  @Test
  void refuses_score_levels_the_model_cannot_tell_apart() {
    var engine = new GlinerDecisionEngine(new FakeDecider(), vertx);
    assertThatThrownBy(() ->
      engine.decide(
        templated("text", new Question.Score("risk", "How risky?", List.of("high", "high")))
      )
    ).hasMessage("questions.risk: score levels must be distinct and not blank");
  }

  @Test
  void a_label_the_model_did_not_score_fails_the_evaluation() {
    GlinerDecisionEngine.Decider partial = new GlinerDecisionEngine.Decider() {
      @Override
      public Decision decide(String state, DecisionQuestion question) {
        return new Decision(question.id(), Map.of("refund", 1f));
      }

      @Override
      public void close() {}
    };
    assertThatThrownBy(() ->
      new GlinerDecisionEngine(partial, vertx).decide(templated("text", intent()))
    ).satisfies(e ->
      assertThat(((DecisionException) e).reason()).isEqualTo(Reason.EVALUATION_FAILED)
    );
  }
}
