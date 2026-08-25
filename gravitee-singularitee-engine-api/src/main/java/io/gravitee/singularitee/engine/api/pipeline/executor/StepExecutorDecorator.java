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

import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.reactivex.rxjava3.core.Maybe;

/**
 * A cross-cutting layer around step execution. The dispatcher wraps every executor,
 * built-in or plugin-supplied, in the same ordered chain of decorators: platform
 * decorators (tracing, diagnostics) outermost and fixed, plugin decorators inside them.
 *
 * <p>A decorator is additive: it may observe or enrich the invocation but must hand the
 * same {@link Maybe} contract back (the next step id, empty for terminal). A decorator
 * that throws fails the step exactly like an executor failure, inside the span.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@FunctionalInterface
public interface StepExecutorDecorator {
  Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next);
}
