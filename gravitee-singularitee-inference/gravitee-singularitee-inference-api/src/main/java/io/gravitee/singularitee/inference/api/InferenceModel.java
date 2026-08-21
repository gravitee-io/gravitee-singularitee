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
package io.gravitee.singularitee.inference.api;

import java.util.List;

/**
 * Base class of a loaded model that maps one input to one output (classifier, embedder, reranker).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class InferenceModel<CONFIG, INPUT, OUTPUT> {

  protected final CONFIG config;

  protected InferenceModel(CONFIG config) {
    this.config = config;
  }

  /** Runs inference on a single input. */
  public abstract OUTPUT infer(INPUT input);

  /** Runs {@link #infer} on each input in order; engines override this to batch. */
  public List<OUTPUT> inferAll(List<INPUT> input) {
    return input.stream().map(this::infer).toList();
  }

  /** Releases the model's native resources; the instance is unusable afterwards. */
  public abstract void close();
}
