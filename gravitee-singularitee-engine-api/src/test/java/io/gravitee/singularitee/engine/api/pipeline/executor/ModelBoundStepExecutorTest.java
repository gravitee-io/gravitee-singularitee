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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link ModelBoundStepExecutor}: runs a model-bound step with no tracer, spans or metrics. */
class ModelBoundStepExecutorTest {

  private static final class StubClassifier implements ClassifierEngine {

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.just(new ClassifyResponse("a", 1f, Map.of("a", 1f), List.of()));
    }

    @Override
    public void close() {}
  }

  private static final class EchoExecutor extends ModelBoundStepExecutor<String, ClassifierEngine> {

    EchoExecutor(StepExecutionContext execContext) {
      super(execContext);
    }

    @Override
    protected String getModelId(String config) {
      return config;
    }

    @Override
    protected Class<ClassifierEngine> engineType() {
      return ClassifierEngine.class;
    }

    @Override
    protected Maybe<String> rxExecuteWithEngine(
      String stepId,
      String config,
      ClassifierEngine engine,
      StepContext ctx
    ) {
      return Maybe.just("next");
    }
  }

  private static EchoExecutor executor() {
    var registry = new ModelRegistry();
    registry.register("m", "stub", new StubClassifier(), null);
    return new EchoExecutor(new StepExecutionContext(registry, null, null));
  }

  private static PipelineContext pipelineContext() {
    return new PipelineContext("hello", null, null, null, null);
  }

  @Test
  void runs_without_a_tracer() {
    var ctx = new StepContext(pipelineContext(), null, null, null);

    executor().execute("s1", "m", ctx).test().assertValue("next").assertComplete();
  }

  @Test
  void runs_when_the_context_has_no_active_step_span_holder() {
    var ctx = new StepContext(pipelineContext(), null, null, null, null, null, null, null);

    executor().execute("s1", "m", ctx).test().assertValue("next").assertComplete();
  }

  @Test
  void a_null_active_step_span_holder_is_replaced_by_an_empty_one() {
    var ctx = new StepContext(pipelineContext(), null, null, null, null, null, null, null);

    assertThat(ctx.activeStepSpan()).isNotNull();
    assertThat(ctx.activeStepSpan()).hasValue(null);
    assertThat(ctx.activeStepSpan()).isInstanceOf(AtomicReference.class);
  }
}
