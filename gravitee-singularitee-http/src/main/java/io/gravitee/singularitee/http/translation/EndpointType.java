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
package io.gravitee.singularitee.http.translation;

/**
 * Logical request family. Drives how an inbound JSON payload maps onto engine input
 * (e.g. chat messages vs. a flat prompt vs. the Responses {@code input} shape) and which
 * response formatter is used.
 */
public enum EndpointType {
  CHAT("/chat/completions"),
  COMPLETION("/completions"),
  RESPONSES("/responses"),
  EMBEDDINGS("/embeddings"),
  CLASSIFY("/classify"),
  SIMILARITY("/similarity"),
  RERANK("/rerank"),
  UNKNOWN(null);

  /** Cached values array to avoid allocation on every call. */
  private static final EndpointType[] VALUES = values();

  private final String pathSuffix;

  EndpointType(String pathSuffix) {
    this.pathSuffix = pathSuffix;
  }

  /**
   * Resolves the {@link EndpointType} from a request path suffix. Order matters:
   * {@code /chat/completions} is tested before {@code /completions} to avoid a false match.
   */
  public static EndpointType fromPath(String path) {
    if (path == null) {
      return UNKNOWN;
    }
    for (EndpointType type : VALUES) {
      if (type.pathSuffix != null && path.endsWith(type.pathSuffix)) {
        return type;
      }
    }
    return UNKNOWN;
  }
}
