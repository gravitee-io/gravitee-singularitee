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
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * openjev, nimble and custom models: one label token per option, read at the last prompt token.
 * openjev lists noul answers true first; nimble shows every question of the request in each
 * prompt. A custom model can score each choice in both option orders, as lev does, to cancel
 * its preference for the first label.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
record LabelDialect(DecisionFamily family, boolean reverse) implements DecisionDialect {
  @Override
  public int variants(QuestionKind kind, int nOptions) {
    return reverse && kind == QuestionKind.CHOICE && nOptions > 1 ? 2 : 1;
  }

  @Override
  public boolean noulTrueFirst() {
    return family == DecisionFamily.OPENJEV;
  }

  @Override
  public Map<String, Object> variables(
    Object state,
    List<Question> questions,
    Question question,
    int variant,
    DecisionBackend backend
  ) {
    Map<String, Object> vars = DecisionDialect.super.variables(
      state,
      questions,
      question,
      variant,
      backend
    );
    if (family == DecisionFamily.NIMBLE) {
      var all = new ArrayList<Map<String, Object>>(questions.size());
      for (Question q : questions) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", q.id());
        map.put("type", q.kind().wireName());
        map.put("instructions", q.instructions());
        map.put("options", options(q, 0, backend.labelTexts()));
        all.add(map);
      }
      vars.put("questions", all);
    }
    return vars;
  }
}
