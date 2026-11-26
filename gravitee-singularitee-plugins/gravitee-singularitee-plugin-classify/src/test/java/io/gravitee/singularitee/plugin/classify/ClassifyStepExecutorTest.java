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
package io.gravitee.singularitee.plugin.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A classify step that brings its own labels sends them to its zero-shot model on every call. */
class ClassifyStepExecutorTest {

  /**
   * A zero-shot model: a label scores high when the text mentions it. Without request labels it
   * answers with its own label set.
   */
  static final class FakeZeroShot implements ClassifierEngine {

    static final List<ClassifyLabel> OWN = List.of(new ClassifyLabel("model-label", ""));

    final List<List<ClassifyLabel>> calls = new CopyOnWriteArrayList<>();

    @Override
    public boolean acceptsRequestLabels() {
      return true;
    }

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return rxClassify(request, List.of());
    }

    @Override
    public Single<ClassifyResponse> rxClassify(
      ClassifyRequest request,
      List<ClassifyLabel> labels
    ) {
      calls.add(labels);
      List<ClassifyLabel> scored = labels.isEmpty() ? OWN : labels;
      String text = request.text().toLowerCase(Locale.ROOT);
      Map<String, Float> scores = new LinkedHashMap<>();
      String top = null;
      float best = -1f;
      for (ClassifyLabel label : scored) {
        float score = text.contains(label.name().toLowerCase(Locale.ROOT)) ? 0.9f : 0.1f;
        scores.put(label.name(), score);
        if (score > best) {
          best = score;
          top = label.name();
        }
      }
      return Single.just(new ClassifyResponse(top, best, scores, List.of()));
    }

    @Override
    public void close() {}
  }

  private static final List<ClassifyLabel> TOPICS = List.of(
    new ClassifyLabel("shipping", "A question about a delivery."),
    new ClassifyLabel("billing", "A question about an invoice or a payment.")
  );
  private static final List<ClassifyLabel> MOODS = List.of(
    new ClassifyLabel("happy", ""),
    new ClassifyLabel("angry", "")
  );

  private FakeZeroShot gliner;
  private ModelRegistry models;
  private ClassifyStepExecutor executor;

  @BeforeEach
  void setUp() {
    gliner = new FakeZeroShot();
    models = new ModelRegistry();
    models.register("gliner", "gliner", gliner, token -> {});
    executor = new ClassifyStepExecutor(new StepExecutionContext(models, null, null));
  }

  /** The engine both steps resolve: the one model the registry holds under "gliner". */
  private ClassifierEngine model() {
    return (ClassifierEngine) models.get("gliner").orElseThrow().engine();
  }

  private static ClassifyStepConfig step(List<ClassifyLabel> labels) {
    return new ClassifyStepConfig("gliner", "", "", 0f, labels);
  }

  private static StepContext context(PipelineContext pctx) {
    var pipeline = new PipelineModel(
      "p",
      "p",
      "topic",
      List.of(new StepModel("topic", "classify", StepRole.STEP_ROLE_OUTPUT, null)),
      Map.of(),
      "",
      false,
      List.of()
    );
    return new StepContext(pctx, pipeline, null, null);
  }

  private static PipelineContext request(String text) {
    return new PipelineContext(
      text,
      List.of(new ChatTurn(ChatRole.USER, text)),
      null,
      List.of(),
      null
    );
  }

  @Test
  void passes_its_labels_to_the_model_on_every_call() {
    var pctx = request("I am angry about my billing");

    executor.rxExecuteWithEngine("topic", step(TOPICS), model(), context(pctx)).blockingGet();
    executor.rxExecuteWithEngine("topic", step(TOPICS), model(), context(pctx)).blockingGet();

    assertThat(gliner.calls).containsExactly(TOPICS, TOPICS);
    assertThat(pctx.get("topic.label")).isEqualTo("billing");
    assertThat(pctx.get("topic.label.score")).isEqualTo("0.9");
  }

  @Test
  void two_steps_on_one_model_with_different_labels_get_different_results() {
    var pctx = request("I am angry about my billing");

    executor.rxExecuteWithEngine("topic", step(TOPICS), model(), context(pctx)).blockingGet();
    executor.rxExecuteWithEngine("mood", step(MOODS), model(), context(pctx)).blockingGet();

    assertThat(pctx.get("topic.label")).isEqualTo("billing");
    assertThat(pctx.get("mood.label")).isEqualTo("angry");
    assertThat(gliner.calls).containsExactly(TOPICS, MOODS);
    assertThat(models.entries()).hasSize(1);
  }

  @Test
  void without_labels_the_model_classifies_with_its_own_as_before() {
    var engine = mock(ClassifierEngine.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    when(engine.rxClassify(any())).thenReturn(
      Single.just(new ClassifyResponse("toxic", 0.8f, Map.of("toxic", 0.8f), List.of()))
    );
    var registry = new ModelRegistry();
    registry.register("toxicity", "toxicity", engine, token -> {});
    var pctx = request("you are awful");

    new ClassifyStepExecutor(new StepExecutionContext(registry, null, null))
      .rxExecuteWithEngine(
        "tox",
        new ClassifyStepConfig("toxicity", "", "", 0f, List.of()),
        engine,
        context(pctx)
      )
      .blockingGet();

    assertThat(pctx.get("tox.label")).isEqualTo("toxic");
    verify(engine).rxClassify(any());
    verify(engine, never()).rxClassify(any(), anyList());
  }

  @Test
  void the_same_step_answers_from_the_model_labels_when_it_brings_none() {
    var pctx = request("anything");

    executor
      .rxExecuteWithEngine("topic", step(new ArrayList<>()), model(), context(pctx))
      .blockingGet();

    assertThat(pctx.get("topic.label")).isEqualTo("model-label");
  }

  @Test
  void a_label_outside_the_step_labels_is_never_its_answer() {
    var engine = mock(ClassifierEngine.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    when(engine.rxClassify(any(), anyList())).thenReturn(
      Single.just(
        new ClassifyResponse(
          "ALPHA",
          0.99f,
          Map.of("ALPHA", 0.99f, "billing", 0.6f, "shipping", 0.1f),
          List.of()
        )
      )
    );
    var pctx = request("ALPHA billing");

    executor.rxExecuteWithEngine("topic", step(TOPICS), engine, context(pctx)).blockingGet();

    assertThat(pctx.get("topic.label")).isEqualTo("billing");
    assertThat(pctx.get("topic.label.score")).isEqualTo("0.6");
  }
}
