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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionCapabilities;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.gravitee.singularitee.inference.api.decision.Question;
import io.gravitee.singularitee.workspace.config.DecisionClassifierQuestion;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A decision model published as a classifier: workspace labels, request labels (zero-shot). */
class DecisionClassifierEngineTest {

  /** Answers every choice with the last option most probable, and records what it was asked. */
  private static final class RecordingModel implements DecisionEngine {

    final List<Question.Choice> asked = new ArrayList<>();

    @Override
    public DecisionCapabilities capabilities() {
      return new DecisionCapabilities(DecisionFamily.CUSTOM, 26, true, true);
    }

    @Override
    public Single<DecisionResult> rxDecide(DecisionRequest request) {
      var question =
        (Question.Choice) ((DecisionInput.Templated) request.input()).questions().getFirst();
      asked.add(question);
      int n = question.options().size();
      var probabilities = new ArrayList<Answer.OptionProbability>();
      for (int i = 0; i < n; i++) {
        double p = i == n - 1 ? 0.7 : 0.3 / Math.max(1, n - 1);
        probabilities.add(
          new Answer.OptionProbability(question.options().get(i).key(), n == 1 ? 1.0 : p)
        );
      }
      String best = question.options().getLast().key();
      return Single.just(
        new DecisionResult(
          List.of(new Answer.Choice(question.id(), best, probabilities, 0.5, List.of())),
          10
        )
      );
    }

    @Override
    public void close() {}
  }

  /** Holds every decision open until the test completes it, answering the request text. */
  private static final class PendingModel implements DecisionEngine {

    final List<SingleSubject<DecisionResult>> pending = new ArrayList<>();
    final List<String> texts = new ArrayList<>();

    @Override
    public DecisionCapabilities capabilities() {
      return new DecisionCapabilities(DecisionFamily.CUSTOM, 26, true, true);
    }

    @Override
    public Single<DecisionResult> rxDecide(DecisionRequest request) {
      texts.add(String.valueOf(((DecisionInput.Templated) request.input()).state()));
      var subject = SingleSubject.<DecisionResult>create();
      pending.add(subject);
      return subject;
    }

    void answer(int index, String key) {
      pending
        .get(index)
        .onSuccess(
          new DecisionResult(
            List.of(
              new Answer.Choice(
                "label",
                key,
                List.of(new Answer.OptionProbability(key, 1.0)),
                1.0,
                List.of()
              )
            ),
            1
          )
        );
    }

    @Override
    public void close() {}
  }

  private static Map<String, Object> options(String... keys) {
    var map = new LinkedHashMap<String, Object>();
    for (String key : keys) map.put(key, key + " issues");
    return map;
  }

  @Test
  void a_batch_asks_every_question_at_once_and_keeps_request_order() {
    var model = new PendingModel();
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      model,
      new DecisionClassifierQuestion("Which team?", options("billing", "shipping"))
    );

    var batch = engine
      .rxClassifyBatch(List.of(new ClassifyRequest("billing"), new ClassifyRequest("shipping")))
      .test();
    assertThat(model.texts).as("both asked before either answers").hasSize(2);

    model.answer(1, "shipping");
    model.answer(0, "billing");

    batch.assertValue(responses ->
      responses
        .stream()
        .map(r -> r.topLabel())
        .toList()
        .equals(List.of("billing", "shipping"))
    );
  }

  @Test
  void classifies_on_the_workspace_labels_by_default() {
    var model = new RecordingModel();
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      model,
      new DecisionClassifierQuestion("Which team?", options("billing", "shipping"))
    );

    var response = engine.rxClassify(new ClassifyRequest("charged twice")).blockingGet();

    assertThat(response.topLabel()).isEqualTo("shipping");
    assertThat(response.allScores()).containsOnlyKeys("billing", "shipping");
    assertThat(model.asked.getFirst().instructions()).isEqualTo("Which team?");
  }

  @Test
  void request_labels_replace_the_workspace_labels() {
    var model = new RecordingModel();
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      model,
      new DecisionClassifierQuestion("Which team?", options("billing", "shipping"))
    );

    var response = engine
      .rxClassify(
        new ClassifyRequest("my password no longer works"),
        List.of(new ClassifyLabel("account", "login or access"), new ClassifyLabel("other", ""))
      )
      .blockingGet();

    assertThat(response.topLabel()).isEqualTo("other");
    assertThat(response.allScores()).containsOnlyKeys("account", "other");
    var asked = model.asked.getFirst().options();
    assertThat(asked.get(0).description()).isEqualTo("login or access");
    assertThat(asked.get(1).description()).as("a blank description is no description").isNull();
  }

  @Test
  void a_classifier_without_options_is_zero_shot_only() {
    var model = new RecordingModel();
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      model,
      new DecisionClassifierQuestion(null, null)
    );

    var refused = engine.rxClassify(new ClassifyRequest("hello")).test();
    refused.assertError(
      e ->
        e instanceof DecisionException d &&
        d.reason() == DecisionException.Reason.INVALID_REQUEST &&
        d.getMessage().startsWith("labels: ")
    );

    var response = engine
      .rxClassify(new ClassifyRequest("hello"), List.of(new ClassifyLabel("greeting", null)))
      .blockingGet();
    assertThat(response.topLabel()).isEqualTo("greeting");
    assertThat(model.asked.getFirst().instructions()).isEqualTo(
      DecisionClassifierEngine.DEFAULT_INSTRUCTIONS
    );
  }

  @Test
  void duplicate_request_labels_are_refused() {
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      new RecordingModel(),
      new DecisionClassifierQuestion(null, null)
    );

    engine
      .rxClassify(
        new ClassifyRequest("hello"),
        List.of(new ClassifyLabel("a", null), new ClassifyLabel("a", null))
      )
      .test()
      .assertError(e -> e.getMessage().equals("labels: duplicate label 'a'"));
  }

  @Test
  void a_batch_asks_every_text_over_the_request_labels() {
    var model = new RecordingModel();
    var engine = (ClassifierEngine) DecisionClassifierEngine.wrap(
      model,
      new DecisionClassifierQuestion("Which team?", options("billing", "shipping"))
    );

    var responses = engine
      .rxClassifyBatch(
        List.of(new ClassifyRequest("one"), new ClassifyRequest("two")),
        List.of(
          new ClassifyLabel("x", null),
          new ClassifyLabel("y", null),
          new ClassifyLabel("z", null)
        )
      )
      .blockingGet();

    assertThat(responses)
      .hasSize(2)
      .allSatisfy(r -> assertThat(r.topLabel()).isEqualTo("z"));
    assertThat(model.asked).allSatisfy(q -> assertThat(q.options()).hasSize(3));
  }
}
