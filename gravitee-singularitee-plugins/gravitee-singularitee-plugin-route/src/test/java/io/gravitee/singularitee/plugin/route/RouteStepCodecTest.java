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
package io.gravitee.singularitee.plugin.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.gravitee.singularitee.plugin.route.RouteStepConfig.RouteRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteStepCodecTest {

  private final RouteStepCodec codec = new RouteStepCodec();
  private final StepCodecContext ctx = new StepCodecContext(Map.of(), null, Map.of(), null);

  @Test
  void parsesRules() {
    var cfg = codec.parse(
      "router",
      Map.of(
        "model_id",
        "embedder",
        "strategy",
        "embedding_knn",
        "input_field",
        "judge.output",
        "default_step",
        "fallback",
        "rules",
        List.of(
          Map.of(
            "label",
            "billing",
            "sentences",
            List.of("my invoice", "refund"),
            "next_step",
            "billing_agent"
          ),
          Map.of("label", "tech", "next_step", "tech_agent")
        )
      ),
      ctx
    );

    assertThat(cfg.modelId()).isEqualTo("embedder");
    assertThat(cfg.strategy()).isEqualTo(RoutingStrategy.EMBEDDING_KNN);
    assertThat(cfg.inputField()).isEqualTo("judge.output");
    assertThat(cfg.defaultStepId()).isEqualTo("fallback");
    assertThat(cfg.rules()).containsExactly(
      new RouteRule("billing", List.of("my invoice", "refund"), "billing_agent"),
      new RouteRule("tech", List.of(), "tech_agent")
    );
    assertThat(cfg.branchTargets()).containsExactly("billing_agent", "tech_agent", "fallback");
  }

  @Test
  void defaultsToClassifierAndSkipsBlankTargets() {
    var cfg = codec.parse(
      "router",
      Map.of("model_id", "clf", "rules", List.of(Map.of("label", "x"))),
      ctx
    );

    assertThat(cfg.strategy()).isEqualTo(RoutingStrategy.CLASSIFIER);
    assertThat(cfg.defaultStepId()).isEmpty();
    assertThat(cfg.branchTargets()).isEmpty();
  }

  @Test
  void invalidShapeNamesTheStep() {
    assertThatThrownBy(() -> codec.parse("intent_router", Map.of("rules", "nope"), ctx))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("intent_router");
  }

  @Test
  void a_decision_threshold_needs_somewhere_to_send_a_less_confident_choice() {
    var rules = List.of(Map.of("label", "billing", "next_step", "billing"));
    assertThatThrownBy(() ->
      codec.parse(
        "route",
        Map.of(
          "strategy",
          "decision",
          "input_field",
          "classify.team",
          "min_confidence",
          0.3,
          "rules",
          rules
        ),
        ctx
      )
    ).hasMessageContaining("min_confidence needs default_step");

    var cfg = codec.parse(
      "route",
      Map.of(
        "strategy",
        "decision",
        "input_field",
        "classify.team",
        "min_confidence",
        0.3,
        "default_step",
        "generalist",
        "rules",
        rules
      ),
      ctx
    );
    assertThat(cfg.defaultStepId()).isEqualTo("generalist");
  }

  @Test
  void a_classifier_route_parses_its_labels() {
    var cfg = codec.parse(
      "route",
      Map.of(
        "model_id",
        "gliner",
        "labels",
        List.of(Map.of("name", "billing", "description", "Invoices and payments."), "tech"),
        "rules",
        List.of(
          Map.of("label", "billing", "next_step", "billing_agent"),
          Map.of("label", "tech", "next_step", "tech_agent")
        )
      ),
      ctx
    );

    assertThat(cfg.labels()).containsExactly(
      new ClassifyLabel("billing", "Invoices and payments."),
      new ClassifyLabel("tech", "")
    );
  }

  @Test
  void a_rule_label_that_is_not_among_the_labels_is_a_load_error() {
    assertThatThrownBy(() ->
      codec.parse(
        "route",
        Map.of(
          "model_id",
          "gliner",
          "labels",
          List.of("billing", "tech"),
          "rules",
          List.of(
            Map.of("label", "billing", "next_step", "billing_agent"),
            Map.of("label", "sales", "next_step", "sales_agent")
          )
        ),
        ctx
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'route'")
      .hasMessageContaining("rule label 'sales'")
      .hasMessageContaining("[billing, tech]");
  }

  @Test
  void labels_apply_to_the_classifier_strategy_only() {
    assertThatThrownBy(() ->
      codec.parse(
        "route",
        Map.of(
          "model_id",
          "embedder",
          "strategy",
          "embedding_knn",
          "labels",
          List.of("billing"),
          "rules",
          List.of(Map.of("label", "billing", "next_step", "billing_agent"))
        ),
        ctx
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'route'")
      .hasMessageContaining("classifier strategy");
  }

  @Test
  void without_labels_a_route_has_none() {
    var cfg = codec.parse(
      "router",
      Map.of("model_id", "clf", "rules", List.of(Map.of("label", "x", "next_step", "y"))),
      ctx
    );

    assertThat(cfg.labels()).isEmpty();
  }
}
