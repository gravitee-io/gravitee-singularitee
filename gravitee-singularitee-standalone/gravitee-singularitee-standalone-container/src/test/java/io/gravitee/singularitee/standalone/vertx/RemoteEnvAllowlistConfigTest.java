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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.api.configuration.Configuration;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteEnvAllowlistConfigTest {

  @Test
  void unset_keeps_the_singularitee_remote_prefix() {
    var allowlist = WorkspaceLoaderComponent.remoteEnvAllowlist(config(Map.of()));

    assertThat(allowlist.allows("SINGULARITEE_REMOTE_PII")).isTrue();
    assertThat(allowlist.allows("GRAVITEE_AI_SYNC_TOKEN")).isFalse();
  }

  @Test
  void a_comma_separated_value_replaces_the_default() {
    var allowlist = WorkspaceLoaderComponent.remoteEnvAllowlist(
      config(Map.of("ai.remote.env-allowlist", "PII_PASSWORD, VAULT_*"))
    );

    assertThat(allowlist.allows("PII_PASSWORD")).isTrue();
    assertThat(allowlist.allows("VAULT_SAFETY")).isTrue();
    assertThat(allowlist.allows("SINGULARITEE_REMOTE_PII")).isFalse();
  }

  @Test
  void a_yaml_list_replaces_the_default() {
    var allowlist = WorkspaceLoaderComponent.remoteEnvAllowlist(
      config(
        Map.of(
          "ai.remote.env-allowlist[0]",
          "PII_PASSWORD",
          "ai.remote.env-allowlist[1]",
          "VAULT_*"
        )
      )
    );

    assertThat(allowlist.allows("PII_PASSWORD")).isTrue();
    assertThat(allowlist.allows("VAULT_SAFETY")).isTrue();
    assertThat(allowlist.allows("SINGULARITEE_REMOTE_PII")).isFalse();
  }

  @Test
  void a_blank_value_admits_no_variable() {
    var allowlist = WorkspaceLoaderComponent.remoteEnvAllowlist(
      config(Map.of("ai.remote.env-allowlist", " "))
    );

    assertThat(allowlist.allows("SINGULARITEE_REMOTE_PII")).isFalse();
  }

  private static Configuration config(Map<String, Object> values) {
    return (Configuration) Proxy.newProxyInstance(
      Configuration.class.getClassLoader(),
      new Class<?>[] { Configuration.class },
      (proxy, method, args) -> {
        if (!method.getName().equals("getProperty")) {
          return null;
        }
        Object value = values.get(args[0]);
        if (args.length == 3) {
          return value != null ? value : args[2];
        }
        return value == null ? null : value.toString();
      }
    );
  }
}
