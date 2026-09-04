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

import io.gravitee.node.api.opentelemetry.Span;
import java.util.Map;

/**
 * A neutral, null-safe facade for writing attributes and events onto a tracing span.
 *
 * <p>Executors annotate spans through this seam instead of touching {@link Span} directly, so
 * a missing span (client-side executor, CLI, unit tests) is a no-op rather than a null check at
 * every call site. It carries no domain vocabulary: callers pass whatever string keys they own.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface SpanScribe {
  /** A scribe that discards everything; used when there is no span to write to. */
  SpanScribe NOOP = new NoopSpanScribe();

  /** Sets a single attribute; a {@code null} value is ignored. Returns {@code this} to chain. */
  SpanScribe set(String key, Object value);

  /** Adds a timestamped event with the given fields. Returns {@code this} to chain. */
  SpanScribe event(String name, Map<String, Object> fields);

  /** Wraps a span, or returns {@link #NOOP} when {@code span} is {@code null}. */
  static SpanScribe of(Span span) {
    return span == null ? NOOP : new SpanBackedScribe(span);
  }
}
