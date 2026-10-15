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
package io.gravitee.singularitee.engine.api.pipeline.model;

import java.util.Locale;

/**
 * What a guard does when there is no text to inspect: a blank field, or no turn in scope with
 * text content (an image-only message, for instance).
 *
 * @author GraviteeSource Team
 */
public enum GuardOnEmpty {
  /** Halt the pipeline with FINISH_REASON_GUARD_BLOCKED, as a fired reject guard would. */
  REJECT,
  /** Pass to the next step without inspecting anything. */
  SKIP;

  /**
   * Parses the YAML spelling; unset is {@link #SKIP} for a {@code warn} guard, which never blocks,
   * and {@link #REJECT} otherwise. An unknown value is refused.
   */
  public static GuardOnEmpty parse(String value, GuardAction action) {
    if (value == null || value.isBlank()) return action == GuardAction.WARN ? SKIP : REJECT;
    return switch (value.trim().toLowerCase(Locale.ENGLISH)) {
      case "reject" -> REJECT;
      case "skip" -> SKIP;
      default -> throw new IllegalArgumentException(
        "unknown on_empty '" + value + "' (expected reject or skip)"
      );
    };
  }
}
