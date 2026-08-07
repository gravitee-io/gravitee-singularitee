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
package io.gravitee.singularitee.workspace;

import io.gravitee.singularitee.protocol.StepRole;
import java.util.Locale;

/**
 * Maps YAML step role strings to their corresponding proto {@link StepRole} enums.
 * Centralizes step role parsing to eliminate string-based switches.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum StepRoleKey {
  THINKING("thinking", StepRole.STEP_ROLE_THINKING),
  OUTPUT("output", StepRole.STEP_ROLE_OUTPUT),
  INTERNAL("internal", StepRole.STEP_ROLE_INTERNAL);

  private final String yamlKey;
  private final StepRole protoRole;

  StepRoleKey(String yamlKey, StepRole protoRole) {
    this.yamlKey = yamlKey;
    this.protoRole = protoRole;
  }

  /**
   * Parses a YAML step role string to its corresponding enum value.
   * Defaults to {@code OUTPUT} if the input is null or blank.
   *
   * @param roleStr the role string from YAML (case-insensitive)
   * @return the matching proto role; {@code STEP_ROLE_OUTPUT} as default
   */
  public static StepRole parse(String roleStr) {
    if (roleStr == null || roleStr.isBlank()) {
      return StepRole.STEP_ROLE_OUTPUT;
    }
    String normalized = roleStr.toLowerCase(Locale.ENGLISH);
    for (StepRoleKey role : StepRoleKey.values()) {
      if (role.yamlKey.equals(normalized)) {
        return role.protoRole;
      }
    }
    return StepRole.STEP_ROLE_OUTPUT;
  }
}
