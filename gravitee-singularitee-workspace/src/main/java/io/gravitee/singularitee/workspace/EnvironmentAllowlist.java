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

import java.util.ArrayList;
import java.util.List;

/**
 * The environment variables a workspace may read through {@code ${env:NAME}}. A workspace can be
 * written by someone who does not run the node, so it must not reach every variable the node
 * holds. An entry is an exact name, or a prefix when it ends in {@code *}.
 */
public final class EnvironmentAllowlist {

  /** Node setting that replaces {@link #DEFAULTS}. */
  public static final String PROPERTY = "ai.remote.env-allowlist";

  public static final List<String> DEFAULTS = List.of("SINGULARITEE_REMOTE_*");

  private static final EnvironmentAllowlist DEFAULT = of(DEFAULTS);

  private final List<String> entries;
  private final List<String> exact;
  private final List<String> prefixes;

  private EnvironmentAllowlist(List<String> entries) {
    List<String> kept = new ArrayList<>();
    List<String> exactNames = new ArrayList<>();
    List<String> prefixNames = new ArrayList<>();
    for (String entry : entries) {
      String trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      kept.add(trimmed);
      if (trimmed.endsWith("*")) {
        prefixNames.add(trimmed.substring(0, trimmed.length() - 1));
      } else {
        exactNames.add(trimmed);
      }
    }
    this.entries = List.copyOf(kept);
    this.exact = List.copyOf(exactNames);
    this.prefixes = List.copyOf(prefixNames);
  }

  public static EnvironmentAllowlist of(List<String> entries) {
    return new EnvironmentAllowlist(entries);
  }

  public static EnvironmentAllowlist defaults() {
    return DEFAULT;
  }

  public boolean allows(String name) {
    if (exact.contains(name)) {
      return true;
    }
    for (String prefix : prefixes) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** The entries as configured, for messages. */
  public String rule() {
    return entries.isEmpty() ? "no variable" : String.join(", ", entries);
  }
}
