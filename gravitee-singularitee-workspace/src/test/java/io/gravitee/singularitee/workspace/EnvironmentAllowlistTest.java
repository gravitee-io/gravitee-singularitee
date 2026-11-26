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

import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentAllowlistTest {

  @Test
  void the_default_admits_only_the_singularitee_remote_prefix() {
    var defaults = EnvironmentAllowlist.defaults();

    assertThat(defaults.allows("SINGULARITEE_REMOTE_PII_PASSWORD")).isTrue();
    assertThat(defaults.allows("SINGULARITEE_REMOTE_")).isTrue();
    assertThat(defaults.allows("GRAVITEE_AI_SYNC_TOKEN")).isFalse();
    assertThat(defaults.allows("HF_TOKEN")).isFalse();
    assertThat(defaults.allows("SINGULARITEE_REMOTEX")).isFalse();
    assertThat(defaults.rule()).isEqualTo("SINGULARITEE_REMOTE_*");
  }

  @Test
  void an_entry_without_a_star_is_an_exact_name_and_one_with_a_star_a_prefix() {
    var allowlist = EnvironmentAllowlist.of(List.of(" PII_PASSWORD ", "VAULT_*", ""));

    assertThat(allowlist.allows("PII_PASSWORD")).isTrue();
    assertThat(allowlist.allows("PII_PASSWORD_2")).isFalse();
    assertThat(allowlist.allows("VAULT_SAFETY")).isTrue();
    assertThat(allowlist.allows("SINGULARITEE_REMOTE_X")).isFalse();
    assertThat(allowlist.rule()).isEqualTo("PII_PASSWORD, VAULT_*");
  }

  @Test
  void an_empty_allowlist_admits_nothing() {
    var none = EnvironmentAllowlist.of(List.of());

    assertThat(none.allows("SINGULARITEE_REMOTE_X")).isFalse();
    assertThat(none.rule()).isEqualTo("no variable");
  }
}
