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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * Queued sequence waiting for an available slot in the batch engine.
 *
 * @param <REQUEST> The generation request type
 * @param seqId The external sequence ID
 * @param request The generation request
 * @param cacheKey Client cache-affinity key for the KV prefix cache, or {@code null}
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record QueuedSequence<REQUEST extends GenerationRequest>(
  int seqId,
  REQUEST request,
  String cacheKey
) {
  /** Compatibility constructor — no cache key. */
  public QueuedSequence(int seqId, REQUEST request) {
    this(seqId, request, null);
  }
}
