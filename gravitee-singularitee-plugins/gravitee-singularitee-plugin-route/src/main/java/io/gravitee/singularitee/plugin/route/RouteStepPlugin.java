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

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.plugin.api.StepExecutorPlugin;
import io.gravitee.singularitee.plugin.api.StepExecutorServices;

/**
 * The {@code route} step plugin: dispatches to a step chosen by a model.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RouteStepPlugin implements StepExecutorPlugin {

  @Override
  public String type() {
    return StepTypes.ROUTE;
  }

  @Override
  public StepConfigCodec<?> codec() {
    return new RouteStepCodec();
  }

  @Override
  public StepExecutor<?> create(StepExecutorServices services) {
    return new RouteStepExecutor(services.executionContext(), services.cacheManager());
  }
}
