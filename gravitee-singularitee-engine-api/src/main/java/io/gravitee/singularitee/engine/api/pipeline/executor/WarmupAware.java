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

import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.reactivex.rxjava3.core.Completable;

/**
 * A step executor that pre-computes state for a pipeline at registration time (for
 * example the reference embeddings of a KNN route step). The factory runs every
 * warm-up-aware handler when a pipeline is registered, without knowing the step type.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface WarmupAware {
  /** Warms this executor up for the given pipeline; completes when the state is ready. */
  Completable rxWarmup(PipelineModel pipeline);
}
