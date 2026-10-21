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
package io.gravitee.singularitee.inference.vllm.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Requests reach the runtime as System One bodies, and its answers come back in question order.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmDecisionModelTest {

  private static final Question INTENT = new Question.Choice(
    "intent",
    "What does the user want?",
    List.of(
      new Question.Option("refund", "Money back"),
      new Question.Option("billing", null),
      new Question.Option("other", Map.of("hint", "anything else"))
    )
  );
  private static final Question URGENCY = new Question.Score(
    "urgency",
    "How urgent is it?",
    List.of("Not urgent", "Somewhat", "Urgent")
  );
  private static final Question UPSET = new Question.Noul(
    "upset",
    "Is the user upset?",
    null,
    null
  );

  private final AtomicReference<Map<String, Object>> sent = new AtomicReference<>();

  private VllmDecisionModel model(Function<Map<String, Object>, Map<String, Object>> answer) {
    return new VllmDecisionModel(
      new VllmDecisionModel.Runtime() {
        @Override
        public Map<String, Object> decide(Map<String, Object> body) {
          sent.set(body);
          return answer.apply(body);
        }

        @Override
        public void close() {}
      },
      DecisionFamily.DECISION2
    );
  }

  private static DecisionRequest templated(Object state, Question... questions) {
    return new DecisionRequest(new DecisionInput.Templated(state, List.of(questions)), false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sends_the_ordered_system_one_body() {
    var model = model(body ->
      Map.of(
        "answers",
        Map.of(
          "intent",
          Map.of(
            "type",
            "choice",
            "choice",
            "refund",
            "probabilities",
            Map.of(),
            "confidence",
            0.5
          ),
          "urgency",
          Map.of("type", "score", "score", 1.0, "probabilities", Map.of(), "confidence", 0.0),
          "upset",
          Map.of("type", "noul", "noul", 0.5)
        )
      )
    );

    model.decide(templated(Map.of("request", "I was charged twice"), INTENT, URGENCY, UPSET));

    var body = sent.get();
    assertThat(body.get("state")).isEqualTo(Map.of("request", "I was charged twice"));
    var questions = (Map<String, Map<String, Object>>) body.get("questions");
    assertThat(questions.keySet()).containsExactly("intent", "urgency", "upset");
    var choices = (List<Map<String, Object>>) questions.get("intent").get("choices");
    assertThat(choices)
      .extracting(c -> c.get("key"))
      .containsExactly("refund", "billing", "other");
    assertThat(choices.get(1)).containsEntry("description", null);
    assertThat(choices.get(2).get("description")).isEqualTo(Map.of("hint", "anything else"));
    assertThat(questions.get("urgency"))
      .containsEntry("type", "score")
      .containsEntry("levels", List.of("Not urgent", "Somewhat", "Urgent"));
    assertThat(questions.get("upset")).containsEntry("type", "noul").doesNotContainKey("criteria");
  }

  @Test
  @SuppressWarnings("unchecked")
  void noul_descriptions_become_criteria() {
    var model = model(body -> Map.of("answers", Map.of("ok", Map.of("type", "noul", "noul", 0.9))));

    model.decide(templated("text", new Question.Noul("ok", "Is it fine?", "Yes, fine", null)));

    var criteria = (Map<String, Object>) ((Map<String, Map<String, Object>>) sent
        .get()
        .get("questions")).get("ok").get("criteria");
    assertThat(criteria).containsEntry("true", "Yes, fine").containsEntry("false", null);
  }

  @Test
  void maps_answers_in_question_order_with_usage() {
    var model = model(body ->
      Map.of(
        "answers",
        Map.of(
          "upset",
          Map.of("type", "noul", "noul", 0.8),
          "urgency",
          Map.of(
            "type",
            "score",
            "score",
            1.4,
            "probabilities",
            Map.of("0", 0.1, "1", 0.4, "2", 0.5),
            "confidence",
            0.3,
            "legend",
            Map.of("0", "Not urgent")
          ),
          "intent",
          Map.of(
            "type",
            "choice",
            "choice",
            "billing",
            "probabilities",
            Map.of("refund", 0.2, "billing", 0.7, "other", 0.1),
            "confidence",
            0.55
          )
        ),
        "usage",
        Map.of("input_tokens", 42, "output_tokens", 0)
      )
    );

    var result = model.decide(templated("I was charged twice", INTENT, URGENCY, UPSET));

    assertThat(result.inputTokens()).isEqualTo(42);
    assertThat(result.answers())
      .extracting(Answer::questionId)
      .containsExactly("intent", "urgency", "upset");
    var intent = (Answer.Choice) result.answers().get(0);
    assertThat(intent.choice()).isEqualTo("billing");
    assertThat(intent.confidence()).isEqualTo(0.55);
    assertThat(intent.probabilities())
      .extracting(Answer.OptionProbability::key)
      .containsExactly("refund", "billing", "other");
    assertThat(intent.probabilities().get(1).probability()).isEqualTo(0.7);
    var urgency = (Answer.Score) result.answers().get(1);
    assertThat(urgency.score()).isEqualTo(1.4);
    assertThat(urgency.mode()).isEqualTo(2);
    assertThat(urgency.confidence()).isEqualTo(0.3);
    assertThat(((Answer.Noul) result.answers().get(2)).probabilityTrue()).isEqualTo(0.8);
  }

  @Test
  void a_failed_question_fails_the_request() {
    var invalid = model(body ->
      Map.of(
        "answers",
        Map.of("intent", Map.of("type", "choice", "error", "invalid_question", "message", "bad"))
      )
    );
    assertThatThrownBy(() -> invalid.decide(templated("x", INTENT)))
      .isInstanceOf(DecisionException.class)
      .hasMessageContaining("questions.intent")
      .hasMessageContaining("invalid_question: bad")
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.INVALID_REQUEST);

    var unavailable = model(body ->
      Map.of("answers", Map.of("intent", Map.of("type", "choice", "error", "unavailable")))
    );
    assertThatThrownBy(() -> unavailable.decide(templated("x", INTENT)))
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.EVALUATION_FAILED);
  }

  @Test
  void refuses_raw_prompts_and_a_missing_state() {
    var model = model(body -> Map.of());
    var raw = new DecisionRequest(
      new DecisionInput.Raw(
        List.of(
          new DecisionInput.RawPrompt("q", QuestionKind.NOUL, List.of("false", "true"), "x", null)
        )
      ),
      false
    );
    assertThatThrownBy(() -> model.decide(raw))
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.UNSUPPORTED_INPUT);
    assertThatThrownBy(() -> model.decide(templated(null, UPSET)))
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.INVALID_REQUEST);
    assertThat(sent.get()).isNull();
  }

  @Test
  void invalid_questions_never_reach_the_runtime() {
    var model = model(body -> Map.of());
    var duplicate = new Question.Choice(
      "intent",
      "Which?",
      List.of(new Question.Option("x", null), new Question.Option("y", null))
    );
    var oneLevel = new Question.Score("urgency", "How urgent?", List.of("only"));
    var repeatedKey = new Question.Choice(
      "intent",
      "Which?",
      List.of(new Question.Option("a", null), new Question.Option("a", null))
    );

    for (var request : List.of(
      templated("x", INTENT, duplicate),
      templated("x", oneLevel),
      templated("x", repeatedKey),
      templated("x")
    )) {
      assertThatThrownBy(() -> model.decide(request))
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(Reason.INVALID_REQUEST);
    }
    assertThat(sent.get()).isNull();
  }

  @Test
  void a_choice_outside_the_options_fails_the_request() {
    var model = model(body ->
      Map.of("answers", Map.of("intent", Map.of("type", "choice", "choice", "x")))
    );
    assertThatThrownBy(() -> model.decide(templated("x", INTENT)))
      .hasMessageContaining("questions.intent")
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.EVALUATION_FAILED);

    var missing = model(body -> Map.of("answers", Map.of("intent", Map.of("type", "choice"))));
    assertThatThrownBy(() -> missing.decide(templated("x", INTENT)))
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.EVALUATION_FAILED);
  }

  @Test
  void runtime_refusals_keep_their_blame() {
    var tooLarge = model(body -> {
      throw VllmDecisionModel.refused("request_too_large", "too big", null);
    });
    assertThatThrownBy(() -> tooLarge.decide(templated("x", UPSET)))
      .extracting(e -> ((DecisionException) e).reason())
      .isEqualTo(Reason.INVALID_REQUEST);

    var crashed = model(body -> {
      throw new IllegalStateException("device lost");
    });
    assertThatThrownBy(() -> crashed.decide(templated("x", UPSET)))
      .isExactlyInstanceOf(IllegalStateException.class)
      .hasMessage("device lost");
  }

  @Test
  void family_comes_from_the_declaration_or_the_name() {
    assertThat(VllmDecisionModel.familyOf("vllm-sr/Decision-2.0-Kai-0.6B", null)).isEqualTo(
      DecisionFamily.DECISION2
    );
    assertThat(VllmDecisionModel.familyOf("vllm-sr/Decision-1.0-Lex-0.6B", "")).isEqualTo(
      DecisionFamily.DECISION1
    );
    assertThat(VllmDecisionModel.familyOf("/models/my-package", "Decision1")).isEqualTo(
      DecisionFamily.DECISION1
    );
    assertThat(VllmDecisionModel.familyOf("vllm-sr/d3", null)).isEqualTo(DecisionFamily.DECISION3);
    assertThat(VllmDecisionModel.familyOf("vllm-sr/d3-lite", null)).isEqualTo(
      DecisionFamily.DECISION3
    );
    assertThat(VllmDecisionModel.familyOf("/models/my-package", "decision3")).isEqualTo(
      DecisionFamily.DECISION3
    );
    assertThatThrownBy(() -> VllmDecisionModel.familyOf("vllm-sr/d30", null)).hasMessageContaining(
      "set family"
    );
    assertThatThrownBy(() -> VllmDecisionModel.familyOf("/models/my-package", null))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("set family");
    assertThatThrownBy(() -> VllmDecisionModel.familyOf("x", "clef")).isInstanceOf(
      IllegalArgumentException.class
    );
  }
}
