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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.singularitee.protocol.InferResponse;
import io.vertx.core.streams.WriteStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides access to per-model active stream maps.
 *
 * <p>This interface decouples the pipeline executors from the gRPC service
 * implementation. The gRPC module provides the concrete implementation that
 * manages token delivery streams.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface StreamRegistry {
  /**
   * Holds per-sequence stream context: the gRPC WriteStream, the client-provided
   * request ID, the model ID, and whether the CREATED event has been emitted.
   */
  record StreamContext(
    WriteStream<InferResponse> stream,
    String requestId,
    String modelId,
    AtomicBoolean createdEmitted
  ) {
    public StreamContext(WriteStream<InferResponse> stream, String requestId, String modelId) {
      this(stream, requestId, modelId, new AtomicBoolean(false));
    }

    /**
     * Creates a context for pipeline-internal streams where no lifecycle
     * events need to be emitted (the pipeline executor manages its own lifecycle).
     */
    public StreamContext(WriteStream<InferResponse> stream) {
      this(stream, "", "", new AtomicBoolean(true));
    }
  }

  /**
   * Returns the map of active stream contexts for the given model, keyed by sequence ID.
   * Returns {@code null} if the model has no active stream map.
   *
   * @param modelId the model ID
   * @return the active stream contexts map, or null
   */
  ConcurrentHashMap<Integer, StreamContext> streamsForModel(String modelId);
}
