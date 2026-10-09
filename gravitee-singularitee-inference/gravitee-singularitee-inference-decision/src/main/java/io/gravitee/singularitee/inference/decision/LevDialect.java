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

import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.List;
import java.util.Map;

/**
 * lev: label tokens like openjev, trained with sorted keys. A choice is scored with its options in
 * both orders to cancel the preference for the first label, and a noul is read from a 9-point
 * rating (0 certainly no, 8 certainly yes).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
record LevDialect() implements DecisionDialect {
  static final int RATINGS = 9;

  @Override
  public int variants(QuestionKind kind, int nOptions) {
    return kind == QuestionKind.CHOICE && nOptions > 1 ? 2 : 1;
  }

  @Override
  public int outputs(QuestionKind kind, int nOptions) {
    return kind == QuestionKind.NOUL ? RATINGS : nOptions;
  }

  @Override
  public boolean noulRating() {
    return true;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> variables(
    Object state,
    List<Question> questions,
    Question question,
    int variant,
    DecisionBackend backend
  ) {
    return (Map<String, Object>) TemplateValues.sortKeys(
      DecisionDialect.super.variables(state, questions, question, variant, backend)
    );
  }
}
