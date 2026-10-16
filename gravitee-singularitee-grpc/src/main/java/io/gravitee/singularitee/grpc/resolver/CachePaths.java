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

import java.nio.file.Path;

/**
 * Joins a workspace-supplied model name or file path onto a cache directory.
 *
 * <p>An absolute path is an explicit operator choice (weights mounted outside the cache) and
 * is returned as-is. A relative one is a repository id or a file inside the repository, so it
 * must stay under the base: {@code ../} segments would otherwise create directories and
 * write downloads outside the model cache.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class CachePaths {

  private CachePaths() {}

  /**
   * @throws IllegalArgumentException if a relative {@code name} resolves outside {@code base}
   */
  static Path under(Path base, String name) {
    Path p = Path.of(name);
    return p.isAbsolute() ? p : HuggingFaceModelDownloader.resolveWithinTarget(base, name);
  }
}
