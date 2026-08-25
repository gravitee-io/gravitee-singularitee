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
 * What a guard does when it fires.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum GuardAction {
  /** Halt the pipeline with FINISH_REASON_GUARD_BLOCKED. */
  REJECT,
  /** Mask matched spans and continue. */
  REDACT,
  /** Log the trigger and continue. */
  WARN;

  /** Parses the YAML spelling; unset or unknown is {@link #REJECT}. */
  public static GuardAction parse(String value) {
    if (value == null || value.isBlank()) return REJECT;
    return switch (value.trim().toLowerCase(Locale.ENGLISH)) {
      case "redact" -> REDACT;
      case "warn" -> WARN;
      default -> REJECT;
    };
  }
}
