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
package io.gravitee.singularitee.inference.onnx.bert;

import static io.gravitee.singularitee.inference.onnx.bert.DjlTelemetry.OPT_OUT_TRACKING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DjlTelemetryTest {

  private String previous;

  @BeforeEach
  void setUp() {
    assumeTrue(
      System.getenv(OPT_OUT_TRACKING) == null,
      "OPT_OUT_TRACKING is set in the environment"
    );
    previous = System.clearProperty(OPT_OUT_TRACKING);
  }

  @AfterEach
  void tearDown() {
    if (previous == null) {
      System.clearProperty(OPT_OUT_TRACKING);
    } else {
      System.setProperty(OPT_OUT_TRACKING, previous);
    }
  }

  @Test
  void should_opt_out_when_nothing_is_configured() {
    DjlTelemetry.optOut();

    assertEquals("true", System.getProperty(OPT_OUT_TRACKING));
  }

  @Test
  void should_keep_the_value_set_by_the_operator() {
    System.setProperty(OPT_OUT_TRACKING, "false");

    DjlTelemetry.optOut();

    assertEquals("false", System.getProperty(OPT_OUT_TRACKING));
  }
}
