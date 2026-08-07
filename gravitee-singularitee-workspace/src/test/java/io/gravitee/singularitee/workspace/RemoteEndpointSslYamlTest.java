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
package io.gravitee.singularitee.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code remote:} endpoints reach their server over plaintext unless {@code ssl: true};
 * an endpoint carrying Basic credentials therefore sends them in the clear by default.
 */
class RemoteEndpointSslYamlTest {

  @Test
  void ssl_defaults_to_false_and_is_read_per_endpoint(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        remote:
          servers:
            - id: plain
              host: 127.0.0.1
              port: 9090
            - id: secured
              host: models.example.com
              port: 443
              ssl: true
              username: pii
              password: s3cret
        models:
          - id: llm
            type: remote_llm
            server: plain
        pipelines: []
      """
    );

    var remotes = YamlWorkspaceLoader.load(workspace).remotes();
    var plain = remotes.get("plain");
    var secured = remotes.get("secured");

    assertThat(plain.effectiveSsl()).as("unset ssl must stay plaintext").isFalse();
    assertThat(secured.effectiveSsl()).isTrue();
    assertThat(secured.hasCredentials()).isTrue();
  }

  @Test
  void ssl_is_read_on_the_default_endpoint(@TempDir Path tmp) throws IOException {
    Path workspace = tmp.resolve("workspace.yaml");
    Files.writeString(
      workspace,
      """
      workspace:
        name: test
        remote:
          default:
            host: models.example.com
            port: 443
            ssl: true
        models:
          - id: llm
            type: remote_llm
        pipelines: []
      """
    );

    var remotes = YamlWorkspaceLoader.load(workspace).remotes();
    assertThat(remotes).hasSize(1);
    assertThat(remotes.values().iterator().next().effectiveSsl()).isTrue();
  }
}
