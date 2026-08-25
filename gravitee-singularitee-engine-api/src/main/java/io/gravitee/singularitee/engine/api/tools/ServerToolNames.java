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
package io.gravitee.singularitee.engine.api.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The display names of the server-owned tools: what the model and the wire see for each
 * canonical name in {@link TodoTools#NAMES}. Identity unless a deployment configured
 * overrides. Immutable, built once at boot and carried on the pipeline context, so a
 * rename is scoped to the executor that applied it rather than to the process.
 *
 * <p>Renaming is presentation only: internal identity, stored state and the proto
 * contract never change with it.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ServerToolNames {

  /** Canonical names throughout. */
  public static final ServerToolNames DEFAULTS = new ServerToolNames(Map.of());

  private final Map<String, String> displayByCanonical;
  private final Map<String, String> canonicalByDisplay;
  private final Set<String> serverToolNames;
  private final Set<String> delegableToolNames;

  private ServerToolNames(Map<String, String> overrides) {
    Map<String, String> display = new HashMap<>();
    for (String canonical : TodoTools.NAMES) {
      display.put(canonical, overrides.getOrDefault(canonical, canonical));
    }
    this.displayByCanonical = Map.copyOf(display);
    Map<String, String> canonical = new HashMap<>();
    displayByCanonical.forEach((c, d) -> canonical.put(d, c));
    this.canonicalByDisplay = Map.copyOf(canonical);
    this.serverToolNames = displayNamesOf(TodoTools.NAMES);
    this.delegableToolNames = displayNamesOf(TodoTools.DELEGABLE);
  }

  /**
   * Builds the names with deployment overrides, keyed by canonical name. Unknown keys and
   * blank values are rejected, and two tools may not share a display name.
   */
  public static ServerToolNames of(Map<String, String> overrides) {
    if (overrides == null || overrides.isEmpty()) return DEFAULTS;
    Map<String, String> trimmed = new HashMap<>();
    for (var e : overrides.entrySet()) {
      if (!TodoTools.NAMES.contains(e.getKey())) {
        throw new IllegalArgumentException("Unknown server tool: " + e.getKey());
      }
      if (e.getValue() == null || e.getValue().isBlank()) {
        throw new IllegalArgumentException("Blank display name for server tool " + e.getKey());
      }
      trimmed.put(e.getKey(), e.getValue().trim());
    }
    var names = new ServerToolNames(trimmed);
    if (names.canonicalByDisplay.size() != names.displayByCanonical.size()) {
      throw new IllegalArgumentException("Server tool display names must be distinct");
    }
    return names;
  }

  /** The wire/display name of a canonical server tool. */
  public String display(String canonical) {
    return displayByCanonical.getOrDefault(canonical, canonical);
  }

  /** The canonical name behind a wire/display name; identity when it is not a server tool. */
  public String canonical(String name) {
    return canonicalByDisplay.getOrDefault(name, name);
  }

  /** Display names of the tools the server owns; used to partition extracted calls. */
  public Set<String> serverToolNames() {
    return serverToolNames;
  }

  /** Display names of the delegable server tools. */
  public Set<String> delegableToolNames() {
    return delegableToolNames;
  }

  /** The canonical to display mapping, for logging. */
  public Map<String, String> displayByCanonical() {
    return displayByCanonical;
  }

  private Set<String> displayNamesOf(Set<String> canonicals) {
    return canonicals.stream().map(this::display).collect(Collectors.toUnmodifiableSet());
  }
}
