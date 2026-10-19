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
package io.gravitee.singularitee.inference.api.decision;

import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.Question.Option;
import java.util.HashSet;
import java.util.List;

/**
 * The checks every decision model applies to the questions of a request, whatever scores them:
 * ids, instructions, option keys and the option count each question type allows.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionQuestions {

  /** Most levels a score question may have. */
  public static final int MAX_SCORE_LEVELS = 10;

  private DecisionQuestions() {}

  /**
   * Checks the questions of a templated request.
   *
   * @throws DecisionException {@code INVALID_REQUEST} naming the first faulty question
   */
  public static void validate(List<Question> questions) {
    if (questions.isEmpty()) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"questions\" must not be empty");
    }
    var ids = new HashSet<String>();
    for (Question question : questions) {
      String id = question.id();
      if (id == null || id.isBlank()) {
        throw new DecisionException(Reason.INVALID_REQUEST, "question ids must not be blank");
      }
      if (!ids.add(id)) throw DecisionException.invalid(id, "duplicate question id");
      if (question.instructions() == null) {
        throw DecisionException.invalid(id, "\"instructions\" must be provided");
      }
      if (question instanceof Question.Choice choice) {
        var keys = new HashSet<String>();
        for (Option option : choice.options()) {
          if (option.key() == null || option.key().isEmpty() || !keys.add(option.key())) {
            throw DecisionException.invalid(id, "option keys must be unique and not empty");
          }
        }
      }
      checkCount(id, question.kind(), question.options().size());
    }
  }

  /**
   * Checks the option count a question type allows: at least one choice option, 2 to
   * {@value #MAX_SCORE_LEVELS} score levels.
   *
   * @throws DecisionException {@code INVALID_REQUEST} when the count is out of range
   */
  public static void checkCount(String id, QuestionKind kind, int n) {
    switch (kind) {
      case CHOICE -> {
        if (n < 1) throw DecisionException.invalid(id, "a choice needs at least one option");
      }
      case SCORE -> {
        if (n < 2 || n > MAX_SCORE_LEVELS) {
          throw DecisionException.invalid(id, "a score needs 2 to 10 levels");
        }
      }
      case NOUL -> {}
    }
  }
}
