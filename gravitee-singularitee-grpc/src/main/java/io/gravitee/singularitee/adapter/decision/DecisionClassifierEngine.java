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
package io.gravitee.singularitee.adapter.decision;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ClassifyResult;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.ModelEngineType;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.protocol.ModelType;
import io.gravitee.singularitee.workspace.config.DecisionClassifierQuestion;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A decision model that is also a classifier: classifying a text asks it one choice question, the
 * labels being the option keys and the scores their probabilities. Every step and route that takes
 * a classifier then takes the decision model unchanged.
 *
 * <p>The labels are the workspace's, or the request's when it sends any: the model reads them as
 * options, descriptions included, so any label set works without retraining (zero-shot). A
 * classifier declared without options takes its labels from every request.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecisionClassifierEngine
  implements DecisionEngine, ClassifierEngine, ProtoTypedEngine {

  /** The question a classification asks when the workspace sets no instructions. */
  static final String DEFAULT_INSTRUCTIONS = "Which label fits the text?";

  /** The id of the question a classification asks. */
  static final String QUESTION_ID = "label";

  private final DecisionEngine delegate;
  private final Object instructions;
  /** The workspace labels; empty when every request must send its own. */
  private final List<Question.Option> options;

  /**
   * @param delegate     the decision model
   * @param instructions the question a classification asks
   * @param options      the default labels, empty for a classifier whose labels come per request
   */
  public DecisionClassifierEngine(
    DecisionEngine delegate,
    Object instructions,
    List<Question.Option> options
  ) {
    this.delegate = delegate;
    this.instructions = instructions == null ? DEFAULT_INSTRUCTIONS : instructions;
    this.options = List.copyOf(options);
  }

  /**
   * {@code engine} published as a classifier too when the workspace declares
   * {@code as_classifier:}, else {@code engine} itself.
   */
  public static DecisionEngine wrap(DecisionEngine engine, DecisionClassifierQuestion classifier) {
    if (classifier == null) return engine;
    var options = new ArrayList<Question.Option>();
    if (classifier.options() != null) {
      classifier
        .options()
        .forEach((key, description) -> options.add(new Question.Option(key, description)));
    }
    return new DecisionClassifierEngine(engine, classifier.instructions(), options);
  }

  @Override
  public ModelType protoModelType() {
    return delegate instanceof ProtoTypedEngine typed
      ? typed.protoModelType()
      : ModelType.MODEL_TYPE_LLAMA_CPP_DECISION;
  }

  @Override
  public ModelEngineType type() {
    return ModelEngineType.DECISION;
  }

  @Override
  public String task() {
    return ModelTasks.DECISION;
  }

  @Override
  public DecisionCapabilities capabilities() {
    return delegate.capabilities();
  }

  @Override
  public Single<DecisionResult> rxDecide(DecisionRequest request) {
    return delegate.rxDecide(request);
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return rxClassify(request, List.of());
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request, List<ClassifyLabel> labels) {
    return Single.defer(() -> classify(request, question(labels)));
  }

  @Override
  public Single<List<ClassifyResponse>> rxClassifyBatch(
    List<ClassifyRequest> requests,
    List<ClassifyLabel> labels
  ) {
    return Single.defer(() -> {
      Question.Choice question = question(labels);
      // All at once: the decision runtime batches concurrent questions. Responses keep input order.
      return Flowable.fromIterable(requests)
        .concatMapEager(request -> classify(request, question).toFlowable())
        .toList();
    });
  }

  /** The choice question over the request's labels, or the workspace's when it sends none. */
  private Question.Choice question(List<ClassifyLabel> labels) {
    if (labels == null || labels.isEmpty()) {
      if (options.isEmpty()) {
        throw invalidLabels(
          "this classifier takes its labels from the request; send at least one label"
        );
      }
      return new Question.Choice(QUESTION_ID, instructions, options);
    }
    var seen = new HashSet<String>();
    var requested = new ArrayList<Question.Option>(labels.size());
    for (ClassifyLabel label : labels) {
      if (!seen.add(label.name())) {
        throw invalidLabels("duplicate label '" + label.name() + "'");
      }
      String description = label.description();
      requested.add(
        new Question.Option(
          label.name(),
          description == null || description.isBlank() ? null : description
        )
      );
    }
    return new Question.Choice(QUESTION_ID, instructions, requested);
  }

  private static DecisionException invalidLabels(String message) {
    return new DecisionException(DecisionException.Reason.INVALID_REQUEST, "labels: " + message);
  }

  private Single<ClassifyResponse> classify(ClassifyRequest request, Question.Choice question) {
    var decision = new DecisionRequest(
      new DecisionInput.Templated(request.text(), List.of(question)),
      false
    );
    return delegate
      .rxDecide(decision)
      .map(result -> {
        var answer = (Answer.Choice) result.answers().getFirst();
        List<Answer.OptionProbability> ranked = answer
          .probabilities()
          .stream()
          .sorted(Comparator.comparingDouble(Answer.OptionProbability::probability).reversed())
          .toList();
        var all = new LinkedHashMap<String, Float>();
        ranked.forEach(p -> all.put(p.key(), (float) p.probability()));
        return new ClassifyResponse(
          ranked.getFirst().key(),
          (float) ranked.getFirst().probability(),
          all,
          ranked
            .stream()
            .map(p -> new ClassifyResult(p.key(), (float) p.probability()))
            .toList()
        );
      });
  }

  @Override
  public void close() throws Exception {
    delegate.close();
  }
}
