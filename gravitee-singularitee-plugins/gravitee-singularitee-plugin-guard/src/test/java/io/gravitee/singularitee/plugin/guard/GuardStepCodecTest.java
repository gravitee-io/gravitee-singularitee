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
package io.gravitee.singularitee.plugin.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.plugin.guard.GuardStepConfig.GuardTrigger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardStepCodecTest {

  private final GuardStepCodec codec = new GuardStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(Map.of(), null, Map.of(), null);

  @Test
  void parsesTriggersList() {
    var cfg = codec.parse(
      "g",
      Map.of(
        "model_id",
        "toxicity",
        "input_field",
        "prompt",
        "output_field",
        "g.clean",
        "action",
        "redact",
        "redact_with_entity_type",
        true,
        "message",
        "Blocked: {{ g.label }}",
        "triggers",
        List.of(Map.of("label", "toxic", "score", 0.8), Map.of("label", "insult", "score", 0.5))
      ),
      ctx
    );

    assertThat(cfg.modelId()).isEqualTo("toxicity");
    assertThat(cfg.inputField()).isEqualTo("prompt");
    assertThat(cfg.outputField()).isEqualTo("g.clean");
    assertThat(cfg.action()).isEqualTo(GuardAction.REDACT);
    assertThat(cfg.redactWithEntityType()).isTrue();
    assertThat(cfg.message()).isEqualTo("Blocked: {{ g.label }}");
    assertThat(cfg.triggers()).containsExactly(
      new GuardTrigger("toxic", 0.8f),
      new GuardTrigger("insult", 0.5f)
    );
  }

  @Test
  void singleTriggerLandsInTheList() {
    var cfg = codec.parse(
      "g",
      Map.of("model_id", "pii", "trigger", Map.of("label", "PII", "score", 0.5)),
      ctx
    );

    assertThat(cfg.action()).isEqualTo(GuardAction.REJECT);
    assertThat(cfg.triggers()).containsExactly(new GuardTrigger("PII", 0.5f));
    assertThat(cfg.message()).isEmpty();
    assertThat(cfg.outputField()).isEmpty();
  }

  @Test
  void triggersWinOverSingleTrigger() {
    var cfg = codec.parse(
      "g",
      Map.of(
        "trigger",
        Map.of("label", "old"),
        "triggers",
        List.of(Map.of("label", "new", "score", 0.9))
      ),
      ctx
    );

    assertThat(cfg.triggers()).containsExactly(new GuardTrigger("new", 0.9f));
  }

  @Test
  void invalidShapeNamesTheStep() {
    assertThatThrownBy(() -> codec.parse("my_guard", Map.of("triggers", "not-a-list"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("my_guard");
  }
}
