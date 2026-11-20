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
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/decisions}: the OpenAI Decisions API. Questions are {@code predicate},
 * {@code choice} or {@code score}, optionally named; answers come back in question order with
 * OpenAI's field names. Two additions the OpenAI API does not have: {@code prompts} the caller
 * already rendered, in place of {@code input} and {@code questions}, and {@code blocked} when a
 * pipeline guard halted before deciding.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionsHandler implements Handler<RoutingContext> {

  private static final String TYPES = "\"type\" must be one of: predicate, choice, score";

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

  /**
   * What the response needs back from a question: the name the caller gave, {@code null} when
   * unnamed, the choice values by option key and the score level labels.
   */
  private record Asked(String id, String name, Map<String, JsonNode> values, List<String> labels) {}

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
    var asked = new ArrayList<Asked>();
    String error = hasPrompts
      ? raw(payload.path("prompts"), request, asked)
      : templated(payload, request, asked);
    if (error != null) {
      if (error.equals(DecisionSupport.NO_IMAGES)) DecisionSupport.unsupportedModality(rc, error);
      else HandlerSupport.badRequest(rc, error, hasPrompts ? "prompts" : "questions");
      return;
    }

    decisions
      .execute(request.build())
      .onSuccess(resp -> JsonResponses.writeJson(rc, render(model, resp, asked)))
      .onFailure(err -> DecisionSupport.fail(rc, err));
  }

  private static String templated(
    JsonNode payload,
    DecideRequest.Builder request,
    List<Asked> asked
  ) {
    JsonNode state = payload.path("input");
    if (state.isMissingNode() || state.isNull()) return "\"input\" must be provided";
    JsonNode questions = payload.path("questions");
    if (DecisionSupport.hasImagePart(state) || DecisionSupport.hasImagePart(questions)) {
      return DecisionSupport.NO_IMAGES;
    }
    var input = TemplatedInput.newBuilder();
    ChatMessageList messages = DecisionSupport.messages(state);
    if (state.isTextual()) input.setText(state.asText());
    else if (messages != null) input.setMessages(messages);
    else input.setJson(DecisionSupport.value(state));

    List<String> ids = ids(questions);
    for (int i = 0; i < questions.size(); i++) {
      JsonNode q = questions.get(i);
      String name = q.path("name").asText(null);
      String where = name != null ? "questions." + name : "questions[" + i + "]";
      JsonNode instructions = q.path("instructions");
      if (instructions.isMissingNode() || instructions.isNull()) {
        return where + ": \"instructions\" must be provided";
      }
      var question = DecisionQuestion.newBuilder()
        .setId(ids.get(i))
        .setInstructions(DecisionSupport.value(DecisionSupport.flattenParts(instructions)));
      Map<String, JsonNode> values = new HashMap<>();
      List<String> labels = new ArrayList<>();
      switch (q.path("type").asText("")) {
        case "choice" -> {
          if (!q.path("choices").isArray()) return where + ": \"choices\" must be provided";
          var choice = ChoiceQuestion.newBuilder();
          for (JsonNode c : q.path("choices")) {
            JsonNode value = c.path("value");
            var option = DecisionOption.newBuilder().setKey(value.asText(""));
            JsonNode description = c.path("description");
            if (!description.isMissingNode() && !description.isNull()) {
              option.setDescription(DecisionSupport.value(description));
            }
            choice.addOptions(option);
            values.put(value.asText(""), value);
          }
          question.setChoice(choice);
        }
        case "score" -> {
          if (!q.path("levels").isArray()) return where + ": \"levels\" must be provided";
          var score = ScoreQuestion.newBuilder();
          for (JsonNode level : q.path("levels")) {
            String label = level.path("label").asText("");
            String description = level.path("description").asText(null);
            labels.add(label);
            // The prompt shows one text per level: the label, then what it means.
            score.addLevels(
              DecisionSupport.value(
                TextNode.valueOf(description == null ? label : label + ": " + description)
              )
            );
          }
          question.setScore(score);
        }
        case "predicate" -> question.setNoul(NoulQuestion.getDefaultInstance());
        default -> {
          return where + ": " + TYPES;
        }
      }
      input.addQuestions(question);
      asked.add(new Asked(ids.get(i), name, values, labels));
    }
    request.setTemplated(input);
    return null;
  }

  /**
   * The protocol id of each question: its name, or {@code question_<index>} when unnamed, since
   * the protocol needs a unique id where OpenAI leaves the name optional.
   */
  private static List<String> ids(JsonNode questions) {
    var names = new ArrayList<String>();
    for (JsonNode q : questions) names.add(q.path("name").asText(null));
    var ids = new ArrayList<String>(names.size());
    for (int i = 0; i < names.size(); i++) {
      String id = names.get(i);
      if (id == null) {
        id = "question_" + i;
        while (names.contains(id)) id += "_";
      }
      ids.add(id);
    }
    return ids;
  }

  private static String raw(JsonNode prompts, DecideRequest.Builder request, List<Asked> asked) {
    var input = RawInput.newBuilder();
    for (JsonNode p : prompts) {
      String name = p.path("name").asText("");
      var prompt = RawPrompt.newBuilder().setQuestionId(name);
      switch (p.path("type").asText("")) {
        case "choice" -> {
          var choice = RawChoice.newBuilder();
          p.path("option_keys").forEach(k -> choice.addOptionKeys(k.asText("")));
          prompt.setChoice(choice);
        }
        case "score" -> prompt.setScore(RawScore.newBuilder().setLevels(p.path("levels").asInt(0)));
        case "predicate" -> prompt.setNoul(RawNoul.getDefaultInstance());
        default -> {
          return "prompts." + name + ": " + TYPES;
        }
      }
      boolean text = p.path("prompt").isTextual();
      boolean tokens = p.path("prompt_tokens").isArray();
      if (text == tokens) {
        return "prompts." + name + ": exactly one of \"prompt\" and \"prompt_tokens\" must be set";
      }
      if (text) {
        prompt.setText(p.path("prompt").asText());
      } else {
        var ids = TokenList.newBuilder();
        p.path("prompt_tokens").forEach(t -> ids.addIds(t.asInt()));
        prompt.setTokens(ids);
      }
      input.addPrompts(prompt);
      asked.add(new Asked(name, name, Map.of(), List.of()));
    }
    request.setRaw(input);
    return null;
  }

  private static ObjectNode render(String model, DecideResponse resp, List<Asked> asked) {
    ObjectNode root = Utils.OBJECT_MAPPER.get().createObjectNode();
    root.put("model", model);
    ArrayNode answers = root.putArray("answers");
    DecisionSupport.blocked(root, resp);
    if (resp.hasBlocked()) {
      // A halted pipeline answered nothing: one refusal per question keeps the answers aligned.
      for (Asked a : asked) refusal(answers.addObject(), a.name());
    }
    var byId = new HashMap<String, Asked>();
    asked.forEach(a -> byId.put(a.id(), a));
    for (DecisionAnswer a : resp.getAnswersList()) {
      Asked question = byId.get(a.getQuestionId());
      // A pipeline whose decide step declares its own questions answers under their ids.
      String name = question != null ? question.name() : a.getQuestionId();
      ObjectNode answer = answers.addObject();
      switch (a.getResultCase()) {
        case CHOICE -> {
          Map<String, JsonNode> values = question != null ? question.values() : Map.of();
          answer.put("type", "choice");
          answer.put("name", name);
          answer.set("choice", value(values, a.getChoice().getChoice()));
          ArrayNode out = answer.putArray("probabilities");
          for (OptionProbability p : a.getChoice().getProbabilitiesList()) {
            ObjectNode entry = out.addObject();
            entry.set("value", value(values, p.getKey()));
            entry.put("probability", p.getProbability());
          }
          answer.put("confidence", a.getChoice().getConfidence());
        }
        case SCORE -> {
          List<String> labels = question != null ? question.labels() : List.of();
          answer.put("type", "score");
          answer.put("name", name);
          answer.put("score", a.getScore().getScore());
          answer.put("mode", a.getScore().getMode());
          ArrayNode out = answer.putArray("probabilities");
          List<OptionProbability> probabilities = a.getScore().getProbabilitiesList();
          for (int i = 0; i < probabilities.size(); i++) {
            ObjectNode entry = out.addObject();
            entry.put("value", i);
            if (i < labels.size()) entry.put("label", labels.get(i));
            entry.put("probability", probabilities.get(i).getProbability());
          }
          answer.put("confidence", a.getScore().getConfidence());
        }
        case NOUL -> {
          answer.put("type", "predicate");
          answer.put("name", name);
          answer.put("probability", a.getNoul().getProbabilityTrue());
        }
        case RESULT_NOT_SET -> refusal(answer, name);
      }
      SystemOneHandler.rawScores(answer, a.getRawScoresList());
    }
    int inputTokens = resp.getUsage().getPromptTokens();
    ObjectNode usage = root.putObject("usage");
    usage.put("input_tokens", inputTokens);
    ObjectNode inputDetails = usage.putObject("input_tokens_details");
    inputDetails.put("cached_tokens", 0);
    inputDetails.put("cache_write_tokens", 0);
    usage.put("output_tokens", 0);
    usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
    usage.put("total_tokens", inputTokens);
    return root;
  }

  private static void refusal(ObjectNode answer, String name) {
    answer.put("type", "refusal");
    answer.put("name", name);
  }

  /** The value the caller gave for an option key: a boolean stays a boolean. */
  private static JsonNode value(Map<String, JsonNode> values, String key) {
    JsonNode value = values.get(key);
    return value != null ? value : TextNode.valueOf(key);
  }
}
