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
package io.gravitee.singularitee.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which end-to-end backends run. A backend runs when {@code SINGULARITEE_E2E} names it
 * (a comma-separated list of keys, or {@code all}), or when every model it needs is already in
 * the models directory. Naming a backend lets the server download what is missing.
 *
 * <p>The models directory is {@code GRAVITEE_AI_MODELS_PATH}, else
 * {@code ~/.cache/gravitee-singularitee/models}, the one {@code run-server.sh} uses.
 */
public final class E2e {

  public static final String SELECTION = "SINGULARITEE_E2E";
  public static final String MODELS_PATH = "GRAVITEE_AI_MODELS_PATH";
  public static final String BOOT_TIMEOUT = "SINGULARITEE_E2E_BOOT_TIMEOUT";

  private E2e() {}

  /** Why the backend is skipped, or {@code null} when it runs. */
  public static String skipReason(String key, List<String> repos) {
    Set<String> selected = selection();
    if (selected.contains("all") || selected.contains(key)) {
      return null;
    }
    if (repos.isEmpty()) {
      return "backend '" + key + "' needs no model: add it to " + SELECTION + " to run it";
    }
    List<String> missing = repos
      .stream()
      .filter(repo -> !holdsFiles(modelsDir().resolve(repo)))
      .toList();
    if (missing.isEmpty()) {
      return null;
    }
    return (
      "backend '" +
      key +
      "' is skipped: " +
      missing +
      " not in " +
      modelsDir() +
      ". Add '" +
      key +
      "' to " +
      SELECTION +
      " to download and run it"
    );
  }

  /** A failed download leaves an empty directory behind: that is not a model. */
  private static boolean holdsFiles(Path dir) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (var files = Files.walk(dir)) {
      return files.anyMatch(Files::isRegularFile);
    } catch (IOException e) {
      return false;
    }
  }

  public static Path modelsDir() {
    String configured = System.getenv(MODELS_PATH);
    return configured != null && !configured.isBlank()
      ? Path.of(configured)
      : Path.of(System.getProperty("user.home"), ".cache", "gravitee-singularitee", "models");
  }

  /** Seconds a server may take to publish its models, downloads included. */
  public static long bootTimeoutSeconds() {
    String configured = System.getenv(BOOT_TIMEOUT);
    return configured != null && !configured.isBlank() ? Long.parseLong(configured.strip()) : 900;
  }

  private static Set<String> selection() {
    String configured = System.getenv(SELECTION);
    if (configured == null || configured.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(configured.split(","))
      .map(s -> s.strip().toLowerCase())
      .filter(s -> !s.isEmpty())
      .collect(Collectors.toSet());
  }
}
