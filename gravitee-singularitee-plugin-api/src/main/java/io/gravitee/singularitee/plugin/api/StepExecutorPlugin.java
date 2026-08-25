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

import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;

/**
 * A gravitee plugin of type {@code step}: the {@code class=} of its {@code plugin.properties}
 * names an implementation of this interface, which contributes one step type, its config
 * codec (the YAML block a workspace writes under {@code config:}) and its executor, built
 * once per server against the parent-owned {@link StepExecutorServices}.
 *
 * <p>Licensing is the manifest's: a {@code feature=} entry in {@code plugin.properties}
 * makes the step available only when the platform license enables that feature. Core
 * steps declare none.
 *
 * <p>Implementations need a public no-arg constructor. A plugin is a decorator over the
 * engine: it may only use parent-loaded APIs, never loads a model or a native library,
 * and cannot claim a step type another plugin already provides.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface StepExecutorPlugin {
  /** The step type string a workspace writes as {@code type:}. */
  String type();

  /** Parses and validates this step's YAML config block. */
  StepConfigCodec<?> codec();

  /** Builds the executor from the parent-owned services. */
  StepExecutor<?> create(StepExecutorServices services);
}
