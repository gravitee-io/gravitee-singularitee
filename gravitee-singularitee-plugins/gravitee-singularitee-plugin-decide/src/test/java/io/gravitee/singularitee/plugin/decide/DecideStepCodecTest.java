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

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecideStepCodecTest {

  private final DecideStepCodec codec = new DecideStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(
    Map.of("judge-route", "Route {{ prompt }}: Answer: ("),
    null,
    null,
    null
  );

  @Test
  void parses_questions_in_declaration_order() {
    var questions = new LinkedHashMap<String, Object>();
    var team = new LinkedHashMap<String, Object>();
    team.put("billing", null);
    team.put("shipping", "parcels, delivery");
    questions.put("team", Map.of("instructions", "Which team?", "choice", team));
    questions.put("angry", Map.of("instructions", "Angry?", "noul", Map.of("true", "shouting")));
    questions.put("urgency", Map.of("instructions", "Urgent?", "score", List.of("low", "high")));

    var cfg = codec.parse("triage", Map.of("model_id", "decider", "questions", questions), ctx);

    assertThat(cfg.modelId()).isEqualTo("decider");
    assertThat(cfg.questionsFromRequest()).isFalse();
    assertThat(cfg.questions())
      .extracting(Question::id)
      .containsExactly("team", "angry", "urgency");
    var choice = (Question.Choice) cfg.questions().getFirst();
    assertThat(choice.options())
      .extracting(Question.Option::key)
      .containsExactly("billing", "shipping");
    assertThat(choice.options().get(1).description()).isEqualTo("parcels, delivery");
    assertThat(((Question.Noul) cfg.questions().get(1)).whenTrue()).isEqualTo("shouting");
    assertThat(((Question.Score) cfg.questions().get(2)).levels()).containsExactly("low", "high");
  }

  @Test
  void a_choice_may_list_bare_keys() {
    var cfg = codec.parse(
      "triage",
      Map.of(
        "model_id",
        "decider",
        "questions",
        Map.of("team", Map.of("instructions", "?", "choice", List.of("a", "b")))
      ),
      ctx
    );

    assertThat(cfg.questions().getFirst().options())
      .extracting(Question.Option::key)
      .containsExactly("a", "b");
  }

  @Test
  void request_takes_the_callers_questions() {
    var cfg = codec.parse("triage", Map.of("model_id", "decider", "questions", "request"), ctx);

    assertThat(cfg.questionsFromRequest()).isTrue();
    assertThat(cfg.questions()).isEmpty();
  }

  @Test
  void prompts_resolve_their_template_by_id() {
    var cfg = codec.parse(
      "judge",
      Map.of(
        "model_id",
        "raw",
        "prompts",
        Map.of("route", Map.of("choice", List.of("billing", "shipping"), "template", "judge-route"))
      ),
      ctx
    );

    var prompt = cfg.prompts().getFirst();
    assertThat(prompt.kind()).isEqualTo(QuestionKind.CHOICE);
    assertThat(prompt.optionKeys()).containsExactly("billing", "shipping");
    assertThat(prompt.template()).isEqualTo("Route {{ prompt }}: Answer: (");
  }

  @Test
  void a_score_prompt_names_its_levels() {
    var cfg = codec.parse(
      "judge",
      Map.of(
        "model_id",
        "raw",
        "prompts",
        Map.of("u", Map.of("score", 3, "template", "Urgency of {{ prompt }}: "))
      ),
      ctx
    );

    assertThat(cfg.prompts().getFirst().optionKeys()).containsExactly("0", "1", "2");
    assertThat(cfg.prompts().getFirst().template()).isEqualTo("Urgency of {{ prompt }}: ");
  }

  @Test
  void a_prompt_with_the_questions_syntax_or_a_misspelt_template_is_refused_at_load() {
    record Case(Map<String, Object> prompt, String error) {}
    for (var c : List.of(
      new Case(
        Map.of("choice", Map.of("billing", "money"), "template", "judge-route"),
        "prompts.p.choice must list the option keys"
      ),
      new Case(
        Map.of("choice", List.of("a", "a"), "template", "judge-route"),
        "prompts.p.choice keys must be unique"
      ),
      new Case(
        Map.of("score", List.of("low", "high"), "template", "judge-route"),
        "prompts.p.score must be a number of levels"
      ),
      new Case(Map.of("score", 11, "template", "judge-route"), "2 to 10"),
      new Case(
        Map.of("noul", Map.of(), "template", "judge-rout"),
        "prompts.p.template names no template: 'judge-rout'"
      )
    )) {
      assertThatThrownBy(() ->
        codec.parse("judge", Map.of("model_id", "raw", "prompts", Map.of("p", c.prompt())), ctx)
      ).hasMessageContaining(c.error());
    }
  }

  @Test
  void questions_and_prompts_are_exclusive() {
    assertThatThrownBy(() ->
      codec.parse("triage", Map.of("model_id", "m"), ctx)
    ).hasMessageContaining("exactly one of questions and prompts");
  }

  @Test
  void a_question_needs_exactly_one_type() {
    assertThatThrownBy(() ->
      codec.parse(
        "triage",
        Map.of(
          "model_id",
          "m",
          "questions",
          Map.of("q", Map.of("instructions", "?", "noul", Map.of(), "choice", List.of("a")))
        ),
        ctx
      )
    ).hasMessageContaining("questions.q must set exactly one of choice, score, noul");
  }

  @Test
  void a_score_needs_two_to_ten_levels() {
    assertThatThrownBy(() ->
      codec.parse(
        "triage",
        Map.of(
          "model_id",
          "m",
          "questions",
          Map.of("q", Map.of("instructions", "?", "score", List.of("x")))
        ),
        ctx
      )
    ).hasMessageContaining("2 to 10 levels");
  }

  @Test
  void model_id_is_required() {
    assertThatThrownBy(() -> codec.parse("triage", Map.of("questions", "request"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'triage'")
      .hasMessageContaining("model_id is required");
  }
}
