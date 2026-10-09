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
import java.util.List;
import java.util.Map;

/**
 * kev: the hidden state of the last token against one end marker per option. Its template takes
 * text only, so the state, the instructions and the options are flattened to text first.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
record KevDialect() implements DecisionDialect {
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
    vars.put("state", TemplateValues.kevText(state));
    vars.put("instructions", TemplateValues.kevText(question.instructions()));
    return vars;
  }

  @Override
  public List<Map<String, Object>> options(
    Question question,
    int variant,
    List<String> labelTexts
  ) {
    List<Map<String, Object>> options = DecisionDialect.super.options(
      question,
      variant,
      labelTexts
    );
    for (Map<String, Object> option : options) {
      option.put("key", TemplateValues.kevText(option.get("key")));
      if (option.get("description") != null) {
        option.put("description", TemplateValues.kevText(option.get("description")));
      }
    }
    return options;
  }
}
