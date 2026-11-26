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
package io.gravitee.singularitee.standalone.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.opentelemetry.configuration.OpenTelemetryConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The span resource attributes a synced node sets for itself, so Gamma's Tracing screen can scope
 * its traces without per-node configuration.
 *
 * <p>With {@code ai.sync.enabled} the sync token names the server group ({@code gid}, the
 * deployment id the control plane also renders as the workspace name) and its environment
 * ({@code env}, absent on tokens issued before tokens named it). They become {@value #API_ID} and
 * {@value #ENV_ID}. The token is read at startup, when the tracer is built, and its payload is
 * decoded without verifying the signature: it is the node's own token, used only for labelling.
 * An attribute the operator set in {@code services.opentelemetry.extraAttributes} is left alone.
 */
public final class SyncedSpanResource {

  public static final String API_ID = "gravitee.api.id";
  public static final String ENV_ID = "gravitee.env.id";

  private static final ObjectMapper JSON = new ObjectMapper();

  private SyncedSpanResource() {}

  /** The attributes to add, leaving alone the keys {@code services.opentelemetry.extraAttributes} sets. */
  public static Map<String, String> attributes(
    Configuration configuration,
    OpenTelemetryConfiguration openTelemetry
  ) {
    return attributes(
      configuration,
      openTelemetry
        .getExtraAttributes()
        .keySet()
        .stream()
        .map(AttributeKey::getKey)
        .collect(Collectors.toSet())
    );
  }

  /**
   * @param explicitKeys resource attribute keys the operator configured explicitly
   * @return the attributes to add, empty when the node does not sync or the token is unreadable
   */
  public static Map<String, String> attributes(
    Configuration configuration,
    Set<String> explicitKeys
  ) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (!configuration.getProperty("ai.sync.enabled", Boolean.class, false)) {
      return attributes;
    }
    JsonNode claims = claims(configuration.getProperty("ai.sync.token"));
    put(attributes, explicitKeys, API_ID, claims.path("gid"));
    put(attributes, explicitKeys, ENV_ID, claims.path("env"));
    return attributes;
  }

  private static void put(
    Map<String, String> attributes,
    Set<String> explicitKeys,
    String key,
    JsonNode claim
  ) {
    if (!explicitKeys.contains(key) && claim.isTextual() && !claim.asText().isBlank()) {
      attributes.put(key, claim.asText());
    }
  }

  private static JsonNode claims(String token) {
    if (token == null) {
      return JSON.missingNode();
    }
    String[] parts = token.trim().split("\\.", -1);
    if (parts.length != 3) {
      return JSON.missingNode();
    }
    try {
      JsonNode payload = JSON.readTree(
        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
      );
      return payload.isObject() ? payload : JSON.missingNode();
    } catch (IllegalArgumentException | java.io.IOException e) {
      return JSON.missingNode();
    }
  }
}
