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

import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionInput.RawPrompt;
import io.gravitee.singularitee.inference.api.decision.DecisionQuestions;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.inference.api.decision.Question.Option;
import io.gravitee.singularitee.inference.api.decision.QuestionKind;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.ChatMessage;
import io.gravitee.singularitee.inference.api.textgen.Role;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers decision requests with one model: renders the prompts its family expects (or takes the
 * caller's), runs the backend once for the whole request, and turns the scores into answers.
 *
 * <p>Thread-safe as long as the backend is: the runner holds no per-request state.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionRunner implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(DecisionRunner.class);

  /** Where the {@code chat} prompt source's instruction template ships. */
  static final String CHAT_INSTRUCTION_RESOURCE = "chat-systemone.jinja";

  private final DecisionBackend backend;
  private final DecisionDialect dialect;
  private final PromptSource source;
  private final ChatTemplateRenderer renderer;
  private final DecisionTemperatures temperatures;
  private final String template;
  private final DecisionCapabilities capabilities;

  /**
   * @param backend              the model; closed with this runner
   * @param source               where prompts come from
   * @param renderer             renders the decision and chat templates
   * @param temperatureOverrides workspace temperatures, same keys as the model's; may be empty
   * @throws IllegalArgumentException when the source cannot serve this model
   */
  public DecisionRunner(
    DecisionBackend backend,
    PromptSource source,
    ChatTemplateRenderer renderer,
    Map<String, Float> temperatureOverrides
  ) {
    this(backend, source, renderer, temperatureOverrides, false);
  }

  /**
   * @param backend              the model; closed with this runner
   * @param source               where prompts come from
   * @param renderer             renders the decision and chat templates
   * @param temperatureOverrides workspace temperatures, same keys as the model's; may be empty
   * @param reverse              custom models: score each choice in both option orders, averaged
   * @throws IllegalArgumentException when the source cannot serve this model
   */
  public DecisionRunner(
    DecisionBackend backend,
    PromptSource source,
    ChatTemplateRenderer renderer,
    Map<String, Float> temperatureOverrides,
    boolean reverse
  ) {
    if (reverse && backend.family() != DecisionFamily.CUSTOM) {
      throw new IllegalArgumentException(
        "debias: reverse is for custom models; native families are scored as they were trained"
      );
    }
    this.backend = backend;
    this.dialect = DecisionDialect.of(backend.family(), reverse);
    this.source = source;
    this.renderer = renderer;
    this.temperatures = new DecisionTemperatures(
      backend.family(),
      backend.temperatures(),
      temperatureOverrides
    );
    this.template = resolveTemplate(backend, source);
    // A joint prompt tags every token with its role; a caller-rendered prompt carries no roles.
    boolean raw = backend.family() != DecisionFamily.CLEF;
    this.capabilities = new DecisionCapabilities(
      backend.family(),
      backend.maxOptions(),
      source.templated(),
      raw
    );
  }

  private static String resolveTemplate(DecisionBackend backend, PromptSource source) {
    return switch (source) {
      case PromptSource.ModelTemplate _ -> {
        if (backend.decisionTemplate() == null || backend.decisionTemplate().isBlank()) {
          throw new IllegalArgumentException(
            "the model has no \"systemone\" template: set prompt.template to a workspace " +
              "template, or to none for raw prompts only"
          );
        }
        yield backend.decisionTemplate();
      }
      case PromptSource.WorkspaceTemplate workspace -> workspace.source();
      case PromptSource.ChatWrapped _ -> {
        if (backend.family() != DecisionFamily.CUSTOM) {
          throw new IllegalArgumentException(
            "prompt.template: chat is for custom models; a " +
              backend.family().wireName() +
              " model was trained on its own prompt"
          );
        }
        if (backend.chatTemplate() == null || backend.chatTemplate().isBlank()) {
          throw new IllegalArgumentException(
            "prompt.template: chat needs a model with a chat template"
          );
        }
        yield readResource(CHAT_INSTRUCTION_RESOURCE);
      }
      case PromptSource.None _ -> null;
    };
  }

  /** What this model accepts. */
  public DecisionCapabilities capabilities() {
    return capabilities;
  }

  /**
   * Answers one request.
   *
   * @throws DecisionException when the request cannot be answered
   */
  public DecisionResult decide(DecisionRequest request) {
    return switch (request.input()) {
      case DecisionInput.Templated templated -> templated(templated, request.includeRawScores());
      case DecisionInput.Raw raw -> raw(raw, request.includeRawScores());
    };
  }

  // ── templated ────────────────────────────────────────────────────────────

  private DecisionResult templated(DecisionInput.Templated input, boolean includeRaw) {
    if (!capabilities.templated()) {
      throw new DecisionException(
        Reason.UNSUPPORTED_INPUT,
        "this model has no prompt template: send rendered prompts instead"
      );
    }
    if (input.state() == null) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"state\" must be provided");
    }
    List<Question> questions = input.questions();
    validate(questions);
    return dialect instanceof ClefDialect clef
      ? joint(clef, input.state(), questions, includeRaw)
      : separate(input.state(), questions, includeRaw);
  }

  private DecisionResult separate(Object state, List<Question> questions, boolean includeRaw) {
    var prompts = new ArrayList<BackendPrompt>();
    int inputTokens = 0;
    for (Question question : questions) {
      int nOptions = question.options().size();
      int nOutputs = dialect.outputs(question.kind(), nOptions);
      for (int v = 0; v < dialect.variants(question.kind(), nOptions); v++) {
        String rendered = render(dialect.variables(state, questions, question, v, backend));
        int[] tokens = backend.tokenize(rendered);
        inputTokens += tokens.length;
        prompts.add(dialect.prompt(tokens, question.kind(), nOutputs, backend));
      }
    }
    List<float[]> scores = backend.score(prompts);

    var answers = new ArrayList<Answer>(questions.size());
    int next = 0;
    for (Question question : questions) {
      int nVariants = dialect.variants(question.kind(), question.options().size());
      answers.add(
        answer(
          question.id(),
          question.kind(),
          keys(question),
          scores.subList(next, next + nVariants),
          includeRaw
        )
      );
      next += nVariants;
    }
    return new DecisionResult(answers, inputTokens);
  }

  private DecisionResult joint(
    ClefDialect clef,
    Object state,
    List<Question> questions,
    boolean includeRaw
  ) {
    String rendered = render(clef.jointVariables(state, questions));
    BackendPrompt.Clef prompt = clef.jointPrompt(rendered, questions, backend::tokenize);
    float[] scores = backend.score(List.of(prompt)).getFirst();
    int expected = questions
      .stream()
      .mapToInt(q -> q.options().size())
      .sum();
    // copyOfRange pads a short result with zeros, which would read as a near-uniform answer.
    if (scores.length != expected) throw DecisionScoring.unusable(questions.getLast().id());

    var answers = new ArrayList<Answer>(questions.size());
    int offset = 0;
    for (Question question : questions) {
      int n = question.options().size();
      float[] own = Arrays.copyOfRange(scores, offset, offset + n);
      offset += n;
      answers.add(answer(question.id(), question.kind(), keys(question), List.of(own), includeRaw));
    }
    return new DecisionResult(answers, prompt.tokens().length);
  }

  private String render(Map<String, Object> variables) {
    String rendered = switch (source) {
      case PromptSource.ChatWrapped chat -> {
        String user = renderer.renderFromVariables(template, variables).strip();
        yield renderer.render(
          backend.chatTemplate(),
          List.of(new ChatMessage(Role.USER, user, null)),
          null,
          true,
          Map.of("enable_thinking", false)
        ) +
        chat.prefill();
      }
      default -> renderer.renderFromVariables(template, variables);
    };
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Decision prompt ({} chars):\n{}", rendered.length(), rendered);
    }
    return rendered;
  }

  // ── raw ──────────────────────────────────────────────────────────────────

  private DecisionResult raw(DecisionInput.Raw input, boolean includeRaw) {
    if (!capabilities.raw()) {
      throw new DecisionException(
        Reason.UNSUPPORTED_INPUT,
        "a " + backend.family().wireName() + " model decides jointly: send questions, not prompts"
      );
    }
    List<RawPrompt> rawPrompts = input.prompts();
    if (rawPrompts.isEmpty()) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"prompts\" must not be empty");
    }
    var ids = new HashSet<String>();
    var prompts = new ArrayList<BackendPrompt>(rawPrompts.size());
    var keys = new ArrayList<List<String>>(rawPrompts.size());
    int inputTokens = 0;
    for (RawPrompt raw : rawPrompts) {
      String id = raw.questionId();
      if (id == null || id.isBlank() || !ids.add(id)) {
        throw new DecisionException(
          Reason.INVALID_REQUEST,
          "prompt ids must be unique and not blank"
        );
      }
      if (raw.kind() == null) throw DecisionException.invalid(
        id,
        "\"type\" must be one of: choice, score, noul"
      );
      if ((raw.text() == null) == (raw.tokens() == null)) {
        throw DecisionException.invalid(
          id,
          "exactly one of the prompt text and tokens must be set"
        );
      }
      List<String> optionKeys = rawKeys(raw);
      checkOptionCount(id, raw.kind(), optionKeys.size());
      int[] tokens = raw.text() != null ? backend.tokenize(raw.text()) : raw.tokens();
      if (tokens.length == 0) throw DecisionException.invalid(id, "the prompt must not be empty");
      inputTokens += tokens.length;
      prompts.add(
        dialect.prompt(tokens, raw.kind(), dialect.outputs(raw.kind(), optionKeys.size()), backend)
      );
      keys.add(optionKeys);
    }
    List<float[]> scores = backend.score(prompts);
    var answers = new ArrayList<Answer>(rawPrompts.size());
    for (int i = 0; i < rawPrompts.size(); i++) {
      RawPrompt raw = rawPrompts.get(i);
      answers.add(
        answer(raw.questionId(), raw.kind(), keys.get(i), List.of(scores.get(i)), includeRaw)
      );
    }
    return new DecisionResult(answers, inputTokens);
  }

  private List<String> rawKeys(RawPrompt raw) {
    return switch (raw.kind()) {
      case NOUL -> dialect.noulTrueFirst() ? List.of("true", "false") : List.of("false", "true");
      case CHOICE, SCORE -> {
        if (new HashSet<>(raw.optionKeys()).size() != raw.optionKeys().size()) {
          throw DecisionException.invalid(raw.questionId(), "option keys must be unique");
        }
        yield raw.optionKeys();
      }
    };
  }

  // ── shared ───────────────────────────────────────────────────────────────

  private Answer answer(
    String id,
    QuestionKind kind,
    List<String> keys,
    List<float[]> scores,
    boolean includeRaw
  ) {
    return DecisionScoring.answer(
      id,
      kind,
      keys,
      scores,
      temperatures.of(kind, keys.size()),
      dialect.noulRating(),
      includeRaw
    );
  }

  private List<String> keys(Question question) {
    return dialect.promptOptions(question).stream().map(Option::key).toList();
  }

  private void validate(List<Question> questions) {
    DecisionQuestions.validate(questions);
    for (Question question : questions) {
      checkOptionCount(question.id(), question.kind(), question.options().size());
    }
  }

  private void checkOptionCount(String id, QuestionKind kind, int n) {
    DecisionQuestions.checkCount(id, kind, n);
    int outputs = dialect.outputs(kind, n);
    if (Math.max(n, outputs) > capabilities.maxOptions()) {
      throw DecisionException.invalid(
        id,
        "too many options (" + n + "), this model supports at most " + capabilities.maxOptions()
      );
    }
  }

  private static String readResource(String name) {
    try (InputStream in = DecisionRunner.class.getResourceAsStream(name)) {
      if (in == null) throw new IllegalStateException("missing resource " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    backend.close();
  }
}
