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
package io.gravitee.singularitee.adapter.gliner;

import io.gravitee.lab.gliner4j.schema.ClassificationLabel;
import io.gravitee.lab.gliner4j.schema.Decision;
import io.gravitee.lab.gliner4j.schema.DecisionQuestion;
import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.adapter.decision.ProtoTypedEngine;
import io.gravitee.singularitee.engine.api.DecisionEngine;
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
import io.gravitee.singularitee.inference.decision.DecisionScoring;
import io.gravitee.singularitee.inference.decision.DecisionText;
import io.gravitee.singularitee.protocol.ModelType;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * {@link DecisionEngine} over a GLiNER2 bundle (GLiNER2.5-Decide): each question is one
 * classification task about the state, the question id and instructions its prompt and the
 * options its labels, and the label probabilities become the answer of the question's type.
 *
 * <p>Takes a state and questions only: the model has no text prompt to render, so raw prompts are
 * refused. Calls run one at a time on the worker pool.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerDecisionEngine
  extends BlockingEngineAdapter<GlinerDecisionEngine.Decider>
  implements DecisionEngine, ProtoTypedEngine {

  /** Most options one question may have: every label shares the encoder window with the state. */
  static final int MAX_OPTIONS = 64;

  private static final DecisionCapabilities CAPABILITIES = new DecisionCapabilities(
    DecisionFamily.GLINER,
    MAX_OPTIONS,
    true,
    false
  );

  /** The model: one probability per option of a question, keyed by option label. */
  interface Decider extends AutoCloseable {
    Decision decide(String state, DecisionQuestion question);

    @Override
    void close();
  }

  GlinerDecisionEngine(Decider decider, Vertx vertx) {
    super(decider, vertx, true);
  }

  @Override
  public ModelType protoModelType() {
    return ModelType.MODEL_TYPE_GLINER_DECISION;
  }

  @Override
  public DecisionCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public Single<DecisionResult> rxDecide(DecisionRequest request) {
    return rxInfer(() -> decide(request));
  }

  DecisionResult decide(DecisionRequest request) {
    if (!(request.input() instanceof DecisionInput.Templated input)) {
      throw new DecisionException(
        Reason.UNSUPPORTED_INPUT,
        "a gliner model reads a state and questions, not rendered prompts"
      );
    }
    if (input.state() == null) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"state\" must be provided");
    }
    String state = DecisionText.of(input.state());
    if (state.isBlank()) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"state\" must not be blank");
    }
    DecisionQuestions.validate(input.questions());

    var answers = new ArrayList<Answer>(input.questions().size());
    for (Question question : input.questions()) {
      answers.add(answer(state, question, request.includeRawScores()));
    }
    return new DecisionResult(answers, 0);
  }

  private Answer answer(String state, Question question, boolean includeRaw) {
    String id = question.id();
    if (question.options().size() > MAX_OPTIONS) {
      throw DecisionException.invalid(
        id,
        "too many options (" +
          question.options().size() +
          "), this model supports at most " +
          MAX_OPTIONS
      );
    }
    String instructions = DecisionText.of(question.instructions());
    // Option keys in answer order, and the label the model scores for each.
    List<String> keys;
    List<String> labels;
    DecisionQuestion asked;
    switch (question) {
      case Question.Choice choice -> {
        keys = choice.options().stream().map(Question.Option::key).toList();
        labels = keys;
        asked = new DecisionQuestion.Choice(
          id,
          instructions,
          choice
            .options()
            .stream()
            .map(o -> new ClassificationLabel(o.key(), DecisionText.of(o.description())))
            .toList()
        );
      }
      case Question.Score score -> {
        keys = score.options().stream().map(Question.Option::key).toList();
        labels = score.levels().stream().map(DecisionText::of).toList();
        if (new HashSet<>(labels).size() != labels.size() || labels.contains("")) {
          throw DecisionException.invalid(id, "score levels must be distinct and not blank");
        }
        asked = new DecisionQuestion.Score(id, instructions, labels);
      }
      case Question.Noul noul -> {
        keys = List.of("true", "false");
        labels = List.of(DecisionQuestion.Noul.YES, DecisionQuestion.Noul.NO);
        asked = new DecisionQuestion.Noul(
          id,
          instructions,
          DecisionText.of(noul.whenTrue()),
          DecisionText.of(noul.whenFalse())
        );
      }
    }

    Decision decision = delegate.decide(state, asked);
    if (decision == null) {
      throw new DecisionException(
        Reason.EVALUATION_FAILED,
        "the model could not evaluate the decision (questions." + id + ")"
      );
    }
    // Log-probabilities: a softmax at temperature 1 gives the model's probabilities back.
    float[] scores = new float[labels.size()];
    for (int i = 0; i < scores.length; i++) {
      Float p = decision.probabilities().get(labels.get(i));
      scores[i] = p == null ? Float.NaN : (float) Math.log(Math.max(p, Float.MIN_NORMAL));
    }
    return DecisionScoring.answer(
      id,
      question.kind(),
      keys,
      List.of(scores),
      1f,
      false,
      includeRaw
    );
  }

  @Override
  public void close() {
    delegate.close();
  }
}
