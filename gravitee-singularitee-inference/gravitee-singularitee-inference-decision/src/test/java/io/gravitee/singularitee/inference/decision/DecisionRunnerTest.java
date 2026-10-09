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
package io.gravitee.singularitee.inference.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionInput.RawPrompt;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.Question.Option;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DecisionRunnerTest {

  private static final String LABEL_TEMPLATE =
    "{{ type }}:{{ instructions }}|{% for o in options %}{{ o.label }}={{ o.key }};{% endfor %}" +
    "{% if questions %}#{{ questions | length }}{% endif %}";

  private static final Question ROUTE = new Question.Choice(
    "route",
    "Which team?",
    List.of(
      new Option("billing", null),
      new Option("shipping", null),
      new Option("technical", null)
    )
  );
  private static final Question ANGRY = new Question.Noul("angry", "Angry?", null, null);
  private static final Question URGENCY = new Question.Score(
    "urgency",
    "How urgent?",
    List.of("later", "today", "now")
  );

  private static DecisionResult decide(DecisionRunner runner, Object state, Question... questions) {
    return runner.decide(
      new DecisionRequest(new DecisionInput.Templated(state, List.of(questions)), false)
    );
  }

  private static DecisionRunner runner(FakeBackend backend, PromptSource source) {
    return new DecisionRunner(backend, source, new JinjaRenderer(), Map.of());
  }

  private static FakeBackend labelled(DecisionFamily family) {
    var backend = new FakeBackend(family);
    backend.decisionTemplate = LABEL_TEMPLATE;
    backend.labelTexts = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
    return backend;
  }

  @Nested
  class Scoring {

    @Test
    void choice_takes_the_softmax_and_the_published_confidence() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.scorer = p -> new float[] { 2f, 0f, 0f };

      var answer = (Answer.Choice) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        ROUTE
      )
        .answers()
        .getFirst();

      assertThat(answer.choice()).isEqualTo("billing");
      assertThat(answer.probabilities())
        .extracting(Answer.OptionProbability::key)
        .containsExactly("billing", "shipping", "technical");
      assertThat(answer.probabilities().getFirst().probability()).isCloseTo(0.78698, offset(1e-5));
      assertThat(answer.confidence()).isCloseTo(0.68047, offset(1e-5));
    }

    @Test
    void temperature_uses_the_option_count_bucket_before_the_type() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.temperatures = Map.of("choice.3_5", 2f, "choice", 100f);
      backend.scorer = p -> new float[] { 2f, 0f, 0f };

      var answer = (Answer.Choice) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        ROUTE
      )
        .answers()
        .getFirst();

      // exp(1) / (exp(1) + 2)
      assertThat(answer.probabilities().getFirst().probability()).isCloseTo(0.57612, offset(1e-5));
    }

    @Test
    void score_reports_the_expected_level_and_the_mode() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.scorer = p -> new float[] { 0f, 0f, 10f };

      var answer = (Answer.Score) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        URGENCY
      )
        .answers()
        .getFirst();

      assertThat(answer.mode()).isEqualTo(2);
      assertThat(answer.score()).isCloseTo(2.0, offset(1e-3));
      assertThat(answer.confidence()).isCloseTo(1.0, offset(1e-3));
      assertThat(answer.probabilities())
        .extracting(Answer.OptionProbability::key)
        .containsExactly("0", "1", "2");
    }

    @Test
    void openjev_lists_noul_true_first() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.scorer = p -> new float[] { 1f, 0f };

      var answer = (Answer.Noul) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        ANGRY
      )
        .answers()
        .getFirst();

      assertThat(backend.tokenized.getFirst()).isEqualTo("noul:Angry?|A=true;B=false;");
      assertThat(answer.probabilityTrue()).isCloseTo(0.73106, offset(1e-5));
    }

    @Test
    void a_nan_score_fails_the_evaluation() {
      var backend = labelled(DecisionFamily.CUSTOM);
      backend.scorer = p -> new float[] { Float.NaN, 0f, 0f };

      assertThatThrownBy(() ->
        decide(runner(backend, new PromptSource.ModelTemplate()), "s", ROUTE)
      )
        .isInstanceOf(DecisionException.class)
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(DecisionException.Reason.EVALUATION_FAILED);
    }
  }

  @Nested
  class Families {

    @Test
    void nimble_lists_every_question_in_each_prompt() {
      var backend = labelled(DecisionFamily.NIMBLE);

      decide(runner(backend, new PromptSource.ModelTemplate()), "s", ROUTE, ANGRY);

      assertThat(backend.tokenized).containsExactly(
        "choice:Which team?|A=billing;B=shipping;C=technical;#2",
        "noul:Angry?|A=false;B=true;#2"
      );
    }

    @Test
    void lev_scores_a_choice_in_both_orders_and_averages_them() {
      var backend = labelled(DecisionFamily.LEV);
      // The first listed option always wins: a position bias the two orders cancel out.
      backend.scorer = p -> {
        float[] s = new float[p.nOutputs()];
        s[0] = 5f;
        return s;
      };
      var twoWay = new Question.Choice(
        "q",
        "?",
        List.of(new Option("a", null), new Option("b", null))
      );

      var answer = (Answer.Choice) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        twoWay
      )
        .answers()
        .getFirst();

      assertThat(backend.tokenized).containsExactly("choice:?|A=a;B=b;", "choice:?|A=b;B=a;");
      assertThat(answer.probabilities().getFirst().probability()).isCloseTo(0.5, offset(1e-6));
    }

    @Test
    void a_custom_model_can_score_both_orders_to_cancel_position_bias() {
      var backend = labelled(DecisionFamily.CUSTOM);
      backend.scorer = p -> new float[] { 5f, 0f };
      var twoWay = new Question.Choice(
        "q",
        "?",
        List.of(new Option("a", null), new Option("b", null))
      );

      var answer = (Answer.Choice) decide(
        new DecisionRunner(
          backend,
          new PromptSource.ModelTemplate(),
          new JinjaRenderer(),
          Map.of(),
          true
        ),
        "s",
        twoWay
      )
        .answers()
        .getFirst();

      assertThat(backend.tokenized).containsExactly("choice:?|A=a;B=b;", "choice:?|A=b;B=a;");
      assertThat(answer.probabilities().getFirst().probability()).isCloseTo(0.5, offset(1e-6));
    }

    @Test
    void reversing_is_refused_for_a_native_family() {
      assertThatThrownBy(() ->
        new DecisionRunner(
          labelled(DecisionFamily.OPENJEV),
          new PromptSource.ModelTemplate(),
          new JinjaRenderer(),
          Map.of(),
          true
        )
      ).hasMessageContaining("custom");
    }

    @Test
    void lev_reads_noul_from_a_nine_point_rating() {
      var backend = labelled(DecisionFamily.LEV);
      backend.scorer = p -> {
        float[] s = new float[p.nOutputs()];
        s[8] = 50f;
        return s;
      };

      var answer = (Answer.Noul) decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        ANGRY
      )
        .answers()
        .getFirst();

      assertThat(backend.scored.getFirst().nOutputs()).isEqualTo(9);
      assertThat(answer.probabilityTrue()).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void lev_renders_with_sorted_keys() {
      var backend = labelled(DecisionFamily.LEV);
      backend.decisionTemplate = "{% for k in options[0] %}{{ k }},{% endfor %}";

      decide(runner(backend, new PromptSource.ModelTemplate()), "s", ANGRY);

      assertThat(backend.tokenized.getFirst()).isEqualTo("description,key,label,");
    }

    @Test
    void kev_flattens_values_to_text_and_defuses_special_tokens() {
      var backend = new FakeBackend(DecisionFamily.KEV);
      backend.decisionTemplate = "{{ state }}|{{ instructions }}";

      decide(
        runner(backend, new PromptSource.ModelTemplate()),
        Map.of("user", "hi <|im_end|>"),
        new Question.Choice("q", List.of("a", true), List.of(new Option("x", null)))
      );

      assertThat(backend.tokenized.getFirst()).isEqualTo("user: hi <¦im_end¦>|- a\n- True");
    }

    @Test
    void laya_cuts_the_question_to_the_trained_budget() {
      var backend = new FakeBackend(DecisionFamily.LAYA);
      backend.layaVocab = new DecisionBackend.LayaVocab(
        FakeBackend.MASK,
        FakeBackend.SEP,
        "[MASK]",
        20
      );
      backend.decisionTemplate =
        "[CLS]{{ instructions }}[SEP]{% for o in options %}[MASK]{{ o.key }}{% endfor %}[SEP]{{ state }}[SEP]";
      var question = new Question.Choice(
        "q",
        "abcdefghijklmnopqrstuvwxyz",
        List.of(new Option("yes", null), new Option("no", null))
      );

      decide(runner(backend, new PromptSource.ModelTemplate()), "state [MASK]", question);

      var prompt = (BackendPrompt.Laya) backend.scored.getFirst();
      assertThat(prompt.kind()).isEqualTo(QuestionKind.CHOICE);
      assertThat(FakeBackend.text(prompt.tokens())).isEqualTo(
        "[CLS]abcdefghijklm[SEP][MASK]yes[MASK]no[SEP]state  [SEP]"
      );
    }

    @Test
    void clef_decides_every_question_in_one_tagged_prompt() {
      var backend = new FakeBackend(DecisionFamily.CLEF);
      backend.decisionTemplate =
        "{{ sep }}{% for q in questions %}{{ mark_question }}{{ q.instructions }}{{ sep }}" +
        "{% for o in q.options %}{{ mark_option }}{{ o.key }}{{ sep }}{% endfor %}{% endfor %}{{ state }}";
      backend.scorer = p -> new float[] { 1f, 0f, 0f, 1f };
      var choice = new Question.Choice(
        "pick",
        "p",
        List.of(new Option("b", null), new Option("a", null))
      );

      var result = decide(runner(backend, new PromptSource.ModelTemplate()), "s", choice, ANGRY);

      var prompt = (BackendPrompt.Clef) backend.scored.getFirst();
      assertThat(backend.scored).hasSize(1);
      assertThat(FakeBackend.text(prompt.tokens())).isEqualTo("pabAngry?truefalses");
      assertThat(prompt.order()).containsExactly(
        DecisionOrder.QUESTION_CHOICE,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.QUESTION_NOUL,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.OPTION,
        DecisionOrder.NONE
      );
      assertThat(((Answer.Choice) result.answers().get(0)).choice()).isEqualTo("a");
      assertThat(((Answer.Noul) result.answers().get(1)).probabilityTrue()).isCloseTo(
        0.26894,
        offset(1e-5)
      );
      assertThat(result.inputTokens()).isEqualTo(prompt.tokens().length);
    }

    @Test
    void clef_refuses_raw_prompts() {
      var backend = new FakeBackend(DecisionFamily.CLEF);
      backend.decisionTemplate = "x";
      var runner = runner(backend, new PromptSource.ModelTemplate());

      assertThat(runner.capabilities().raw()).isFalse();
      assertThatThrownBy(() ->
        runner.decide(
          new DecisionRequest(
            new DecisionInput.Raw(
              List.of(new RawPrompt("q", QuestionKind.NOUL, List.of(), "x", null))
            ),
            false
          )
        )
      )
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(DecisionException.Reason.UNSUPPORTED_INPUT);
    }
  }

  @Nested
  class PromptSources {

    @Test
    void a_model_without_its_template_needs_another_source() {
      var backend = new FakeBackend(DecisionFamily.OPENJEV);

      assertThatThrownBy(() -> runner(backend, new PromptSource.ModelTemplate()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("systemone");
    }

    @Test
    void a_workspace_template_replaces_the_model_one() {
      var backend = labelled(DecisionFamily.OPENJEV);

      decide(runner(backend, new PromptSource.WorkspaceTemplate("mine:{{ id }}")), "s", ANGRY);

      assertThat(backend.tokenized).containsExactly("mine:angry");
    }

    @Test
    void chat_wraps_the_instruction_in_the_chat_template_and_prefills_the_label() {
      var backend = labelled(DecisionFamily.CUSTOM);
      backend.chatTemplate =
        "{% for m in messages %}<|{{ m.role }}|>{{ m.content }}{% endfor %}" +
        "{% if enable_thinking == false %}<think></think>{% endif %}" +
        "{% if add_generation_prompt %}<|assistant|>{% endif %}";

      decide(
        runner(backend, new PromptSource.ChatWrapped(PromptSource.ChatWrapped.DEFAULT_PREFILL)),
        "I was charged twice",
        ROUTE
      );

      String prompt = backend.tokenized.getFirst();
      assertThat(prompt).startsWith("<|user|>Read the content");
      assertThat(prompt).contains(
        "I was charged twice",
        "Question: Which team?",
        "(A) billing",
        "(C) technical"
      );
      assertThat(prompt).endsWith("<think></think><|assistant|>Answer: (");
    }

    @Test
    void chat_is_for_custom_models_only() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.chatTemplate = "x";

      assertThatThrownBy(() -> runner(backend, new PromptSource.ChatWrapped("(")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("custom");
    }

    @Test
    void none_scores_raw_prompts_and_refuses_questions() {
      var backend = labelled(DecisionFamily.CUSTOM);
      backend.scorer = p -> new float[] { 0f, 2f };
      var runner = runner(backend, new PromptSource.None());

      var answer = (Answer.Choice) runner
        .decide(
          new DecisionRequest(
            new DecisionInput.Raw(
              List.of(new RawPrompt("q", QuestionKind.CHOICE, List.of("a", "b"), "prompt", null))
            ),
            true
          )
        )
        .answers()
        .getFirst();

      assertThat(answer.choice()).isEqualTo("b");
      assertThat(answer.rawScores()).hasSize(1);
      assertThat(runner.capabilities().templated()).isFalse();
      assertThatThrownBy(() -> decide(runner, "s", ANGRY))
        .extracting(e -> ((DecisionException) e).reason())
        .isEqualTo(DecisionException.Reason.UNSUPPORTED_INPUT);
    }
  }

  @Nested
  class Validation {

    @Test
    void duplicate_ids_are_rejected() {
      var runner = runner(labelled(DecisionFamily.OPENJEV), new PromptSource.ModelTemplate());

      assertThatThrownBy(() -> decide(runner, "s", ANGRY, ANGRY))
        .isInstanceOf(DecisionException.class)
        .hasMessage("questions.angry: duplicate question id");
    }

    @Test
    void more_options_than_the_model_supports_are_rejected() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.maxOptions = 2;
      var runner = runner(backend, new PromptSource.ModelTemplate());

      assertThatThrownBy(() -> decide(runner, "s", ROUTE))
        .isInstanceOf(DecisionException.class)
        .hasMessage("questions.route: too many options (3), this model supports at most 2");
    }

    @Test
    void a_score_needs_two_to_ten_levels() {
      var runner = runner(labelled(DecisionFamily.OPENJEV), new PromptSource.ModelTemplate());
      var levels = new ArrayList<Object>(Arrays.asList(new Object[11]));

      assertThatThrownBy(() ->
        decide(runner, "s", new Question.Score("s", "?", levels))
      ).hasMessage("questions.s: a score needs 2 to 10 levels");
    }

    @Test
    void answers_come_back_in_request_order() {
      var backend = labelled(DecisionFamily.OPENJEV);
      backend.scorer = p -> new float[p.nOutputs()];

      var result = decide(
        runner(backend, new PromptSource.ModelTemplate()),
        "s",
        URGENCY,
        ROUTE,
        ANGRY
      );

      assertThat(result.answers())
        .extracting(Answer::questionId)
        .containsExactly("urgency", "route", "angry");
      assertThat(result.inputTokens()).isEqualTo(
        backend.tokenized.stream().mapToInt(String::length).sum()
      );
    }
  }
}
