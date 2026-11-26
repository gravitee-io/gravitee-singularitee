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

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a workspace value written {@code ${env:NAME}} from the environment when the workspace
 * loads. Only a value that is wholly a reference is resolved; any other value is kept as written.
 * A variable outside the {@link EnvironmentAllowlist} is refused before it is read. Messages name
 * the variable and the field, never a value.
 */
final class EnvironmentReferences {

  private static final String PREFIX = "${env:";
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{env:([A-Z_][A-Z0-9_]*)}");

  private EnvironmentReferences() {}

  /**
   * @param field names the value in messages, e.g. {@code password}
   * @throws IllegalArgumentException when the reference is malformed, names a variable outside
   *     {@code allowlist}, or its variable is unset or empty
   */
  static String resolve(
    String value,
    String field,
    EnvironmentAllowlist allowlist,
    UnaryOperator<String> environment
  ) {
    if (value == null || !value.startsWith(PREFIX)) {
      return value;
    }
    Matcher reference = REFERENCE.matcher(value);
    if (!reference.matches()) {
      throw new IllegalArgumentException(
        field +
          " is not a valid environment reference: write ${env:NAME} with NAME matching [A-Z_][A-Z0-9_]*"
      );
    }
    String name = reference.group(1);
    if (!allowlist.allows(name)) {
      throw new IllegalArgumentException(
        "environment variable " +
          name +
          " (" +
          field +
          ") is not allowed: remote credentials may read only " +
          allowlist.rule() +
          " (" +
          EnvironmentAllowlist.PROPERTY +
          ")"
      );
    }
    String resolved = environment.apply(name);
    if (resolved == null) {
      throw new IllegalArgumentException(
        "environment variable " + name + " (" + field + ") is not set"
      );
    }
    if (resolved.isEmpty()) {
      throw new IllegalArgumentException(
        "environment variable " + name + " (" + field + ") is empty"
      );
    }
    return resolved;
  }
}
