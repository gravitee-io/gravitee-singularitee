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
 * A {@link SpanScribe} that writes through to a real {@link Span} via {@code withAttribute} /
 * {@code addEvent}. Attribute values are coerced to the primitive types tracing backends accept
 * (string, long, double, boolean); anything else is stringified.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class SpanBackedScribe implements SpanScribe {

  private final Span span;

  SpanBackedScribe(Span span) {
    this.span = span;
  }

  @Override
  public SpanScribe set(String key, Object value) {
    if (value == null) {
      return this;
    }
    switch (value) {
      case String s -> span.withAttribute(key, s);
      case Boolean b -> span.withAttribute(key, b);
      case Float f -> span.withAttribute(key, f.doubleValue());
      case Double d -> span.withAttribute(key, d);
      case Number n -> span.withAttribute(key, n.longValue());
      default -> span.withAttribute(key, String.valueOf(value));
    }
    return this;
  }

  @Override
  public SpanScribe event(String name, Map<String, Object> fields) {
    span.addEvent(name, fields == null ? Map.of() : fields);
    return this;
  }
}
