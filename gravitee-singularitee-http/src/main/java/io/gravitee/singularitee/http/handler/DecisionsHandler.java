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
import io.gravitee.singularitee.protocol.ChatMessageList;
import io.gravitee.singularitee.protocol.ChoiceQuestion;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.DecisionAnswer;
import io.gravitee.singularitee.protocol.DecisionOption;
import io.gravitee.singularitee.protocol.DecisionQuestion;
import io.gravitee.singularitee.protocol.NoulQuestion;
import io.gravitee.singularitee.protocol.OptionProbability;
import io.gravitee.singularitee.protocol.RawChoice;
import io.gravitee.singularitee.protocol.RawInput;
import io.gravitee.singularitee.protocol.RawNoul;
import io.gravitee.singularitee.protocol.RawPrompt;
import io.gravitee.singularitee.protocol.RawScore;
import io.gravitee.singularitee.protocol.ScoreQuestion;
import io.gravitee.singularitee.protocol.TemplatedInput;
import io.gravitee.singularitee.protocol.TokenList;
import io.gravitee.singularitee.service.GraviteeDecisionServiceImpl;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /v1/decisions}: the OpenAI-shaped decision route. Same envelope conventions as the
 * other routes ({@code id}, {@code object}, {@code created}, {@code usage.prompt_tokens}, the
 * error object) and the gRPC message shape in JSON: ordered arrays rather than keyed objects.
 * Accepts questions the server renders ({@code input} + {@code questions}) or prompts the caller
 * rendered ({@code prompts}).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionsHandler implements Handler<RoutingContext> {

  private final GraviteeDecisionServiceImpl decisions;
  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;

  public DecisionsHandler(
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
    if (!HandlerSupport.validate(rc, payload, SchemaName.DECISIONS)) return;

    boolean hasPrompts = payload.has("prompts");
    boolean hasQuestions = payload.has("questions") || payload.has("input");
    if (hasPrompts == hasQuestions) {
      HandlerSupport.badRequest(
        rc,
        "send either \"input\" and \"questions\", or \"prompts\"",
        hasPrompts ? "prompts" : "questions"
      );
      return;
    }

    var request = DecideRequest.newBuilder().setIncludeRawScores(
      payload.path("include_raw_scores").asBoolean(false)
    );
    if (!DecisionSupport.target(rc, model, modelRegistry, pipelineRegistry, request)) return;
    String error = hasPrompts ? raw(payload.path("prompts"), request) : templated(payload, request);
    if (error != null) {
      if (error.equals(DecisionSupport.NO_IMAGES)) DecisionSupport.unsupportedModality(rc, error);
      else HandlerSupport.badRequest(rc, error, hasPrompts ? "prompts" : "questions");
      return;
    }

    decisions
      .execute(request.build())
      .onSuccess(resp -> JsonResponses.writeJson(rc, render(model, resp)))
      .onFailure(err -> DecisionSupport.fail(rc, err));
  }

  private static String templated(JsonNode payload, DecideRequest.Builder request) {
    JsonNode state = payload.path("input");
    if (state.isMissingNode() || state.isNull()) return "\"input\" must be provided";
    if (DecisionSupport.hasImagePart(state)) return DecisionSupport.NO_IMAGES;
    var input = TemplatedInput.newBuilder();
    ChatMessageList messages = DecisionSupport.messages(state);
    if (state.isTextual()) input.setText(state.asText());
    else if (messages != null) input.setMessages(messages);
    else input.setJson(DecisionSupport.value(state));

    for (JsonNode q : payload.path("questions")) {
      String id = q.path("id").asText("");
      JsonNode instructions = q.path("instructions");
      if (instructions.isMissingNode() || instructions.isNull()) {
        return "questions." + id + ": \"instructions\" must be provided";
      }
      var question = DecisionQuestion.newBuilder()
        .setId(id)
        .setInstructions(DecisionSupport.value(instructions));
      switch (q.path("type").asText("")) {
        case "choice" -> {
          var choice = ChoiceQuestion.newBuilder();
          for (JsonNode o : q.path("options")) {
            var option = DecisionOption.newBuilder().setKey(o.path("key").asText(""));
            JsonNode description = o.path("description");
            if (!description.isMissingNode() && !description.isNull()) {
              option.setDescription(DecisionSupport.value(description));
            }
            choice.addOptions(option);
          }
          question.setChoice(choice);
        }
        case "score" -> {
          var score = ScoreQuestion.newBuilder();
          q.path("levels").forEach(level -> score.addLevels(DecisionSupport.value(level)));
          question.setScore(score);
        }
        case "noul" -> {
          var noul = NoulQuestion.newBuilder();
          if (q.has("when_true")) noul.setWhenTrue(DecisionSupport.value(q.get("when_true")));
          if (q.has("when_false")) noul.setWhenFalse(DecisionSupport.value(q.get("when_false")));
          question.setNoul(noul);
        }
        default -> {
          return "questions." + id + ": \"type\" must be one of: choice, score, noul";
        }
      }
      input.addQuestions(question);
    }
    request.setTemplated(input);
    return null;
  }

  private static String raw(JsonNode prompts, DecideRequest.Builder request) {
    var input = RawInput.newBuilder();
    for (JsonNode p : prompts) {
      String id = p.path("id").asText("");
      var prompt = RawPrompt.newBuilder().setQuestionId(id);
      switch (p.path("type").asText("")) {
        case "choice" -> {
          var choice = RawChoice.newBuilder();
          p.path("option_keys").forEach(k -> choice.addOptionKeys(k.asText("")));
          prompt.setChoice(choice);
        }
        case "score" -> prompt.setScore(RawScore.newBuilder().setLevels(p.path("levels").asInt(0)));
        case "noul" -> prompt.setNoul(RawNoul.getDefaultInstance());
        default -> {
          return "prompts." + id + ": \"type\" must be one of: choice, score, noul";
        }
      }
      boolean text = p.path("prompt").isTextual();
      boolean tokens = p.path("prompt_tokens").isArray();
      if (text == tokens) {
        return "prompts." + id + ": exactly one of \"prompt\" and \"prompt_tokens\" must be set";
      }
      if (text) {
        prompt.setText(p.path("prompt").asText());
      } else {
        var ids = TokenList.newBuilder();
        p.path("prompt_tokens").forEach(t -> ids.addIds(t.asInt()));
        prompt.setTokens(ids);
      }
      input.addPrompts(prompt);
    }
    request.setRaw(input);
    return null;
  }

  private static ObjectNode render(String model, DecideResponse resp) {
    ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
    root.put("id", "dec_" + UUID.randomUUID().toString().replace("-", ""));
    root.put("object", "decision");
    root.put("created", Instant.now().getEpochSecond());
    root.put("model", model);
    ArrayNode answers = root.putArray("answers");
    DecisionSupport.blocked(root, resp);
    for (DecisionAnswer a : resp.getAnswersList()) {
      ObjectNode answer = answers.addObject();
      answer.put("question_id", a.getQuestionId());
      switch (a.getResultCase()) {
        case CHOICE -> {
          answer.put("type", "choice");
          answer.put("choice", a.getChoice().getChoice());
          answer.put("confidence", a.getChoice().getConfidence());
          probabilities(answer, a.getChoice().getProbabilitiesList());
        }
        case SCORE -> {
          answer.put("type", "score");
          answer.put("score", a.getScore().getScore());
          answer.put("mode", a.getScore().getMode());
          answer.put("confidence", a.getScore().getConfidence());
          probabilities(answer, a.getScore().getProbabilitiesList());
        }
        case NOUL -> {
          answer.put("type", "noul");
          answer.put("probability_true", a.getNoul().getProbabilityTrue());
        }
        case RESULT_NOT_SET -> {}
      }
      SystemOneHandler.rawScores(answer, a.getRawScoresList());
    }
    ObjectNode usage = root.putObject("usage");
    int promptTokens = resp.getUsage().getPromptTokens();
    usage.put("prompt_tokens", promptTokens);
    usage.put("completion_tokens", 0);
    usage.put("total_tokens", promptTokens);
    return root;
  }

  private static void probabilities(ObjectNode answer, List<OptionProbability> probabilities) {
    ArrayNode out = answer.putArray("probabilities");
    for (OptionProbability p : probabilities) {
      ObjectNode entry = out.addObject();
      entry.put("key", p.getKey());
      entry.put("probability", p.getProbability());
    }
  }
}
