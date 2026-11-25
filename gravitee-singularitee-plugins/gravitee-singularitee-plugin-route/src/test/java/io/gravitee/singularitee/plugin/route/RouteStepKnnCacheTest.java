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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.EmbedRequest;
import io.gravitee.singularitee.engine.api.EmbedResponse;
import io.gravitee.singularitee.engine.api.EmbeddingEngine;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The KNN reference embeddings of a route step must follow its references across an in-place swap. */
class RouteStepKnnCacheTest {

  private final AtomicInteger embeds = new AtomicInteger();
  private RouteStepExecutor executor;

  @BeforeEach
  void setUp() {
    EmbeddingEngine engine = new EmbeddingEngine() {
      @Override
      public Single<EmbedResponse> rxEmbed(EmbedRequest request) {
        embeds.incrementAndGet();
        float[] vector = request.text().startsWith("a")
          ? new float[] { 1, 0 }
          : new float[] { 0, 1 };
        return Single.just(new EmbedResponse(vector, 1));
      }

      @Override
      public void close() {}
    };
    var models = new ModelRegistry();
    models.register("embedder", "embedder", engine, token -> {});
    executor = new RouteStepExecutor(new StepExecutionContext(models, null, null));
  }

  private static RouteStepConfig config(String sentenceOfX, String sentenceOfY) {
    return new RouteStepConfig(
      "embedder",
      RoutingStrategy.EMBEDDING_KNN,
      "",
      "",
      List.of(
        new RouteRule("X", List.of(sentenceOfX), "to_x"),
        new RouteRule("Y", List.of(sentenceOfY), "to_y")
      ),
      0,
      List.of()
    );
  }

  private static PipelineModel pipeline(RouteStepConfig config) {
    return new PipelineModel(
      "p",
      "p",
      "route",
      List.of(new StepModel("route", "route", StepRole.STEP_ROLE_INTERNAL, config)),
      Map.of(),
      "",
      false,
      List.of()
    );
  }

  private String route(RouteStepConfig config) {
    var pctx = new PipelineContext(
      "a-query",
      List.of(new ChatTurn(ChatRole.USER, "a-query")),
      null,
      List.of(),
      null
    );
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.pipeline()).thenReturn(pipeline(config));
    when(ctx.stepScribe()).thenReturn(SpanScribe.NOOP);
    return executor.execute("route", config, ctx).blockingGet();
  }

  @Test
  void a_swapped_route_uses_its_new_references_before_warm_up_finishes() {
    var before = config("a-ref", "b-ref");
    var after = config("b-ref", "a-ref");
    executor.rxWarmup(pipeline(before)).blockingAwait();
    assertThat(route(before)).isEqualTo("to_x");

    assertThat(route(after)).isEqualTo("to_y");
  }

  @Test
  void a_swapped_route_keeps_using_the_new_references_after_warm_up() {
    var before = config("a-ref", "b-ref");
    var after = config("b-ref", "a-ref");
    executor.rxWarmup(pipeline(before)).blockingAwait();
    executor.rxWarmup(pipeline(after)).blockingAwait();

    assertThat(route(after)).isEqualTo("to_y");
  }

  @Test
  void unchanged_references_are_embedded_once() {
    var config = config("a-ref", "b-ref");
    executor.rxWarmup(pipeline(config)).blockingAwait();
    int afterWarmUp = embeds.get();

    route(config);
    route(config);

    assertThat(embeds.get() - afterWarmUp).isEqualTo(2);
  }

  @Test
  void a_forgotten_pipeline_embeds_its_references_again() {
    var config = config("a-ref", "b-ref");
    executor.rxWarmup(pipeline(config)).blockingAwait();
    route(config);
    int warm = embeds.get();

    executor.forget("p");
    route(config);

    assertThat(embeds.get() - warm).isEqualTo(3);
  }

  @Test
  void forgetting_another_pipeline_keeps_the_references() {
    var config = config("a-ref", "b-ref");
    executor.rxWarmup(pipeline(config)).blockingAwait();
    int warm = embeds.get();

    executor.forget("other");
    route(config);

    assertThat(embeds.get() - warm).isEqualTo(1);
  }
}
