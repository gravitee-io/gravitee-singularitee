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

import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.workspace.ModelLoadRequest;

/**
 * Creates a {@link ModelEngine} from a {@link ModelLoadRequest}.
 *
 * This is the only factory interface that code outside the adapter package is
 * allowed to reference. The concrete implementations each know about exactly
 * one external inference library; no external type ever leaks through this interface.
 *
 * The architectural boundary is enforced by package structure: only the sub-packages
 * of adapter may import gravitee-inference-* types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface ModelEngineFactory {
  /**
   * Creates and returns a model engine configured from the load request.
   * The engine is returned before it is started — the ModelRegistry is responsible
   * for starting text-gen engines.
   *
   * @param request the model load request from the workspace loader
   * @return a new model engine, ready to be started or used directly
   * @throws Exception if engine construction fails (e.g. model file not found)
   */
  ModelEngine create(ModelLoadRequest request) throws Exception;
}
