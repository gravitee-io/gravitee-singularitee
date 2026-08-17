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
import java.io.Serializable;
import java.util.List;

/**
 * Cross-request persistence for the engine-managed todo plan, keyed by the
 * request's {@code cache_key} (OpenAI {@code prompt_cache_key} / {@code user}).
 * Lets a plan survive an {@code ask_user} pause: the next turn with the same
 * key restores the plan and continues at the in_progress item.
 *
 * <p>A thin domain wrapper over the gravitee-node {@link CacheManager} — the
 * backing implementation is whatever the container wires (standalone
 * in-memory by default; Hazelcast/Redis by swapping one bean), so nothing
 * here assumes process-locality. Values are small serializable DTOs, not
 * proto messages. Time-to-idle acts as the session timeout.
 */
public final class TodoSessionStore {

  /** Serializable snapshot of one plan item (proto/domain types stay out of the cache). */
  public record SessionTodo(String id, String title, String status, String proof) implements
    Serializable {}

  /**
   * The cache value: the plan items plus the plan-level constraints paragraph
   * (locked user decisions). Changing this record's shape is a serialization
   * concern for distributed backends — bump the cache name if it ever becomes
   * incompatible.
   */
  public record SessionPlan(List<SessionTodo> todos, String constraints) implements Serializable {}

  /** A restored plan: domain todo items plus the constraints paragraph. */
  public record RestoredPlan(List<PipelineContext.TodoItem> todos, String constraints) {}

  private final Cache<String, SessionPlan> cache;
  private final CacheManager cacheManager;

  /**
   * @param cacheManager   the node cache manager; {@code null} disables persistence
   * @param idleTtlSeconds session idle timeout; {@code <= 0} disables persistence
   * @param maxEntries     upper bound on concurrently tracked sessions
   */
  public TodoSessionStore(CacheManager cacheManager, long idleTtlSeconds, long maxEntries) {
    this.cacheManager = cacheManager;
    this.cache = (cacheManager == null || idleTtlSeconds <= 0)
      ? null
      : cacheManager.getOrCreateCache(
        "ai-todo-sessions",
        CacheConfiguration.builder()
          .timeToIdleInMs(idleTtlSeconds * 1000)
          .maxSize(maxEntries)
          .build()
      );
  }

  /** Whether persistence is active (a cache is bound and the TTL is positive). */
  public boolean isEnabled() {
    return cache != null;
  }

  /** The underlying manager (may be {@code null}) — lets other engine caches share the backend. */
  public CacheManager cacheManager() {
    return cacheManager;
  }

  /** Returns the stored plan for the session key, or {@code null}. */
  public RestoredPlan restore(String key) {
    if (cache == null || key == null || key.isBlank()) return null;
    SessionPlan stored = cache.get(key);
    if (stored == null || stored.todos() == null || stored.todos().isEmpty()) return null;
    return new RestoredPlan(
      stored
        .todos()
        .stream()
        .map(t -> new PipelineContext.TodoItem(t.id(), t.title(), t.status(), t.proof()))
        .toList(),
      stored.constraints()
    );
  }

  /** Saves the plan for the session key (no-op when disabled or the plan is empty). */
  public void save(String key, List<PipelineContext.TodoItem> todos, String constraints) {
    if (cache == null || key == null || key.isBlank() || todos == null || todos.isEmpty()) {
      return;
    }
    cache.put(
      key,
      new SessionPlan(
        todos
          .stream()
          .map(t -> new SessionTodo(t.id(), t.title(), t.status(), t.proof()))
          .toList(),
        constraints
      )
    );
  }

  /** Drops the session (a completed plan must not leak into an unrelated conversation). */
  public void clear(String key) {
    if (cache == null || key == null || key.isBlank()) return;
    cache.evict(key);
  }
}
