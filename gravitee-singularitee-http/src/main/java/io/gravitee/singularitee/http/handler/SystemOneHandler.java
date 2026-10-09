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
package io.gravitee.singularitee.http.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.http.json.JsonResponses;
import io.gravitee.singularitee.http.json.Utils;
import io.gravitee.singularitee.http.validation.SchemaName;
import io.gravitee.singularitee.protocol.ChoiceQuestion;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.DecisionAnswer;
import io.gravitee.singularitee.protocol.DecisionOption;
import io.gravitee.singularitee.protocol.DecisionQuestion;
import io.gravitee.singularitee.protocol.NoulQuestion;
import io.gravitee.singularitee.protocol.OptionProbability;
import io.gravitee.singularitee.protocol.RawScores;
import io.gravitee.singularitee.protocol.ScoreQuestion;
import io.gravitee.singularitee.protocol.TemplatedInput;
import io.gravitee.singularitee.service.GraviteeDecisionServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/systemone}: the decision route in the shape of TypeSafe's System One API, as
 * llama-server also serves it, so a client written for it works here. {@code model} names the
 * decision model; questions are an object keyed by id, answers likewise. Validation errors use
 * this API's {@code 400} error object rather than TypeSafe's {@code 422}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SystemOneHandler implements Handler<RoutingContext> {

  private static final int MAX_LEVELS = 10;

  private final GraviteeDecisionServiceImpl decisions;
  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;

  public SystemOneHandler(
    GraviteeDecisionServiceImpl decisions,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry
  ) {
    this.decisions = decisions;
    this.modelRegistry = modelRegistry;
    this.pipelineRegistry = pipelineRegistry;
  }

  @Override
  public void handle(RoutingContext rc) {
    JsonNode payload = HandlerSupport.parseOrFail(rc);
    if (payload == null) return;
    String model = HandlerSupport.requireModel(rc, payload, modelRegistry);
    if (model == null) return;
    if (!HandlerSupport.validate(rc, payload, SchemaName.SYSTEMONE)) return;

    JsonNode state = payload.path("state");
    if (state.isMissingNode() || state.isNull()) {
      HandlerSupport.badRequest(rc, "\"state\" must be provided", "state");
      return;
    }
    JsonNode videos = payload.path("videos");
    if (videos.isArray() && !videos.isEmpty()) {
      HandlerSupport.badRequest(rc, "\"videos\" is not supported", "videos");
      return;
    }
    JsonNode images = payload.path("images");
    if ((images.isArray() && !images.isEmpty()) || DecisionSupport.hasImagePart(state)) {
      DecisionSupport.unsupportedModality(rc, DecisionSupport.NO_IMAGES);
      return;
    }
    JsonNode questions = payload.path("questions");
    if (!questions.isObject() || questions.isEmpty()) {
      HandlerSupport.badRequest(rc, "\"questions\" must be a non-empty object", "questions");
      return;
    }

    var input = TemplatedInput.newBuilder();
    if (state.isTextual()) input.setText(state.asText());
    else input.setJson(DecisionSupport.value(state));

    Map<String, JsonNode> levels = new HashMap<>();
    for (var field : questions.properties()) {
      String id = field.getKey();
      String error = question(id, field.getValue(), input, levels);
      if (error != null) {
        HandlerSupport.badRequest(rc, "questions." + id + ": " + error, "questions");
        return;
      }
    }

    var request = DecideRequest.newBuilder()
      .setTemplated(input)
      .setIncludeRawScores(payload.path("include_raw_scores").asBoolean(false));
    if (!DecisionSupport.target(rc, model, modelRegistry, pipelineRegistry, request)) return;
    decisions
      .execute(request.build())
      .onSuccess(resp -> JsonResponses.writeJson(rc, render(model, resp, levels)))
      .onFailure(err -> DecisionSupport.fail(rc, err));
  }

  /** Adds one question to {@code input}; returns the error message, or {@code null}. */
  private static String question(
    String id,
    JsonNode q,
    TemplatedInput.Builder input,
    Map<String, JsonNode> levels
  ) {
    if (!q.isObject()) return "must be an object";
    JsonNode instructions = q.path("instructions");
    if (instructions.isMissingNode() || instructions.isNull()) {
      return "\"instructions\" must be provided";
    }
    var question = DecisionQuestion.newBuilder()
      .setId(id)
      .setInstructions(DecisionSupport.value(instructions));
    JsonNode criteria = q.path("criteria");
    switch (q.path("type").asText("")) {
      case "choice" -> {
        if (!criteria.isObject() || criteria.isEmpty()) {
          return "\"criteria\" must be a non-empty object";
        }
        var choice = ChoiceQuestion.newBuilder();
        for (var option : criteria.properties()) {
          var o = DecisionOption.newBuilder().setKey(option.getKey());
          if (!option.getValue().isNull()) o.setDescription(
            DecisionSupport.value(option.getValue())
          );
          choice.addOptions(o);
        }
        question.setChoice(choice);
      }
      case "score" -> {
        if (!criteria.isArray() || criteria.size() < 2 || criteria.size() > MAX_LEVELS) {
          return "\"criteria\" must be an array of 2 to 10 levels";
        }
        var score = ScoreQuestion.newBuilder();
        criteria.forEach(level -> score.addLevels(DecisionSupport.value(level)));
        question.setScore(score);
        levels.put(id, criteria);
      }
      case "noul" -> {
        if (!criteria.isMissingNode() && !criteria.isNull() && !criteria.isObject()) {
          return "\"criteria\" must be an object";
        }
        var noul = NoulQuestion.newBuilder();
        if (criteria.has("true")) noul.setWhenTrue(DecisionSupport.value(criteria.get("true")));
        if (criteria.has("false")) noul.setWhenFalse(DecisionSupport.value(criteria.get("false")));
        question.setNoul(noul);
      }
      default -> {
        return "\"type\" must be one of: choice, score, noul";
      }
    }
    input.addQuestions(question);
    return null;
  }

  private static ObjectNode render(
    String model,
    DecideResponse resp,
    Map<String, JsonNode> levels
  ) {
    ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
    root.put("model", model);
    ObjectNode answers = root.putObject("answers");
    DecisionSupport.blocked(root, resp);
    for (DecisionAnswer a : resp.getAnswersList()) {
      ObjectNode answer = answers.putObject(a.getQuestionId());
      switch (a.getResultCase()) {
        case CHOICE -> {
          answer.put("type", "choice");
          answer.put("choice", a.getChoice().getChoice());
          probabilities(answer, a.getChoice().getProbabilitiesList());
          answer.put("confidence", a.getChoice().getConfidence());
        }
        case SCORE -> {
          answer.put("type", "score");
          answer.put("score", a.getScore().getScore());
          ObjectNode legend = answer.putObject("legend");
          JsonNode described = levels.get(a.getQuestionId());
          for (int i = 0; described != null && i < described.size(); i++) {
            legend.set(String.valueOf(i), described.get(i));
          }
          probabilities(answer, a.getScore().getProbabilitiesList());
          answer.put("confidence", a.getScore().getConfidence());
        }
        case NOUL -> {
          answer.put("type", "noul");
          answer.put("noul", a.getNoul().getProbabilityTrue());
        }
        case RESULT_NOT_SET -> {}
      }
      rawScores(answer, a.getRawScoresList());
    }
    ObjectNode usage = root.putObject("usage");
    usage.put("input_tokens", resp.getUsage().getPromptTokens());
    usage.put("output_tokens", 0);
    return root;
  }

  private static void probabilities(ObjectNode answer, List<OptionProbability> probabilities) {
    ObjectNode out = answer.putObject("probabilities");
    probabilities.forEach(p -> out.put(p.getKey(), p.getProbability()));
  }

  static void rawScores(ObjectNode answer, List<RawScores> raw) {
    if (raw.isEmpty()) return;
    ArrayNode variants = answer.putArray("raw_scores");
    for (RawScores r : raw) {
      ArrayNode values = variants.addArray();
      r.getValuesList().forEach(values::add);
    }
  }
}
