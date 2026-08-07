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
package io.gravitee.singularitee.grpc.resolver;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Compiles the {@code download.exclude:} globs of a workspace model definition
 * into a predicate over repository-relative file paths.
 *
 * <p>Glob rather than regex because these are written by hand in YAML, next to
 * file names: {@code *} matches within a path segment, {@code **} across
 * segments, {@code ?} a single character. Everything else — {@code .} very much
 * included — is literal.
 *
 * <p>A pattern containing no {@code /} is also matched against the file name
 * alone, so {@code "*.pth"} excludes {@code original/consolidated.00.pth} without
 * the author having to think about where in the repo it sits. Patterns that do
 * contain a {@code /} are anchored at the repository root, so {@code "original/*"}
 * excludes only that directory's contents.
 *
 * <p>Matching is case-insensitive, matching how the resolvers compare the file
 * suffixes they filter on.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class ExcludePatterns {

  private ExcludePatterns() {}

  /**
   * Builds the exclusion predicate for {@code globs}.
   *
   * @param globs patterns from {@code download.exclude:}; {@code null} or empty
   *              yields a predicate that excludes nothing
   * @return a predicate that is {@code true} for repository-relative paths that
   *         should NOT be downloaded
   */
  static Predicate<String> excluder(List<String> globs) {
    if (globs == null || globs.isEmpty()) {
      return file -> false;
    }

    List<Pattern> compiled = globs
      .stream()
      .filter(g -> g != null && !g.isBlank())
      .map(ExcludePatterns::compile)
      .toList();

    if (compiled.isEmpty()) {
      return file -> false;
    }

    return file -> {
      if (file == null || file.isBlank()) {
        return false;
      }
      String path = normalise(file);
      String name = path.substring(path.lastIndexOf('/') + 1);
      return compiled
        .stream()
        .anyMatch(p -> p.matcher(path).matches() || p.matcher(name).matches());
    };
  }

  /**
   * Compiles one glob. Bare patterns are matched against both the full path and
   * the file name by {@link #excluder(List)}, so nothing extra is needed here.
   */
  private static Pattern compile(String glob) {
    return Pattern.compile(globToRegex(normalise(glob.trim())), Pattern.CASE_INSENSITIVE);
  }

  /** HuggingFace paths always use {@code /}; accept Windows-style input anyway. */
  private static String normalise(String path) {
    String p = path.replace('\\', '/');
    return p.startsWith("./") ? p.substring(2) : p;
  }

  private static String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder(glob.length() * 2);

    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);

      if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
        // "**/" spans zero or more directories, so "**/*.bin" also matches a
        // root-level "model.bin"; a trailing "**" is simply "anything".
        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
          regex.append("(?:.*/)?");
          i += 2;
        } else {
          regex.append(".*");
          i++;
        }
      } else if (c == '*') {
        regex.append("[^/]*");
      } else if (c == '?') {
        regex.append("[^/]");
      } else {
        if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
          regex.append('\\');
        }
        regex.append(c);
      }
    }

    return regex.toString();
  }
}
