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
package io.gravitee.singularitee.plugin.route;

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
import io.gravitee.singularitee.plugin.route.RouteStepConfig.RouteRule;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A classifier route that brings its own labels routes on the zero-shot model's answer to them. */
class RouteStepClassifierLabelsTest {

  private static final List<ClassifyLabel> LABELS = List.of(
    new ClassifyLabel("billing", "Invoices and payments."),
    new ClassifyLabel("tech", "Something does not work.")
  );

  private static final List<RouteRule> RULES = List.of(
    new RouteRule("billing", List.of(), "to_billing"),
    new RouteRule("tech", List.of(), "to_tech")
  );

  /** Picks the last label it is given; with none it answers its own "other". */
  private static final class LastLabel implements ClassifierEngine {

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
      String top = labels.isEmpty() ? "other" : labels.getLast().name();
      return Single.just(new ClassifyResponse(top, 0.9f, Map.of(top, 0.9f), List.of()));
    }

    @Override
    public void close() {}
  }

  private static String route(ClassifierEngine engine, RouteStepConfig config) {
    var models = new ModelRegistry();
    models.register("gliner", "gliner", engine, token -> {});
    var pctx = new PipelineContext(
      "my invoice is wrong",
      List.of(new ChatTurn(ChatRole.USER, "my invoice is wrong")),
      null,
      List.of(),
      null
    );
    var pipeline = new PipelineModel(
      "p",
      "p",
      "route",
      List.of(new StepModel("route", "route", StepRole.STEP_ROLE_INTERNAL, config)),
      Map.of(),
      "",
      false,
      List.of()
    );
    return new RouteStepExecutor(new StepExecutionContext(models, null, null))
      .execute("route", config, new StepContext(pctx, pipeline, null, null))
      .blockingGet();
  }

  private static RouteStepConfig config(List<ClassifyLabel> labels) {
    return new RouteStepConfig(
      "gliner",
      RoutingStrategy.CLASSIFIER,
      "",
      "fallback",
      RULES,
      0,
      labels
    );
  }

  @Test
  void a_classifier_route_sends_its_labels_and_routes_on_the_answer() {
    var engine = new LastLabel();

    assertThat(route(engine, config(LABELS))).isEqualTo("to_tech");
    assertThat(engine.calls).containsExactly(LABELS);
  }

  @Test
  void without_labels_a_classifier_route_classifies_with_the_model_labels_as_before() {
    var engine = mock(ClassifierEngine.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    when(engine.rxClassify(any())).thenReturn(
      Single.just(new ClassifyResponse("billing", 0.9f, Map.of("billing", 0.9f), List.of()))
    );

    assertThat(route(engine, config(List.of()))).isEqualTo("to_billing");
    verify(engine).rxClassify(any());
    verify(engine, never()).rxClassify(any(), anyList());
  }
}
