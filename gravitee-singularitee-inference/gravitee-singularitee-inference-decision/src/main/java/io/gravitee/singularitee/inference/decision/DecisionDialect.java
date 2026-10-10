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

import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.Question.Option;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a decision family does differently: option order, scoring variants, outputs, the variables
 * its template was trained with, and the prompt layout its backend reads. One strategy per family;
 * the flow around them lives in {@link DecisionRunner}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
sealed interface DecisionDialect
  permits LabelDialect, LevDialect, KevDialect, LayaDialect, ClefDialect {
  /**
   * The strategy of {@code family}.
   *
   * @param reverse custom models: also score each choice with its options reversed, and average
   */
  static DecisionDialect of(DecisionFamily family, boolean reverse) {
    return switch (family) {
      case OPENJEV, NIMBLE, CUSTOM -> new LabelDialect(
        family,
        reverse && family == DecisionFamily.CUSTOM
      );
      case LEV -> new LevDialect();
      case KEV -> new KevDialect();
      case LAYA -> new LayaDialect();
      case CLEF -> new ClefDialect();
      case DECISION1, DECISION2, GLINER -> throw new IllegalArgumentException(
        family.wireName() + " models are rendered and scored by their own runtime"
      );
    };
  }

  /** Whether a noul lists {@code true} before {@code false}. */
  default boolean noulTrueFirst() {
    return false;
  }

  /** The options of {@code question} in the order its prompt lists them (variant 0). */
  default List<Option> promptOptions(Question question) {
    List<Option> options = question.options();
    if (question.kind() == QuestionKind.NOUL && noulTrueFirst()) {
      return List.of(options.get(1), options.get(0));
    }
    return options;
  }

  /** How many prompts score one question; variant 1 lists the options in reverse order. */
  default int variants(QuestionKind kind, int nOptions) {
    return 1;
  }

  /** How many scores one prompt of a question returns. */
  default int outputs(QuestionKind kind, int nOptions) {
    return nOptions;
  }

  /** Whether a noul is read from a rating scale instead of a true / false pair. */
  default boolean noulRating() {
    return false;
  }

  /**
   * The template variables of one prompt: {@code id}, {@code type}, {@code instructions},
   * {@code state}, {@code options} (each with {@code key}, {@code description} and, when the
   * family shows codes, {@code label}) and {@code images}.
   */
  default Map<String, Object> variables(
    Object state,
    List<Question> questions,
    Question question,
    int variant,
    DecisionBackend backend
  ) {
    var vars = new LinkedHashMap<String, Object>();
    vars.put("id", question.id());
    vars.put("type", question.kind().wireName());
    vars.put("instructions", question.instructions());
    vars.put("state", state);
    vars.put("options", options(question, variant, backend.labelTexts()));
    vars.put("images", List.of());
    return vars;
  }

  /** The {@code options} variable of one prompt. */
  default List<Map<String, Object>> options(
    Question question,
    int variant,
    List<String> labelTexts
  ) {
    List<Option> options = promptOptions(question);
    int n = options.size();
    var out = new ArrayList<Map<String, Object>>(n);
    for (int i = 0; i < n; i++) {
      Option option = options.get(variant == 0 ? i : n - 1 - i);
      var map = new LinkedHashMap<String, Object>();
      map.put("key", option.key());
      map.put("description", option.description());
      if (!labelTexts.isEmpty()) map.put("label", labelTexts.get(i));
      out.add(map);
    }
    return out;
  }

  /** The backend prompt of one rendered and tokenized prompt. */
  default BackendPrompt prompt(
    int[] tokens,
    QuestionKind kind,
    int nOutputs,
    DecisionBackend backend
  ) {
    return new BackendPrompt.Text(tokens, nOutputs);
  }
}
