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
package io.gravitee.singularitee.inference.vllm.decision;

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionQuestions;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.vllm.decision.VllmDecider;
import io.gravitee.vllm.runtime.PythonRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A vLLM Semantic Router decision model, run in-process by its own model runtime
 * ({@code vllm_srun}) through vLLM4j's {@link VllmDecider}.
 *
 * <p>The runtime renders and scores its prompts itself, so a request goes to it as a System One
 * {@code /v1/decisions} body (state and named questions) and the answers come back already
 * calibrated. Caller-rendered prompts have nothing to run on.
 *
 * <p>Thread-safe: the runtime batches concurrent calls on its own scheduler.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmDecisionModel implements AutoCloseable {

  /** Decision 3.0's built-in ids: {@code vllm-sr/d3} and its sizes ({@code vllm-sr/d3-lite}...). */
  private static final Pattern DECISION3_ID = Pattern.compile("(?:.*/)?d3(?:-[a-z]+)?");

  /** Most options a choice question may list. */
  static final int MAX_OPTIONS = 255;

  /** Per-question errors that blame the request rather than the model. */
  private static final Set<String> INVALID_QUESTION_ERRORS = Set.of(
    "invalid_question",
    "invalid_input",
    "max_length_exceeded",
    "scan_budget_exceeded"
  );

  /** Request-wide errors that blame the request rather than the model. */
  private static final Set<String> INVALID_REQUEST_ERRORS = Set.of(
    "invalid_request",
    "request_too_large"
  );

  /** The runtime call: a {@code /v1/decisions} body in, the response body out. */
  public interface Runtime extends AutoCloseable {
    Map<String, Object> decide(Map<String, Object> body);

    @Override
    void close();
  }

  private final Runtime runtime;
  private final DecisionCapabilities capabilities;

  VllmDecisionModel(Runtime runtime, DecisionFamily family) {
    this.runtime = runtime;
    this.capabilities = new DecisionCapabilities(family, MAX_OPTIONS, true, false);
  }

  /** Loads the model: resolves and verifies its files and checks its golden answers. */
  public static VllmDecisionModel load(VllmDecisionModelConfig config) {
    if (config.hfToken() != null && !config.hfToken().isBlank()) {
      PythonRuntime.setEnv("HF_TOKEN", config.hfToken());
      PythonRuntime.setEnv("HUGGING_FACE_HUB_TOKEN", config.hfToken());
    }
    var builder = VllmDecider.builder().model(config.model());
    if (config.venvPath() != null) builder.venvPath(config.venvPath());
    if (config.revision() != null) builder.revision(config.revision());
    if (config.device() != null) builder.device(config.device());
    if (config.profile() != null) builder.profile(config.profile());
    if (config.engine() != null) builder.engine(config.engine());
    if (config.memoryBudgetGib() != null) builder.memoryBudgetGib(config.memoryBudgetGib());
    if (config.threads() != null) builder.threads(config.threads());
    if (config.cacheDir() != null) builder.cacheDir(config.cacheDir());
    if (config.offline() != null) builder.offline(config.offline());
    return new VllmDecisionModel(new VllmDeciderRuntime(builder.build()), config.family());
  }

  /** A request the runtime refused as a whole, under its error {@code code}. */
  static DecisionException refused(String code, String message, Throwable cause) {
    return new DecisionException(
      INVALID_REQUEST_ERRORS.contains(code) ? Reason.INVALID_REQUEST : Reason.EVALUATION_FAILED,
      message,
      cause
    );
  }

  /**
   * The family of {@code model}: the declared one, or the one its built-in id names.
   *
   * @param declared {@code decision1}, {@code decision2}, {@code decision3}, or {@code null} /
   *                 blank to infer it
   * @throws IllegalArgumentException when neither says
   */
  public static DecisionFamily familyOf(String model, String declared) {
    if (declared != null && !declared.isBlank()) {
      return switch (declared.toLowerCase(Locale.ROOT)) {
        case "decision1" -> DecisionFamily.DECISION1;
        case "decision2" -> DecisionFamily.DECISION2;
        case "decision3" -> DecisionFamily.DECISION3;
        default -> throw new IllegalArgumentException(
          "family must be decision1, decision2 or decision3, got '" + declared + "'"
        );
      };
    }
    if (model.contains("Decision-1.0-")) return DecisionFamily.DECISION1;
    if (model.contains("Decision-2.0-")) return DecisionFamily.DECISION2;
    if (DECISION3_ID.matcher(model).matches()) return DecisionFamily.DECISION3;
    throw new IllegalArgumentException(
      "cannot tell the family of '" +
        model +
        "' from its name; set family: decision1, decision2 or decision3"
    );
  }

  /** What the model accepts: templated requests only, up to 255 options per choice. */
  public DecisionCapabilities capabilities() {
    return capabilities;
  }

  /**
   * Answers one request.
   *
   * @throws DecisionException when the request is not one the runtime takes, or a question fails
   */
  public DecisionResult decide(DecisionRequest request) {
    if (!(request.input() instanceof DecisionInput.Templated templated)) {
      throw new DecisionException(
        Reason.UNSUPPORTED_INPUT,
        "this model renders its own prompts: send a state and questions, not raw prompts"
      );
    }
    Map<String, Object> response;
    try {
      response = runtime.decide(body(templated));
    } catch (DecisionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecisionException(Reason.EVALUATION_FAILED, e.getMessage(), e);
    }
    return result(templated.questions(), response);
  }

  @Override
  public void close() {
    runtime.close();
  }

  /** The System One body of a templated request. */
  static Map<String, Object> body(DecisionInput.Templated input) {
    if (input.state() == null) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"state\" must be provided");
    }
    // Checked here: keyed by id below, a duplicate would silently replace its first occurrence.
    DecisionQuestions.validate(input.questions());
    Map<String, Object> questions = new LinkedHashMap<>();
    for (Question question : input.questions()) {
      questions.put(question.id(), question(question));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("state", input.state());
    body.put("questions", questions);
    return body;
  }

  private static Map<String, Object> question(Question question) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", question.kind().name().toLowerCase(Locale.ROOT));
    out.put("instructions", question.instructions());
    switch (question) {
      case Question.Choice choice -> {
        // The ordered form, so options keep the caller's order.
        var choices = new ArrayList<Map<String, Object>>(choice.options().size());
        for (Question.Option option : choice.options()) {
          Map<String, Object> entry = new HashMap<>();
          entry.put("key", option.key());
          entry.put("description", option.description());
          choices.add(entry);
        }
        out.put("choices", choices);
      }
      case Question.Score score -> out.put("levels", score.levels());
      case Question.Noul noul -> {
        // A null description takes the model's default.
        if (noul.whenFalse() != null || noul.whenTrue() != null) {
          Map<String, Object> criteria = new HashMap<>();
          criteria.put("false", noul.whenFalse());
          criteria.put("true", noul.whenTrue());
          out.put("criteria", criteria);
        }
      }
    }
    return out;
  }

  /** The answers of {@code response}, in {@code questions} order. */
  @SuppressWarnings("unchecked")
  static DecisionResult result(List<Question> questions, Map<String, Object> response) {
    Map<String, Object> answers = response.get("answers") instanceof Map<?, ?> m
      ? (Map<String, Object>) m
      : Map.of();
    var out = new ArrayList<Answer>(questions.size());
    for (Question question : questions) {
      if (!(answers.get(question.id()) instanceof Map<?, ?> raw)) {
        throw new DecisionException(
          Reason.EVALUATION_FAILED,
          "questions." + question.id() + ": the model returned no answer"
        );
      }
      out.add(answer(question, (Map<String, Object>) raw));
    }
    int inputTokens = 0;
    if (
      response.get("usage") instanceof Map<?, ?> usage &&
      usage.get("input_tokens") instanceof Number n
    ) {
      inputTokens = n.intValue();
    }
    return new DecisionResult(out, inputTokens);
  }

  private static Answer answer(Question question, Map<String, Object> raw) {
    String id = question.id();
    if (raw.get("error") != null) {
      String error = String.valueOf(raw.get("error"));
      String message = raw.get("message") == null ? error : error + ": " + raw.get("message");
      throw INVALID_QUESTION_ERRORS.contains(error)
        ? DecisionException.invalid(id, message)
        : new DecisionException(Reason.EVALUATION_FAILED, "questions." + id + ": " + message);
    }
    return switch (question) {
      case Question.Choice choice -> new Answer.Choice(
        id,
        choice(choice, raw.get("choice")),
        probabilities(choice.options(), raw.get("probabilities")),
        number(raw.get("confidence")),
        List.of()
      );
      case Question.Score score -> {
        var probabilities = probabilities(score.options(), raw.get("probabilities"));
        yield new Answer.Score(
          id,
          number(raw.get("score")),
          mode(probabilities),
          probabilities,
          number(raw.get("confidence")),
          List.of()
        );
      }
      case Question.Noul noul -> new Answer.Noul(id, number(raw.get("noul")), List.of());
    };
  }

  /** The runtime's choice, which must be one of the question's option keys. */
  private static String choice(Question.Choice question, Object raw) {
    String choice = string(raw);
    for (Question.Option option : question.options()) {
      if (option.key().equals(choice)) return choice;
    }
    throw new DecisionException(
      Reason.EVALUATION_FAILED,
      "questions." + question.id() + ": the model chose '" + choice + "', which is not an option"
    );
  }

  /** One probability per option, in option order; an option the model left out has 0. */
  private static List<Answer.OptionProbability> probabilities(
    List<Question.Option> options,
    Object raw
  ) {
    Map<?, ?> byKey = raw instanceof Map<?, ?> m ? m : Map.of();
    var out = new ArrayList<Answer.OptionProbability>(options.size());
    for (Question.Option option : options) {
      out.add(new Answer.OptionProbability(option.key(), number(byKey.get(option.key()))));
    }
    return out;
  }

  /** The index of the most probable level. */
  private static int mode(List<Answer.OptionProbability> probabilities) {
    int best = 0;
    for (int i = 1; i < probabilities.size(); i++) {
      if (probabilities.get(i).probability() > probabilities.get(best).probability()) best = i;
    }
    return best;
  }

  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0.0;
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
