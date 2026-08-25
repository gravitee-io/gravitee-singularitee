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
package io.gravitee.singularitee.engine.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.template.JinjaTemplateRenderer;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A dispatcher over an incomplete core vocabulary is a misassembly, never an inert node:
 * a missing core step plugin fails creation naming the gap, and a type provided twice fails
 * rather than letting one provider shadow the other.
 */
class StepExecutorFactoryTest {

  private static final StepExecutor<Object> NOOP = (id, config, ctx) -> Maybe.empty();

  private static StepExecutorFactory factory() {
    var models = new ModelRegistry();
    return new StepExecutorFactory(
      models,
      new PipelineRegistry(models),
      null,
      new JinjaTemplateRenderer(),
      null
    );
  }

  @Test
  void missing_core_step_fails_dispatcher_creation() {
    var factory = factory();
    factory.addExecutors(Map.of(StepTypes.INFER, NOOP));

    assertThatThrownBy(factory::createDispatcher)
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Core step types without an executor")
      .hasMessageContaining(StepTypes.GUARD)
      .hasMessageNotContaining("'" + StepTypes.INFER + "'");
  }

  @Test
  void every_core_step_present_creates_the_dispatcher() {
    var factory = factory();
    factory.addExecutors(StepTypes.CORE.stream().collect(Collectors.toMap(t -> t, t -> NOOP)));

    factory.createDispatcher();
  }

  @Test
  void a_step_type_provided_twice_is_misassembly() {
    var factory = factory();
    factory.addExecutors(Map.of(StepTypes.INFER, NOOP));

    assertThatThrownBy(() -> factory.addExecutors(Map.of(StepTypes.INFER, NOOP)))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("provided twice");
  }
}
