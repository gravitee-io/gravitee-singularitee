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

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marks a cached download as complete, for the cache entries that are a <em>set</em> of files.
 *
 * <p>{@link HuggingFaceModelDownloader} writes each file to a temp sibling, publishes it atomically
 * and re-fetches it when its size does not match the repository listing, so a single file is either
 * absent or whole. A directory is not: an interrupted download leaves one that holds some of the
 * files and looks exactly like a finished one. Guessing from its contents cannot tell the two apart
 * (a sharded checkpoint missing shard 2, a bundle missing its scorer), and a wrong guess is
 * permanent, since the resolver then skips the download that would repair it.
 *
 * <p>So a directory counts as cached only once the marker this writes is there. Anything else sends
 * the resolver back to the repository listing, which re-fetches just what is missing. Caches
 * created before the marker existed are listed once and marked on the next start.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class CacheMarker {

  private static final Logger LOGGER = LoggerFactory.getLogger(CacheMarker.class);

  private static final String PREFIX = ".complete";

  private CacheMarker() {}

  /**
   * Whether {@code dir} holds a download completed for {@code key}.
   *
   * @param key what was downloaded into the directory, when one directory holds several entries
   *            (a GLiNER variant, an ONNX tokenizer); null or blank for the directory as a whole
   */
  static boolean isComplete(Path dir, String key) {
    return Files.isRegularFile(marker(dir, key));
  }

  /** Records that every file selected for {@code key} is on disk, listing them for diagnostics. */
  static void mark(Path dir, String key, List<Path> files) {
    Path marker = marker(dir, key);
    try {
      Files.writeString(marker, files.stream().map(Path::toString).sorted().collect(joining("\n")));
    } catch (IOException | RuntimeException e) {
      LOGGER.warn(
        "Cannot write the cache marker {}: the next start lists the repository again",
        marker,
        e
      );
    }
  }

  /** The marker path, whose name is sanitised since the key comes from the workspace. */
  private static Path marker(Path dir, String key) {
    String suffix = key == null || key.isBlank()
      ? ""
      : "-" + key.replaceAll("[^A-Za-z0-9_.-]", "_");
    return dir.resolve(PREFIX + suffix);
  }
}
