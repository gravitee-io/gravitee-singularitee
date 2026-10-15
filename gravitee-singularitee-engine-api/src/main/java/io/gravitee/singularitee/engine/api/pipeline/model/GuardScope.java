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
 * Which turns of the request a guard inspects when it reads the request itself (no
 * {@code input_field}, or {@code input_field: prompt}). Each turn is classified on its own and the
 * guard fires if any of them matches.
 *
 * @author GraviteeSource Team
 */
public enum GuardScope {
  /** The last user turn only. */
  LAST_USER,
  /** Every user turn. */
  USER_TURNS,
  /** Every user, assistant and tool turn: the whole caller-supplied transcript except system. */
  CONVERSATION;

  /** Parses the YAML spelling; unset is {@link #CONVERSATION}, an unknown value is refused. */
  public static GuardScope parse(String value) {
    if (value == null || value.isBlank()) return CONVERSATION;
    return switch (value.trim().toLowerCase(Locale.ENGLISH)) {
      case "last_user" -> LAST_USER;
      case "user_turns" -> USER_TURNS;
      case "conversation" -> CONVERSATION;
      default -> throw new IllegalArgumentException(
        "unknown scope '" + value + "' (expected last_user, user_turns or conversation)"
      );
    };
  }
}
