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
package io.gravitee.singularitee.engine.api.decision;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.gravitee.singularitee.protocol.ChatMessage;
import io.gravitee.singularitee.protocol.ChoiceAnswer;
import io.gravitee.singularitee.protocol.ChoiceQuestion;
import io.gravitee.singularitee.protocol.DecideRequest;
import io.gravitee.singularitee.protocol.DecideResponse;
import io.gravitee.singularitee.protocol.DecisionAnswer;
import io.gravitee.singularitee.protocol.DecisionOption;
import io.gravitee.singularitee.protocol.DecisionQuestion;
import io.gravitee.singularitee.protocol.NoulAnswer;
import io.gravitee.singularitee.protocol.NoulQuestion;
import io.gravitee.singularitee.protocol.OptionProbability;
import io.gravitee.singularitee.protocol.RawChoice;
import io.gravitee.singularitee.protocol.RawInput;
import io.gravitee.singularitee.protocol.RawNoul;
import io.gravitee.singularitee.protocol.RawPrompt;
import io.gravitee.singularitee.protocol.RawScore;
import io.gravitee.singularitee.protocol.RawScores;
import io.gravitee.singularitee.protocol.ScoreAnswer;
import io.gravitee.singularitee.protocol.ScoreQuestion;
import io.gravitee.singularitee.protocol.TemplatedInput;
import io.gravitee.singularitee.protocol.TokenList;
import io.gravitee.singularitee.protocol.TokenUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates decision calls between the protocol messages and the engine contract, both ways:
 * the server reads requests and writes answers, a remote proxy writes requests and reads answers.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionMessages {

  private DecisionMessages() {}

  // ── protocol to engine ───────────────────────────────────────────────────

  /** The engine request of a protocol request. */
  public static DecisionRequest request(DecideRequest request) {
    return switch (request.getInputCase()) {
      case TEMPLATED -> templated(request.getTemplated(), request.getIncludeRawScores());
      case RAW -> raw(request.getRaw(), request.getIncludeRawScores());
      case INPUT_NOT_SET -> throw new DecisionException(
        Reason.INVALID_REQUEST,
        "either questions (templated) or prompts (raw) are required"
      );
    };
  }

  /** The engine request of a templated input. */
  public static DecisionRequest templated(TemplatedInput input, boolean includeRawScores) {
    rejectMedia(input.getImagesCount() > 0);
    Object state = switch (input.getStateCase()) {
      case TEXT -> input.getText();
      case JSON -> toJava(input.getJson());
      case MESSAGES -> messages(input.getMessages().getMessagesList());
      case STATE_NOT_SET -> null;
    };
    var questions = new ArrayList<Question>(input.getQuestionsCount());
    for (DecisionQuestion q : input.getQuestionsList()) questions.add(question(q));
    return new DecisionRequest(new DecisionInput.Templated(state, questions), includeRawScores);
  }

  /** The engine request of a raw input. */
  public static DecisionRequest raw(RawInput input, boolean includeRawScores) {
    var prompts = new ArrayList<DecisionInput.RawPrompt>(input.getPromptsCount());
    for (RawPrompt p : input.getPromptsList()) {
      QuestionKind kind;
      List<String> keys;
      switch (p.getKindCase()) {
        case CHOICE -> {
          kind = QuestionKind.CHOICE;
          keys = p.getChoice().getOptionKeysList();
        }
        case SCORE -> {
          kind = QuestionKind.SCORE;
          keys = levelKeys(p.getScore().getLevels());
        }
        case NOUL -> {
          kind = QuestionKind.NOUL;
          keys = List.of();
        }
        default -> throw DecisionException.invalid(
          p.getQuestionId(),
          "the type must be one of: choice, score, noul"
        );
      }
      String text = p.getBodyCase() == RawPrompt.BodyCase.TEXT ? p.getText() : null;
      int[] tokens = p.getBodyCase() == RawPrompt.BodyCase.TOKENS
        ? p.getTokens().getIdsList().stream().mapToInt(Integer::intValue).toArray()
        : null;
      prompts.add(new DecisionInput.RawPrompt(p.getQuestionId(), kind, keys, text, tokens));
    }
    return new DecisionRequest(new DecisionInput.Raw(prompts), includeRawScores);
  }

  private static Question question(DecisionQuestion q) {
    Object instructions = q.hasInstructions() ? toJava(q.getInstructions()) : null;
    return switch (q.getKindCase()) {
      case CHOICE -> new Question.Choice(
        q.getId(),
        instructions,
        q
          .getChoice()
          .getOptionsList()
          .stream()
          .map(o ->
            new Question.Option(o.getKey(), o.hasDescription() ? toJava(o.getDescription()) : null)
          )
          .toList()
      );
      case SCORE -> new Question.Score(
        q.getId(),
        instructions,
        q.getScore().getLevelsList().stream().map(DecisionMessages::toJava).toList()
      );
      case NOUL -> new Question.Noul(
        q.getId(),
        instructions,
        q.getNoul().hasWhenTrue() ? toJava(q.getNoul().getWhenTrue()) : null,
        q.getNoul().hasWhenFalse() ? toJava(q.getNoul().getWhenFalse()) : null
      );
      case KIND_NOT_SET -> throw DecisionException.invalid(
        q.getId(),
        "\"type\" must be one of: choice, score, noul"
      );
    };
  }

  private static List<String> levelKeys(int levels) {
    var keys = new ArrayList<String>(Math.max(levels, 0));
    for (int i = 0; i < levels; i++) keys.add(String.valueOf(i));
    return keys;
  }

  private static List<Map<String, Object>> messages(List<ChatMessage> messages) {
    var out = new ArrayList<Map<String, Object>>(messages.size());
    for (ChatMessage m : messages) {
      rejectMedia(m.getMediaCount() > 0);
      var map = new LinkedHashMap<String, Object>();
      map.put("role", m.getRole().name().substring("ROLE_".length()).toLowerCase(Locale.ROOT));
      map.put("content", m.getContent());
      out.add(map);
    }
    return out;
  }

  private static void rejectMedia(boolean hasMedia) {
    if (hasMedia) {
      throw new DecisionException(
        Reason.UNSUPPORTED_MODALITY,
        "image input is not supported for decisions on this server"
      );
    }
  }

  /** The engine result of a protocol response. */
  public static DecisionResult result(DecideResponse response) {
    var answers = new ArrayList<Answer>(response.getAnswersCount());
    for (DecisionAnswer a : response.getAnswersList()) {
      List<float[]> raw = a
        .getRawScoresList()
        .stream()
        .map(r -> {
          float[] values = new float[r.getValuesCount()];
          for (int i = 0; i < values.length; i++) values[i] = r.getValues(i);
          return values;
        })
        .toList();
      answers.add(
        switch (a.getResultCase()) {
          case CHOICE -> new Answer.Choice(
            a.getQuestionId(),
            a.getChoice().getChoice(),
            probabilities(a.getChoice().getProbabilitiesList()),
            a.getChoice().getConfidence(),
            raw
          );
          case SCORE -> new Answer.Score(
            a.getQuestionId(),
            a.getScore().getScore(),
            a.getScore().getMode(),
            probabilities(a.getScore().getProbabilitiesList()),
            a.getScore().getConfidence(),
            raw
          );
          case NOUL, RESULT_NOT_SET -> new Answer.Noul(
            a.getQuestionId(),
            a.getNoul().getProbabilityTrue(),
            raw
          );
        }
      );
    }
    return new DecisionResult(answers, response.getUsage().getPromptTokens());
  }

  private static List<Answer.OptionProbability> probabilities(List<OptionProbability> in) {
    return in
      .stream()
      .map(p -> new Answer.OptionProbability(p.getKey(), p.getProbability()))
      .toList();
  }

  // ── engine to protocol ───────────────────────────────────────────────────

  /** The protocol request of an engine request, for the model {@code modelId}. */
  public static DecideRequest request(String modelId, DecisionRequest request) {
    var builder = DecideRequest.newBuilder()
      .setModelId(modelId)
      .setIncludeRawScores(request.includeRawScores());
    switch (request.input()) {
      case DecisionInput.Templated t -> {
        var input = TemplatedInput.newBuilder();
        if (t.state() instanceof String text) input.setText(text);
        else input.setJson(toValue(t.state()));
        t.questions().forEach(q -> input.addQuestions(question(q)));
        builder.setTemplated(input);
      }
      case DecisionInput.Raw r -> {
        var input = RawInput.newBuilder();
        for (DecisionInput.RawPrompt p : r.prompts()) {
          var prompt = RawPrompt.newBuilder().setQuestionId(p.questionId());
          switch (p.kind()) {
            case CHOICE -> prompt.setChoice(
              RawChoice.newBuilder().addAllOptionKeys(p.optionKeys())
            );
            case SCORE -> prompt.setScore(RawScore.newBuilder().setLevels(p.optionKeys().size()));
            case NOUL -> prompt.setNoul(RawNoul.getDefaultInstance());
          }
          if (p.text() != null) prompt.setText(p.text());
          else prompt.setTokens(
            TokenList.newBuilder().addAllIds(Arrays.stream(p.tokens()).boxed().toList())
          );
          input.addPrompts(prompt);
        }
        builder.setRaw(input);
      }
    }
    return builder.build();
  }

  private static DecisionQuestion question(Question q) {
    var builder = DecisionQuestion.newBuilder()
      .setId(q.id())
      .setInstructions(toValue(q.instructions()));
    switch (q) {
      case Question.Choice c -> {
        var choice = ChoiceQuestion.newBuilder();
        for (Question.Option o : c.options()) {
          var option = DecisionOption.newBuilder().setKey(o.key());
          if (o.description() != null) option.setDescription(toValue(o.description()));
          choice.addOptions(option);
        }
        builder.setChoice(choice);
      }
      case Question.Score s -> {
        var score = ScoreQuestion.newBuilder();
        s.levels().forEach(level -> score.addLevels(toValue(level)));
        builder.setScore(score);
      }
      case Question.Noul n -> {
        var noul = NoulQuestion.newBuilder();
        if (n.whenTrue() != null) noul.setWhenTrue(toValue(n.whenTrue()));
        if (n.whenFalse() != null) noul.setWhenFalse(toValue(n.whenFalse()));
        builder.setNoul(noul);
      }
    }
    return builder.build();
  }

  /** The protocol response of an engine result. */
  public static DecideResponse response(String targetId, DecisionResult result) {
    var builder = DecideResponse.newBuilder()
      .setModelId(targetId)
      .setUsage(TokenUsage.newBuilder().setPromptTokens(result.inputTokens()));
    for (Answer answer : result.answers()) builder.addAnswers(answer(answer));
    return builder.build();
  }

  private static DecisionAnswer answer(Answer answer) {
    var builder = DecisionAnswer.newBuilder().setQuestionId(answer.questionId());
    switch (answer) {
      case Answer.Choice c -> builder.setChoice(
        ChoiceAnswer.newBuilder()
          .setChoice(c.choice())
          .addAllProbabilities(protoProbabilities(c.probabilities()))
          .setConfidence(c.confidence())
      );
      case Answer.Score s -> builder.setScore(
        ScoreAnswer.newBuilder()
          .setScore(s.score())
          .setMode(s.mode())
          .addAllProbabilities(protoProbabilities(s.probabilities()))
          .setConfidence(s.confidence())
      );
      case Answer.Noul n -> builder.setNoul(
        NoulAnswer.newBuilder().setProbabilityTrue(n.probabilityTrue())
      );
    }
    for (float[] variant : answer.rawScores()) {
      var raw = RawScores.newBuilder();
      for (float v : variant) raw.addValues(v);
      builder.addRawScores(raw);
    }
    return builder.build();
  }

  private static List<OptionProbability> protoProbabilities(List<Answer.OptionProbability> in) {
    return in
      .stream()
      .map(p ->
        OptionProbability.newBuilder().setKey(p.key()).setProbability(p.probability()).build()
      )
      .toList();
  }

  // ── values ───────────────────────────────────────────────────────────────

  /** A protobuf {@link Value} as the plain Java value templates receive. */
  public static Object toJava(Value value) {
    return switch (value.getKindCase()) {
      case NULL_VALUE, KIND_NOT_SET -> null;
      case BOOL_VALUE -> value.getBoolValue();
      case STRING_VALUE -> value.getStringValue();
      case NUMBER_VALUE -> {
        double d = value.getNumberValue();
        // JSON has one number type; keep integers integers so templates print 3, not 3.0.
        yield d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
      }
      case STRUCT_VALUE -> {
        var map = new LinkedHashMap<String, Object>();
        value
          .getStructValue()
          .getFieldsMap()
          .forEach((k, v) -> map.put(k, toJava(v)));
        yield map;
      }
      case LIST_VALUE -> value
        .getListValue()
        .getValuesList()
        .stream()
        .map(DecisionMessages::toJava)
        .toList();
    };
  }

  /** A plain Java value (from a JSON parser) as a protobuf {@link Value}. */
  public static Value toValue(Object value) {
    return switch (value) {
      case null -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
      case String s -> Value.newBuilder().setStringValue(s).build();
      case Boolean b -> Value.newBuilder().setBoolValue(b).build();
      case Number n -> Value.newBuilder().setNumberValue(n.doubleValue()).build();
      case Map<?, ?> map -> {
        var struct = Struct.newBuilder();
        map.forEach((k, v) -> struct.putFields(String.valueOf(k), toValue(v)));
        yield Value.newBuilder().setStructValue(struct).build();
      }
      case List<?> list -> {
        var lv = ListValue.newBuilder();
        list.forEach(v -> lv.addValues(toValue(v)));
        yield Value.newBuilder().setListValue(lv).build();
      }
      default -> Value.newBuilder().setStringValue(String.valueOf(value)).build();
    };
  }
}
