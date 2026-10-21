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
package io.gravitee.singularitee.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackHostsTest {

  @ParameterizedTest
  @ValueSource(
    strings = {
      "localhost",
      "LOCALHOST",
      "127.0.0.1",
      "127.1.2.3",
      "::1",
      "[::1]",
      "0:0:0:0:0:0:0:1",
      "::ffff:127.0.0.1",
      " 127.0.0.1 ",
    }
  )
  void loopback_literals_are_recognised(String host) {
    assertThat(LoopbackHosts.isLoopback(host)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "127.example.com",
      "localhost.example.com",
      "pii.internal",
      "10.0.0.5",
      "0.0.0.0",
      "127.0.0.256",
      "::2",
      "not:an:address",
      "",
    }
  )
  void anything_else_is_not(String host) {
    assertThat(LoopbackHosts.isLoopback(host)).isFalse();
  }

  @Test
  void null_is_not_loopback() {
    assertThat(LoopbackHosts.isLoopback(null)).isFalse();
  }
}
