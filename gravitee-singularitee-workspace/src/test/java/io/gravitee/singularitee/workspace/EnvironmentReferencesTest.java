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

import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class EnvironmentReferencesTest {

  private static final UnaryOperator<String> ENV = Map.of(
    "PII_PASSWORD",
    "s3cret",
    "BLANK",
    ""
  )::get;

  @Test
  void a_reference_resolves_to_the_variable() {
    assertThat(EnvironmentReferences.resolve("${env:PII_PASSWORD}", "password", ENV)).isEqualTo(
      "s3cret"
    );
  }

  @Test
  void a_value_that_is_not_wholly_a_reference_is_kept() {
    assertThat(EnvironmentReferences.resolve("pre-${env:PII_PASSWORD}", "password", ENV)).isEqualTo(
      "pre-${env:PII_PASSWORD}"
    );
    assertThat(EnvironmentReferences.resolve(null, "password", ENV)).isNull();
  }

  @Test
  void an_unset_variable_is_named_in_the_error() {
    assertThatThrownBy(() -> EnvironmentReferences.resolve("${env:MISSING}", "password", ENV))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("environment variable MISSING (password) is not set");
  }

  @Test
  void an_empty_variable_is_refused() {
    assertThatThrownBy(() ->
      EnvironmentReferences.resolve("${env:BLANK}", "username", ENV)
    ).hasMessage("environment variable BLANK (username) is empty");
  }

  @Test
  void a_malformed_reference_is_refused_without_echoing_it() {
    assertThatThrownBy(() -> EnvironmentReferences.resolve("${env:pii}", "password", ENV))
      .hasMessageContaining("password")
      .hasMessageNotContaining("${env:pii}");
  }
}
