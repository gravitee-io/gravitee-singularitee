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
package io.gravitee.singularitee.engine.pipeline.executor;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;

/**
 * Renders a step config for the diagnostics log without knowing any step type: a compact
 * one-line form for DEBUG (size-capped, so a large template never floods the log) and the
 * full form for TRACE. Works for any config object a plugin hands the engine; protobuf
 * messages get their text format, everything else its {@code toString()}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class StepConfigDescriber {

  private static final int COMPACT_MAX = 400;

  private StepConfigDescriber() {}

  /** One-line, size-capped description. */
  public static String describe(Object config) {
    if (config == null) return "null";
    String full = describeFull(config);
    String oneLine = full.replaceAll("\\s+", " ").trim();
    if (oneLine.length() <= COMPACT_MAX) return oneLine;
    return (
      oneLine.substring(0, COMPACT_MAX) + "...(+" + (oneLine.length() - COMPACT_MAX) + " chars)"
    );
  }

  /** The complete description. */
  public static String describeFull(Object config) {
    if (config == null) return "null";
    if (config instanceof MessageOrBuilder message) {
      return (
        config.getClass().getSimpleName() +
        "{" +
        TextFormat.printer().shortDebugString(message) +
        "}"
      );
    }
    return String.valueOf(config);
  }
}
