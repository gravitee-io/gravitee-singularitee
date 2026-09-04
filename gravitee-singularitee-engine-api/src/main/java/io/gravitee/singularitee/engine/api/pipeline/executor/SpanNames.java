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

/**
 * The single source of the telemetry name prefix applied to the tracing spans and span
 * attributes this engine emits (span names, per-turn attributes, diary fields). It defaults to
 * {@code "singularitee"} and is set once at startup from {@code services.opentelemetry.name-prefix}.
 *
 * <p>Micrometer metric names are a separate, unaffected namespace.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class SpanNames {

  /** The prefix used when none is configured. */
  public static final String DEFAULT_PREFIX = "singularitee";

  private static volatile String prefix = DEFAULT_PREFIX;

  private SpanNames() {}

  /** Sets the prefix; a blank or null value restores {@link #DEFAULT_PREFIX}. */
  public static void configure(String value) {
    prefix = (value == null || value.isBlank()) ? DEFAULT_PREFIX : value.trim();
  }

  /** The configured prefix. */
  public static String prefix() {
    return prefix;
  }

  /** {@code <prefix>.<suffix>}, the name to give a span, attribute or event. */
  public static String key(String suffix) {
    return prefix + "." + suffix;
  }
}
