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

import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.gravitee.singularitee.plugin.decide.DecideStepConfig.PromptDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code config:} block of a {@code decide} step.
 *
 * <pre>{@code
 * config:
 *   model_id: decider
 *   input_field: prompt                 # or messages
 *   questions:                          # or: questions: request
 *     team:    { instructions: Which team?, choice: { billing: null, shipping: null } }
 *     angry:   { instructions: Is the customer angry?, noul: {} }
 *     urgency: { instructions: How urgent?, score: [later, today, now] }
 *   # or, for a model without a template:
 *   prompts:
 *     team: { choice: [billing, shipping], template: my-judge-prompt }
 * }</pre>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecideStepCodec implements StepConfigCodec<DecideStepConfig> {

  /** The {@code questions:} value that takes the caller's questions. */
  static final String FROM_REQUEST = "request";

  @Override
  public DecideStepConfig parse(String stepId, Map<String, Object> raw, StepCodecContext ctx) {
    String modelId = string(raw.get("model_id"));
    if (modelId.isBlank()) throw invalid(stepId, "model_id is required");
    Object questions = raw.get("questions");
    Object prompts = raw.get("prompts");
    if ((questions == null) == (prompts == null)) {
      throw invalid(stepId, "set exactly one of questions and prompts");
    }
    boolean fromRequest = FROM_REQUEST.equals(questions);
    return new DecideStepConfig(
      modelId,
      string(raw.get("input_field")),
      string(raw.get("output_field")),
      fromRequest,
      questions == null || fromRequest ? List.of() : questions(stepId, questions),
      prompts == null ? List.of() : prompts(stepId, prompts, ctx)
    );
  }

  private static List<Question> questions(String stepId, Object value) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
      throw invalid(stepId, "questions must be a non-empty map of id to question, or 'request'");
    }
    var out = new ArrayList<Question>(map.size());
    map.forEach((id, q) -> out.add(question(stepId, String.valueOf(id), q)));
    return out;
  }

  private static Question question(String stepId, String id, Object value) {
    if (!(value instanceof Map<?, ?> q)) throw invalid(
      stepId,
      "questions." + id + " must be a map"
    );
    Object instructions = q.get("instructions");
    if (instructions == null) throw invalid(
      stepId,
      "questions." + id + ".instructions is required"
    );
    QuestionKind kind = kind(stepId, "questions." + id, q);
    return switch (kind) {
      case CHOICE -> new Question.Choice(
        id,
        instructions,
        choiceOptions(stepId, id, q.get("choice"))
      );
      case SCORE -> {
        if (
          !(q.get("score") instanceof List<?> levels) || levels.size() < 2 || levels.size() > 10
        ) {
          throw invalid(stepId, "questions." + id + ".score must list 2 to 10 levels");
        }
        yield new Question.Score(id, instructions, new ArrayList<>(levels));
      }
      case NOUL -> {
        Map<?, ?> noul = q.get("noul") instanceof Map<?, ?> m ? m : Map.of();
        yield new Question.Noul(id, instructions, noul.get("true"), noul.get("false"));
      }
    };
  }

  private static List<Question.Option> choiceOptions(String stepId, String id, Object value) {
    var out = new ArrayList<Question.Option>();
    switch (value) {
      case Map<?, ?> map -> map.forEach((k, d) ->
        out.add(new Question.Option(String.valueOf(k), d))
      );
      case List<?> list -> list.forEach(k -> out.add(new Question.Option(String.valueOf(k), null)));
      case null, default -> {}
    }
    if (out.isEmpty()) {
      throw invalid(stepId, "questions." + id + ".choice must list at least one option");
    }
    return out;
  }

  private static List<PromptDef> prompts(String stepId, Object value, StepCodecContext ctx) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
      throw invalid(stepId, "prompts must be a non-empty map of id to prompt");
    }
    var out = new ArrayList<PromptDef>(map.size());
    map.forEach((key, v) -> {
      String id = String.valueOf(key);
      if (!(v instanceof Map<?, ?> p)) throw invalid(stepId, "prompts." + id + " must be a map");
      String template = string(p.get("template"));
      if (template.isBlank()) throw invalid(stepId, "prompts." + id + ".template is required");
      QuestionKind kind = kind(stepId, "prompts." + id, p);
      List<String> keys = switch (kind) {
        case CHOICE -> p.get("choice") instanceof List<?> l
          ? l.stream().map(String::valueOf).toList()
          : List.of();
        case SCORE -> {
          int levels = p.get("score") instanceof Number n ? n.intValue() : 0;
          var k = new ArrayList<String>();
          for (int i = 0; i < levels; i++) k.add(String.valueOf(i));
          yield k;
        }
        case NOUL -> List.of();
      };
      out.add(new PromptDef(id, kind, keys, ctx.templates().getOrDefault(template, template)));
    });
    return out;
  }

  private static QuestionKind kind(String stepId, String path, Map<?, ?> q) {
    var kinds = new ArrayList<QuestionKind>();
    for (QuestionKind k : QuestionKind.values()) if (q.containsKey(k.wireName())) kinds.add(k);
    if (kinds.size() != 1) {
      throw invalid(stepId, path + " must set exactly one of choice, score, noul");
    }
    return kinds.getFirst();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static IllegalArgumentException invalid(String stepId, String message) {
    return new IllegalArgumentException("Step '" + stepId + "': invalid decide config: " + message);
  }
}
