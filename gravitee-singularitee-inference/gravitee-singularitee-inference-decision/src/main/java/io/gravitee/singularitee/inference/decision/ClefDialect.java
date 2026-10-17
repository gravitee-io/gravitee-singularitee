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

import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.Question.Option;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * clef: a joint head that decides every question of a request in one prompt. The template marks
 * where each piece starts; each piece is tokenized on its own, as in training, and each token is
 * tagged with the role of its piece. Choice options are sorted by key and noul answers listed
 * true first.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
record ClefDialect() implements DecisionDialect {
  static final String MARKER = "<<clef:";
  static final String SEP = "<<clef:sep>>";
  static final String MARK_QUESTION = "<<clef:question>>";
  static final String MARK_OPTION = "<<clef:option>>";

  @Override
  public boolean noulTrueFirst() {
    return true;
  }

  @Override
  public List<Option> promptOptions(Question question) {
    if (question.kind() == QuestionKind.CHOICE) {
      return question.options().stream().sorted(Comparator.comparing(Option::key)).toList();
    }
    return DecisionDialect.super.promptOptions(question);
  }

  /** The template variables of the joint prompt. */
  @SuppressWarnings("unchecked")
  Map<String, Object> jointVariables(Object state, List<Question> questions) {
    var inQuestions = new ArrayList<Map<String, Object>>(questions.size());
    for (Question q : questions) {
      var options = new ArrayList<Map<String, Object>>();
      for (Option option : promptOptions(q)) {
        var map = new LinkedHashMap<String, Object>();
        map.put("key", option.key());
        map.put("description", option.description());
        options.add(map);
      }
      var map = new LinkedHashMap<String, Object>();
      map.put("id", q.id());
      map.put("type", q.kind().wireName());
      map.put("instructions", q.instructions());
      map.put("options", options);
      inQuestions.add(map);
    }
    var vars = new LinkedHashMap<String, Object>();
    vars.put("state", state);
    vars.put("questions", inQuestions);
    var out = (Map<String, Object>) TemplateValues.replaceText(
      TemplateValues.sortKeys(vars),
      MARKER,
      "<<clef "
    );
    out.put("images", List.of());
    out.put("sep", SEP);
    out.put("mark_question", MARK_QUESTION);
    out.put("mark_option", MARK_OPTION);
    return out;
  }

  /** Splits the rendered joint prompt into tagged tokens. */
  BackendPrompt.Clef jointPrompt(
    String rendered,
    List<Question> questions,
    Function<String, int[]> tokenize
  ) {
    var tokens = new IntList(rendered.length() / 3);
    var order = new ArrayList<DecisionOrder>();
    int nQuestion = 0;
    int nScores = 0;
    for (String piece : rendered.split(Pattern.quote(SEP), -1)) {
      DecisionOrder role = DecisionOrder.NONE;
      if (piece.startsWith(MARK_QUESTION)) {
        piece = piece.substring(MARK_QUESTION.length());
        if (nQuestion >= questions.size()) throw invalidLayout();
        role = switch (questions.get(nQuestion++).kind()) {
          case NOUL -> DecisionOrder.QUESTION_NOUL;
          case CHOICE -> DecisionOrder.QUESTION_CHOICE;
          case SCORE -> DecisionOrder.QUESTION_SCORE;
        };
      } else if (piece.startsWith(MARK_OPTION)) {
        piece = piece.substring(MARK_OPTION.length());
        role = DecisionOrder.OPTION;
        nScores++;
      }
      int[] pieceTokens = piece.isEmpty() ? new int[0] : tokenize.apply(piece);
      if (role != DecisionOrder.NONE && pieceTokens.length == 0) {
        throw new DecisionException(
          DecisionException.Reason.INVALID_REQUEST,
          "the instructions and the options of a question must not be empty"
        );
      }
      tokens.addRange(pieceTokens, 0, pieceTokens.length);
      for (int i = 0; i < pieceTokens.length; i++) order.add(role);
    }
    int nOptions = questions
      .stream()
      .mapToInt(q -> q.options().size())
      .sum();
    if (nQuestion != questions.size() || nScores != nOptions) throw invalidLayout();
    return new BackendPrompt.Clef(tokens.toArray(), order.toArray(DecisionOrder[]::new), nScores);
  }

  private static IllegalStateException invalidLayout() {
    return new IllegalStateException("unexpected layout of the decision prompt");
  }
}
