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

import io.gravitee.singularitee.workspace.WorkspaceDefinition.RemoteEndpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Basic credentials never leave the host over plaintext. */
class RemoteEndpointCredentialsTest {

  @Test
  void plaintext_credentials_to_a_remote_host_are_refused() {
    List<String> failures = new ArrayList<>();
    var endpoint = new RemoteEndpoint("pii", "pii.internal", 9090, null, "user", "secret", false);

    var clients = WorkspaceLoaderComponent.buildRemoteClients(
      Map.of("pii", endpoint),
      // TLS settings are read only for an ssl endpoint
      null,
      failures
    );

    assertThat(clients).isEmpty();
    assertThat(failures).singleElement().asString().contains("pii").contains("ssl: true");
  }

  @Test
  void a_host_name_that_only_looks_like_loopback_is_refused() {
    List<String> failures = new ArrayList<>();
    var endpoint = new RemoteEndpoint(
      "pii",
      "127.example.com",
      9090,
      null,
      "user",
      "secret",
      false
    );

    var clients = WorkspaceLoaderComponent.buildRemoteClients(
      Map.of("pii", endpoint),
      null,
      failures
    );

    assertThat(clients).isEmpty();
    assertThat(failures).singleElement().asString().contains("ssl: true");
  }
}
