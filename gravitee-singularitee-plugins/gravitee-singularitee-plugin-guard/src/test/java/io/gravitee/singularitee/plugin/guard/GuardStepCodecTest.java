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

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardOnEmpty;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardScope;
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

  @Test
  void scope_and_on_empty_default_to_the_strict_values() {
    var cfg = codec.parse("g", Map.of("model_id", "toxicity"), ctx);

    assertThat(cfg.scope()).isEqualTo(GuardScope.CONVERSATION);
    assertThat(cfg.onEmpty()).isEqualTo(GuardOnEmpty.REJECT);
  }

  @Test
  void parses_scope_and_on_empty() {
    var cfg = codec.parse(
      "g",
      Map.of("model_id", "toxicity", "scope", "last_user", "on_empty", "skip"),
      ctx
    );

    assertThat(cfg.scope()).isEqualTo(GuardScope.LAST_USER);
    assertThat(cfg.onEmpty()).isEqualTo(GuardOnEmpty.SKIP);
  }

  @Test
  void scope_with_a_named_input_field_is_refused() {
    assertThatThrownBy(() ->
      codec.parse(
        "g",
        Map.of("model_id", "toxicity", "input_field", "generate.output", "scope", "user_turns"),
        ctx
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("input_field");
  }

  @Test
  void parses_labels_with_triggers_among_them() {
    var cfg = codec.parse(
      "g",
      Map.of(
        "model_id",
        "gliner",
        "labels",
        List.of(
          "prompt injection",
          Map.of("name", "jailbreak", "description", "Asks to ignore rules.")
        ),
        "triggers",
        List.of(Map.of("label", "jailbreak", "score", 0.6))
      ),
      ctx
    );

    assertThat(cfg.labels()).containsExactly(
      new ClassifyLabel("prompt injection", ""),
      new ClassifyLabel("jailbreak", "Asks to ignore rules.")
    );
    assertThat(cfg.triggers()).containsExactly(new GuardTrigger("jailbreak", 0.6f));
  }

  @Test
  void a_trigger_label_that_is_not_among_the_labels_is_a_load_error() {
    assertThatThrownBy(() ->
      codec.parse(
        "g",
        Map.of(
          "model_id",
          "gliner",
          "labels",
          List.of("jailbreak"),
          "trigger",
          Map.of("label", "toxic", "score", 0.5)
        ),
        ctx
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'g'")
      .hasMessageContaining("trigger label 'toxic'")
      .hasMessageContaining("[jailbreak]");
  }

  @Test
  void without_labels_any_trigger_label_is_kept_as_before() {
    var cfg = codec.parse(
      "g",
      Map.of("model_id", "toxicity", "trigger", Map.of("label", "toxic", "score", 0.5)),
      ctx
    );

    assertThat(cfg.labels()).isEmpty();
    assertThat(cfg.triggers()).containsExactly(new GuardTrigger("toxic", 0.5f));
  }
}
