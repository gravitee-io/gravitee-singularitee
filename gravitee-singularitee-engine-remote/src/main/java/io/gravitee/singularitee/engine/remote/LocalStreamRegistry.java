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
package io.gravitee.singularitee.engine.remote;

import io.gravitee.singularitee.pipeline.executor.StreamRegistry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StreamRegistry} for client-side pipeline execution.
 *
 * <p>Maintains per-model stream maps so that {@code InferStepExecutor} can
 * register its {@code TokenCaptureStream}. The remote engine's {@code tokenConsumer}
 * dispatches tokens into these maps.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LocalStreamRegistry implements StreamRegistry {

  private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, StreamContext>> streams =
    new ConcurrentHashMap<>();

  @Override
  public ConcurrentHashMap<Integer, StreamContext> streamsForModel(String modelId) {
    return streams.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>());
  }
}
