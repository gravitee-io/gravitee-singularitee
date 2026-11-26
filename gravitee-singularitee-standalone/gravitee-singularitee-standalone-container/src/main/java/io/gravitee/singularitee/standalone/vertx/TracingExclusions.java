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
package io.gravitee.singularitee.standalone.vertx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Request paths the HTTP API does not trace: probes and management endpoints that would otherwise
 * open a root trace on every poll. An entry is an exact path, or a prefix when it ends in
 * {@code *} ({@code /_node/*}).
 *
 * <p>Read from {@code services.opentelemetry.exclude-paths}, a comma-separated string or a YAML
 * list. A configured value replaces {@link #DEFAULTS}; a blank one traces every path.
 */
final class TracingExclusions {

  static final String PROPERTY = "services.opentelemetry.exclude-paths";

  static final List<String> DEFAULTS = List.of(
    "/health",
    "/healthz",
    "/ready",
    "/readyz",
    "/readiness",
    "/live",
    "/livez",
    "/liveness",
    "/metrics",
    "/_node/*"
  );

  private final List<String> exact;
  private final List<String> prefixes;

  TracingExclusions(List<String> patterns) {
    List<String> exactPaths = new ArrayList<>();
    List<String> prefixPaths = new ArrayList<>();
    for (String pattern : patterns) {
      String trimmed = pattern.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.endsWith("*")) {
        prefixPaths.add(trimmed.substring(0, trimmed.length() - 1));
      } else {
        exactPaths.add(trimmed);
      }
    }
    this.exact = List.copyOf(exactPaths);
    this.prefixes = List.copyOf(prefixPaths);
  }

  static TracingExclusions from(Environment environment) {
    String csv = environment.getProperty(PROPERTY);
    if (csv != null) {
      return new TracingExclusions(Arrays.asList(csv.split(",")));
    }
    List<String> listed = new ArrayList<>();
    for (int i = 0; ; i++) {
      String entry = environment.getProperty(PROPERTY + "[" + i + "]");
      if (entry == null) {
        break;
      }
      listed.add(entry);
    }
    return new TracingExclusions(listed.isEmpty() ? DEFAULTS : listed);
  }

  static TracingExclusions defaults() {
    return new TracingExclusions(DEFAULTS);
  }

  boolean excludes(String path) {
    if (path == null) {
      return false;
    }
    if (exact.contains(path)) {
      return true;
    }
    for (String prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
