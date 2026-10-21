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

import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.OutputFieldConfig;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import java.util.List;

/**
 * Runtime config of a {@code decide} step. Exactly one source of questions is set: the
 * workspace's {@link #questions}, caller-rendered {@link #prompts} built from templates, or the
 * caller's own questions ({@link #questionsFromRequest}).
 *
 * @param modelId              id of the decision model
 * @param inputField           context key holding the state; {@code messages} passes the chat
 *                             messages; blank = the prompt
 * @param outputField          prefix of the context keys written; blank = the step id
 * @param questionsFromRequest whether the questions come from the decision call that targets the
 *                             pipeline
 * @param questions            the questions the model renders prompts for
 * @param prompts              prompts the step renders itself, for a model without a template
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record DecideStepConfig(
  String modelId,
  String inputField,
  String outputField,
  boolean questionsFromRequest,
  List<Question> questions,
  List<PromptDef> prompts
) implements ModelBoundConfig, OutputFieldConfig {
  public DecideStepConfig {
    modelId = modelId == null ? "" : modelId;
    inputField = inputField == null ? "" : inputField;
    outputField = outputField == null ? "" : outputField;
    questions = questions == null ? List.of() : List.copyOf(questions);
    prompts = prompts == null ? List.of() : List.copyOf(prompts);
  }

  /**
   * A prompt the step renders with the pipeline context, for a model that scores caller-rendered
   * prompts only.
   *
   * @param id         the answer's id
   * @param kind       the shape of the answer
   * @param optionKeys the option keys in prompt order (choice), {@code "0".."n-1"} (score), empty
   *                   (noul)
   * @param template   the Jinja source
   */
  public record PromptDef(String id, QuestionKind kind, List<String> optionKeys, String template) {
    public PromptDef {
      optionKeys = List.copyOf(optionKeys);
    }
  }
}
