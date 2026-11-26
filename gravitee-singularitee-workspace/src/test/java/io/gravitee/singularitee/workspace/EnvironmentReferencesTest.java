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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class EnvironmentReferencesTest {

  private static final UnaryOperator<String> ENV = Map.of(
    "PII_PASSWORD",
    "s3cret",
    "BLANK",
    "",
    "HOME",
    "/home/secret-path"
  )::get;

  private static final EnvironmentAllowlist ALLOWED = EnvironmentAllowlist.of(
    List.of("PII_PASSWORD", "BLANK", "MISSING")
  );

  private static String resolve(String value, String field) {
    return EnvironmentReferences.resolve(value, field, ALLOWED, ENV);
  }

  @Test
  void a_reference_resolves_to_the_variable() {
    assertThat(resolve("${env:PII_PASSWORD}", "password")).isEqualTo("s3cret");
  }

  @Test
  void a_value_that_is_not_wholly_a_reference_is_kept() {
    assertThat(resolve("pre-${env:PII_PASSWORD}", "password")).isEqualTo("pre-${env:PII_PASSWORD}");
    assertThat(resolve(null, "password")).isNull();
  }

  @Test
  void an_unset_variable_is_named_in_the_error() {
    assertThatThrownBy(() -> resolve("${env:MISSING}", "password"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("environment variable MISSING (password) is not set");
  }

  @Test
  void an_empty_variable_is_refused() {
    assertThatThrownBy(() -> resolve("${env:BLANK}", "username")).hasMessage(
      "environment variable BLANK (username) is empty"
    );
  }

  @Test
  void a_malformed_reference_is_refused_without_echoing_it() {
    assertThatThrownBy(() -> resolve("${env:pii}", "password"))
      .hasMessageContaining("password")
      .hasMessageNotContaining("${env:pii}");
  }

  @Test
  void a_variable_outside_the_allowlist_is_refused_naming_it_and_the_rule_but_not_its_value() {
    assertThatThrownBy(() -> resolve("${env:HOME}", "password"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("HOME")
      .hasMessageContaining("password")
      .hasMessageContaining("PII_PASSWORD, BLANK, MISSING")
      .hasMessageContaining("ai.remote.env-allowlist")
      .hasMessageNotContaining("/home/secret-path");
  }

  @Test
  void a_refused_variable_is_never_read() {
    UnaryOperator<String> reads = name -> {
      throw new AssertionError("read " + name);
    };
    assertThatThrownBy(() ->
      EnvironmentReferences.resolve("${env:HOME}", "password", ALLOWED, reads)
    ).isInstanceOf(IllegalArgumentException.class);
  }
}
