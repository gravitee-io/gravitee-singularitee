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
package io.gravitee.singularitee.adapter.batching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchingConfigTest {

  @Test
  void parses_sorts_and_de_duplicates() {
    assertThat(BatchingConfig.parseBoundaries("E", "256, 64,128,128")).containsExactly(
      64,
      128,
      256
    );
  }

  @Test
  void drops_entries_below_one() {
    assertThat(BatchingConfig.parseBoundaries("E", "0,128,-5")).containsExactly(128);
  }

  @Test
  void all_dropped_yields_an_empty_list() {
    assertThat(BatchingConfig.parseBoundaries("E", "0")).isEmpty();
  }

  @Test
  void malformed_list_is_null() {
    assertThat(BatchingConfig.parseBoundaries("E", "64,abc")).isNull();
  }

  @Test
  void duplicate_boundaries_build_a_batcher() {
    try (
      var batcher = new MicroBatcher<String, String>(
        "t",
        4,
        100,
        BatchingConfig.parseBoundaries("E", "64,128,128"),
        0,
        1,
        in -> in
      )
    ) {
      assertThat(batcher).isNotNull();
    }
  }
}
