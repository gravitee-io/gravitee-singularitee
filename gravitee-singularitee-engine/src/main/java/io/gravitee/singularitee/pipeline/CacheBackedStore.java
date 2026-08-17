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
package io.gravitee.singularitee.pipeline;

import io.gravitee.node.api.cache.Cache;
import io.gravitee.node.api.cache.CacheConfiguration;
import io.gravitee.node.api.cache.CacheManager;

/**
 * Common shape of the string-keyed, idle-expiring engine stores backed by the
 * pluggable gravitee-node {@link CacheManager}: a {@code null} manager or a
 * non-positive TTL disables the store, and blank keys are always no-ops.
 *
 * @param <V> the serializable cache value type
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
abstract class CacheBackedStore<V> {

  protected final Cache<String, V> cache;

  /**
   * @param cacheManager   the node cache manager; {@code null} disables the store
   * @param cacheName      the backing cache name (a serialization contract for
   *                       distributed backends)
   * @param idleTtlSeconds entry idle timeout; {@code <= 0} disables the store
   * @param maxEntries     upper bound on concurrently stored entries
   */
  protected CacheBackedStore(
    CacheManager cacheManager,
    String cacheName,
    long idleTtlSeconds,
    long maxEntries
  ) {
    this.cache = (cacheManager == null || idleTtlSeconds <= 0)
      ? null
      : cacheManager.getOrCreateCache(
        cacheName,
        CacheConfiguration.builder()
          .timeToIdleInMs(idleTtlSeconds * 1000)
          .maxSize(maxEntries)
          .build()
      );
  }

  /** Whether the store is active (a cache is bound and the TTL is positive). */
  public final boolean isEnabled() {
    return cache != null;
  }

  /** Whether reads/writes for this key must be skipped. */
  protected final boolean unusable(String key) {
    return cache == null || key == null || key.isBlank();
  }

  /** Returns the cached value, or {@code null} when unusable/absent. */
  protected final V lookup(String key) {
    return unusable(key) ? null : cache.get(key);
  }
}
