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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.gravitee.node.api.opentelemetry.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link SpanScribe}: the no-op discards; the span-backed one forwards and coerces. */
class SpanScribeTest {

  /** A {@link Span} that records what was written to it. */
  private static final class RecordingSpan implements Span {

    final Map<String, Object> attributes = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> events = new LinkedHashMap<>();

    @Override
    public boolean isRoot() {
      return false;
    }

    @Override
    public <T> Span withAttribute(String name, T value) {
      attributes.put(name, value);
      return this;
    }

    @Override
    public Span inError() {
      return this;
    }

    @Override
    public Span addEvent(String name, Map<String, Object> fields) {
      events.put(name, fields);
      return this;
    }

    @Override
    public String spanId() {
      return "span";
    }

    @Override
    public String traceId() {
      return "trace";
    }
  }

  @Test
  void the_noop_scribe_discards_everything_and_never_throws() {
    assertThatCode(() ->
      SpanScribe.NOOP.set("k", "v").event("e", Map.of("a", 1))
    ).doesNotThrowAnyException();
  }

  @Test
  void of_null_span_is_the_noop() {
    assertThat(SpanScribe.of(null)).isSameAs(SpanScribe.NOOP);
  }

  @Test
  void the_span_backed_scribe_forwards_attributes_and_coerces_types() {
    var span = new RecordingSpan();
    SpanScribe.of(span)
      .set("s", "text")
      .set("b", true)
      .set("i", 7)
      .set("l", 9L)
      .set("d", 1.5)
      .set("nullValue", null)
      .event("turn", Map.of("role", "engine"));

    assertThat(span.attributes)
      .containsEntry("s", "text")
      .containsEntry("b", true)
      .containsEntry("i", 7L) // int widened to long
      .containsEntry("l", 9L)
      .containsEntry("d", 1.5)
      .doesNotContainKey("nullValue"); // null is ignored
    assertThat(span.events).containsKey("turn");
  }
}
