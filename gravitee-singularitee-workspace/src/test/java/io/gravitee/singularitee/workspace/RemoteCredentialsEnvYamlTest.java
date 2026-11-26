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

import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ${env:NAME}} in a remote endpoint's credentials is read from the process environment
 * when the workspace loads, so a release carries the reference and never the secret.
 */
class RemoteCredentialsEnvYamlTest {

  /** Set in every test JVM on the hosts this builds on. */
  private static final String SET = "HOME";
  private static final String UNSET = "SINGULARITEE_TEST_UNSET_7F3A9C";

  private static final EnvironmentAllowlist ADMITS_TEST_NAMES = EnvironmentAllowlist.of(
    List.of(SET, "SINGULARITEE_TEST_*")
  );

  private static YamlWorkspaceLoader.WorkspaceRequests load(String remote) throws IOException {
    return load(remote, ADMITS_TEST_NAMES);
  }

  private static YamlWorkspaceLoader.WorkspaceRequests load(
    String remote,
    EnvironmentAllowlist allowlist
  ) throws IOException {
    return YamlWorkspaceLoader.loadFromString(
      "workspace:\n  name: test\n  remote:\n" + remote,
      null,
      null,
      TestStepPlugins.codecs(),
      allowlist
    );
  }

  private static YamlWorkspaceLoader.WorkspaceRequests loadWithDefaultAllowlist(String remote)
    throws IOException {
    return YamlWorkspaceLoader.loadFromString(
      "workspace:\n  name: test\n  remote:\n" + remote,
      null,
      null,
      TestStepPlugins.codecs()
    );
  }

  @Test
  void a_reference_in_a_named_server_is_read_from_the_environment() throws IOException {
    var ws = load(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: ${env:HOME}
              password: ${env:HOME}
      """
    );

    var pii = ws.remotes().get("pii");
    assertThat(pii.username()).isEqualTo(System.getenv(SET));
    assertThat(pii.password()).isEqualTo(System.getenv(SET));
    assertThat(ws.remoteErrors()).isEmpty();
  }

  @Test
  void a_reference_in_the_default_endpoint_is_read_from_the_environment() throws IOException {
    var ws = load(
      """
          default:
            host: 127.0.0.1
            port: 9090
            username: svc
            password: ${env:HOME}
      """
    );

    assertThat(ws.remotes().get("default").password()).isEqualTo(System.getenv(SET));
  }

  @Test
  void plain_credentials_are_kept_as_written() throws IOException {
    var ws = load(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: svc
              password: s3cret
      """
    );

    assertThat(ws.remotes().get("pii").username()).isEqualTo("svc");
    assertThat(ws.remotes().get("pii").password()).isEqualTo("s3cret");
  }

  @Test
  void a_missing_variable_fails_only_that_endpoint_and_names_the_variable() throws IOException {
    var ws = load(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: ${env:HOME}
              password: ${env:SINGULARITEE_TEST_UNSET_7F3A9C}
            - id: safety
              host: 127.0.0.1
              port: 9091
      """
    );

    assertThat(ws.remotes()).containsOnlyKeys("safety");
    assertThat(ws.remoteErrors()).containsOnlyKeys("pii");
    assertThat(ws.remoteErrors().get("pii"))
      .contains(UNSET)
      .contains("password")
      .doesNotContain(System.getenv(SET));
  }

  @Test
  void a_reference_with_an_invalid_name_fails_that_endpoint() throws IOException {
    var ws = load(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: svc
              password: ${env:lower-case}
      """
    );

    assertThat(ws.remotes()).isEmpty();
    assertThat(ws.remoteErrors().get("pii")).contains("password").contains("[A-Z_][A-Z0-9_]*");
  }

  @Test
  void by_default_a_variable_outside_singularitee_remote_fails_only_that_endpoint()
    throws IOException {
    var ws = loadWithDefaultAllowlist(
      """
          servers:
            - id: exfiltrate
              host: 127.0.0.1
              port: 9090
              username: a
              password: ${env:HOME}
            - id: safety
              host: 127.0.0.1
              port: 9091
      """
    );

    assertThat(ws.remotes()).containsOnlyKeys("safety");
    assertThat(ws.remoteErrors()).containsOnlyKeys("exfiltrate");
    assertThat(ws.remoteErrors().get("exfiltrate"))
      .contains(SET)
      .contains("password")
      .contains("SINGULARITEE_REMOTE_*")
      .contains("ai.remote.env-allowlist")
      .doesNotContain(System.getenv(SET));
  }

  @Test
  void by_default_a_singularitee_remote_variable_is_read() throws IOException {
    var ws = loadWithDefaultAllowlist(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: svc
              password: ${env:SINGULARITEE_REMOTE_UNSET_7F3A9C}
      """
    );

    assertThat(ws.remoteErrors().get("pii")).isEqualTo(
      "environment variable SINGULARITEE_REMOTE_UNSET_7F3A9C (password) is not set"
    );
  }

  @Test
  void a_configured_allowlist_replaces_the_default() throws IOException {
    var ws = load(
      """
          servers:
            - id: pii
              host: 127.0.0.1
              port: 9090
              username: svc
              password: ${env:SINGULARITEE_REMOTE_UNSET_7F3A9C}
      """,
      EnvironmentAllowlist.of(List.of(SET))
    );

    assertThat(ws.remoteErrors().get("pii"))
      .contains("SINGULARITEE_REMOTE_UNSET_7F3A9C")
      .contains("not allowed");
  }
}
