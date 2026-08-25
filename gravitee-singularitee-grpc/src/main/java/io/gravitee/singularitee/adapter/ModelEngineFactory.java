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
package io.gravitee.singularitee.adapter;

import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.workspace.ModelLoadRequest;

/**
 * Creates a {@link ModelEngine} from a {@link ModelLoadRequest}.
 *
 * <p>This is the only factory interface that code outside the adapter package may reference.
 * Each implementation knows about exactly one inference library; no library type leaks
 * through this interface. Only the sub-packages of {@code adapter} may import
 * {@code gravitee-inference-*} types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface ModelEngineFactory {
  /**
   * Creates a model engine configured from the load request.
   * The engine is returned before it is started: the model registry starts text-gen engines.
   *
   * @param request the model load request from the workspace loader
   * @return a new model engine, ready to be started or used directly
   * @throws Exception if engine construction fails (e.g. model file not found)
   */
  ModelEngine create(ModelLoadRequest request) throws Exception;
}
