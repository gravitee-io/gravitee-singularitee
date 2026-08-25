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
package io.gravitee.singularitee.plugin.api;

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;

/**
 * A gravitee plugin of type {@code step-decorator}: contributes a cross-cutting decorator
 * applied to EVERY step, inside the platform decorators (tracing, diagnostics) so it is
 * itself observed. Ordered among plugin decorators by the manifest's {@code priority}
 * (lower runs outer); licensed through the manifest's {@code feature}, like steps.
 *
 * <p>Implementations need a public no-arg constructor.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface StepDecoratorPlugin {
  StepExecutorDecorator create(StepExecutorServices services);
}
