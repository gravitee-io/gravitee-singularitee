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
package io.gravitee.singularitee.plugin.regexguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.plugin.regexguard.RegexGuardStepConfig.RegexEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegexGuardStepCodecTest {

  private final RegexGuardStepCodec codec = new RegexGuardStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(Map.of(), null, Map.of(), null);

  @Test
  void parsesPatterns() {
    var cfg = codec.parse(
      "pii",
      Map.of(
        "input_field",
        "prompt",
        "action",
        "redact",
        "redact_with_entity_type",
        true,
        "output_field",
        "pii.clean",
        "message",
        "Blocked {{ pii.entity_type }}",
        "patterns",
        List.of(
          Map.of("name", "SSN", "pattern", "\\d{3}-\\d{2}-\\d{4}"),
          Map.of("name", "Email Address", "pattern", "[^@\\s]+@[^@\\s]+")
        )
      ),
      ctx
    );

    assertThat(cfg.inputField()).isEqualTo("prompt");
    assertThat(cfg.action()).isEqualTo(GuardAction.REDACT);
    assertThat(cfg.redactWithEntityType()).isTrue();
    assertThat(cfg.outputField()).isEqualTo("pii.clean");
    assertThat(cfg.message()).isEqualTo("Blocked {{ pii.entity_type }}");
    assertThat(cfg.patterns()).containsExactly(
      new RegexEntity("SSN", "\\d{3}-\\d{2}-\\d{4}"),
      new RegexEntity("Email Address", "[^@\\s]+@[^@\\s]+")
    );
  }

  @Test
  void defaultsWhenEmpty() {
    var cfg = codec.parse("pii", Map.of(), ctx);

    assertThat(cfg.action()).isEqualTo(GuardAction.REJECT);
    assertThat(cfg.patterns()).isEmpty();
    assertThat(cfg.inputField()).isEmpty();
    assertThat(cfg.outputField()).isEmpty();
    assertThat(cfg.message()).isEmpty();
  }

  @Test
  void invalidShapeNamesTheStep() {
    assertThatThrownBy(() -> codec.parse("pii_guard", Map.of("patterns", "nope"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pii_guard");
  }
}
