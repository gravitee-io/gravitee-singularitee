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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.opentelemetry.configuration.OpenTelemetryConfiguration;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class SyncedSpanResourceTest {

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  @Test
  void a_synced_node_labels_spans_with_the_group_and_environment_of_its_token() {
    var attributes = SyncedSpanResource.attributes(
      config(true, token("{\"gid\":\"pii-prod\",\"env\":\"DEFAULT\",\"ver\":1}")),
      Set.of()
    );

    assertThat(attributes).containsOnly(
      Map.entry("gravitee.api.id", "pii-prod"),
      Map.entry("gravitee.env.id", "DEFAULT")
    );
  }

  @Test
  void a_token_issued_before_tokens_named_the_environment_labels_the_deployment_only() {
    var attributes = SyncedSpanResource.attributes(
      config(true, token("{\"gid\":\"pii-prod\",\"ver\":1}")),
      Set.of()
    );

    assertThat(attributes).containsOnly(Map.entry("gravitee.api.id", "pii-prod"));
  }

  @Test
  void an_attribute_the_operator_configured_explicitly_wins() {
    var token = token("{\"gid\":\"pii-prod\",\"env\":\"DEFAULT\"}");

    assertThat(
      SyncedSpanResource.attributes(config(true, token), Set.of("gravitee.api.id"))
    ).containsOnly(Map.entry("gravitee.env.id", "DEFAULT"));
    assertThat(
      SyncedSpanResource.attributes(
        config(true, token),
        Set.of("gravitee.api.id", "gravitee.env.id")
      )
    ).isEmpty();
  }

  @Test
  void a_node_that_does_not_sync_adds_nothing() {
    var attributes = SyncedSpanResource.attributes(
      config(false, token("{\"gid\":\"pii-prod\",\"env\":\"DEFAULT\"}")),
      Set.of()
    );

    assertThat(attributes).isEmpty();
  }

  @Test
  void a_token_that_is_not_a_readable_jwt_adds_nothing() {
    assertThat(SyncedSpanResource.attributes(config(true, null), Set.of())).isEmpty();
    assertThat(SyncedSpanResource.attributes(config(true, "  "), Set.of())).isEmpty();
    assertThat(SyncedSpanResource.attributes(config(true, "not-a-jwt"), Set.of())).isEmpty();
    assertThat(SyncedSpanResource.attributes(config(true, "a.%%%.c"), Set.of())).isEmpty();
    assertThat(SyncedSpanResource.attributes(config(true, token("[1,2]")), Set.of())).isEmpty();
    assertThat(
      SyncedSpanResource.attributes(config(true, token("{\"gid\":\" \",\"env\":7}")), Set.of())
    ).isEmpty();
  }

  @Test
  void the_extra_attributes_of_the_opentelemetry_configuration_are_the_explicit_ones() {
    var environment = new StandardEnvironment();
    environment
      .getPropertySources()
      .addFirst(
        new MapPropertySource(
          "test",
          Map.of(
            "services.opentelemetry.extraAttributes[0].gravitee.env.id",
            "STAGING",
            "services.opentelemetry.extraAttributes[1].team",
            "ml"
          )
        )
      );

    var attributes = SyncedSpanResource.attributes(
      config(true, token("{\"gid\":\"pii-prod\",\"env\":\"DEFAULT\"}")),
      new OpenTelemetryConfiguration(environment)
    );

    assertThat(attributes).containsOnly(Map.entry("gravitee.api.id", "pii-prod"));
  }

  @Test
  void without_extra_attributes_both_are_added() {
    var attributes = SyncedSpanResource.attributes(
      config(true, token("{\"gid\":\"pii-prod\",\"env\":\"DEFAULT\"}")),
      new OpenTelemetryConfiguration(new StandardEnvironment())
    );

    assertThat(attributes).hasSize(2);
  }

  private static String token(String payload) {
    return (
      B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) +
      "." +
      B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) +
      ".signature"
    );
  }

  private static Configuration config(boolean sync, String token) {
    Map<String, Object> values = new java.util.HashMap<>();
    values.put("ai.sync.enabled", sync);
    values.put("ai.sync.token", token);
    return (Configuration) Proxy.newProxyInstance(
      Configuration.class.getClassLoader(),
      new Class<?>[] { Configuration.class },
      (proxy, method, args) -> {
        if (!method.getName().equals("getProperty")) {
          return null;
        }
        Object value = values.get(args[0]);
        return args.length == 3 && value == null ? args[2] : value;
      }
    );
  }
}
