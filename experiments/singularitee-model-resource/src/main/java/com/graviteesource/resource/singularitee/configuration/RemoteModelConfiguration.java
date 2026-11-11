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
package com.graviteesource.resource.singularitee.configuration;

import io.gravitee.secrets.api.annotation.Secret;
import java.net.URI;

/** A resolved serving binding. Discovery references are retained by the control plane. */
public record RemoteModelConfiguration(
  String adapter,
  String endpoint,
  String modelId,
  @Secret String authorization,
  Long timeoutMs
) {
  public RemoteModelConfiguration {
    if (!"singularitee".equals(adapter)) throw new IllegalArgumentException(
      "Unsupported classification adapter"
    );
    if (
      endpoint == null || endpoint.isBlank()
    ) throw new IllegalArgumentException(
      "A valid HTTP serving endpoint is required"
    );
    URI uri = URI.create(endpoint);
    if (
      !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ||
      uri.getHost() == null ||
      uri.getUserInfo() != null ||
      uri.getQuery() != null ||
      uri.getFragment() != null
    ) {
      throw new IllegalArgumentException(
        "A valid HTTP serving endpoint is required"
      );
    }
    if (
      modelId == null || modelId.isBlank()
    ) throw new IllegalArgumentException("A serving model ID is required");
    timeoutMs = timeoutMs == null ? 5000L : timeoutMs;
    if (timeoutMs < 1 || timeoutMs > 60000) throw new IllegalArgumentException(
      "Timeout must be between 1 and 60000 ms"
    );
  }

  @Override
  public String toString() {
    return (
      "RemoteModelConfiguration[adapter=" +
      adapter +
      ", endpoint=" +
      endpoint +
      ", modelId=" +
      modelId +
      ", authorization=<redacted>, timeoutMs=" +
      timeoutMs +
      "]"
    );
  }
}
