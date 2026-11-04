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
package io.gravitee.singularitee.engine.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.EmbeddingEngine;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.classifier.CompositeClassifierEngine;
import io.gravitee.singularitee.engine.classifier.LlmSpans;
import io.gravitee.singularitee.engine.classifier.LlmTokenClassifierEngine;
import io.gravitee.singularitee.plugin.test.TestStepPlugins;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientLocalLlmTokenClassifierTest {

  private static final String YAML = """
    workspace:
      name: test
      models:
        - id: pii
          type: llm_token_classifier
          visible: false
          llm_token_classifier:
            model_id: span-llm
            system_prompt: Find all personal data.
            labels: [PERSON, PHONE]
            max_tokens: 1500
            context:
              enable_thinking: false
            score: max_token_perplexity
        - id: pii-default
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
        - id: pii-unscored
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
            score: none
        - id: pii-all
          type: composite_classifier
          composite_classifier:
            models: [pii]
        - id: no-llm
          type: llm_token_classifier
          llm_token_classifier:
            model_id: absent
        - id: wrong-kind
          type: llm_token_classifier
          llm_token_classifier:
            model_id: embedder
        - id: no-ref
          type: llm_token_classifier
        - id: bad-score
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
            score: logprob_slope
        - id: ner-entities
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
            structured_output: false
            answer:
              spans: entities
              label: type
              text: value
              metadata: [person]
        - id: ner-bare
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
            answer:
              spans: ""
        - id: bad-answer
          type: llm_token_classifier
          llm_token_classifier:
            model_id: span-llm
            answer:
              label: text
      pipelines: []
    """;

  @Test
  void builds_over_a_registered_llm_and_composes(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("workspace.yaml");
    Files.writeString(file, YAML);
    var ws = YamlWorkspaceLoader.load(file, null, TestStepPlugins.codecs());

    var def = ws.clientLocalModels().getFirst().definition().llmTokenClassifier();
    assertThat(def.modelId()).isEqualTo("span-llm");
    assertThat(def.labels()).containsExactly("PERSON", "PHONE");
    assertThat(def.maxTokens()).isEqualTo(1500);
    assertThat(def.context()).containsEntry("enable_thinking", false);
    assertThat(def.score()).isEqualTo("max_token_perplexity");

    var registry = new ModelRegistry();
    registry.register("span-llm", "span-llm", mock(TextGenEngine.class), t -> {});
    registry.register("embedder", "embedder", mock(EmbeddingEngine.class), t -> {});

    ClientLocalModelRegistrar.register(
      ws.clientLocalModels(),
      registry,
      (id, name, engine, task, visible, modalities) ->
        registry.register(id, name, engine, t -> {}, task, visible, modalities)
    );

    var pii = registry.get("pii").orElseThrow();
    assertThat(pii.engine()).isInstanceOf(LlmTokenClassifierEngine.class);
    assertThat(pii.task()).isEqualTo(ModelTasks.TOKEN_CLASSIFICATION);
    assertThat(pii.visible()).isFalse();

    var composite = registry.get("pii-all").orElseThrow();
    assertThat(composite.engine()).isInstanceOf(CompositeClassifierEngine.class);
    assertThat(((ClassifierEngine) composite.engine()).task()).isEqualTo(
      ModelTasks.TOKEN_CLASSIFICATION
    );

    assertThat(registry.get("no-llm")).isEmpty();
    assertThat(registry.get("wrong-kind")).isEmpty();
    assertThat(registry.get("no-ref")).isEmpty();
    assertThat(registry.get("bad-score")).isEmpty();
    assertThat(scoreOf(registry, "pii")).isEqualTo("max_token_perplexity");
    assertThat(scoreOf(registry, "pii-default")).isEqualTo("max_entropy");
    assertThat(scoreOf(registry, "pii-unscored")).isNull();

    var defaults = optionsOf(registry, "pii-default");
    assertThat(defaults.format()).isEqualTo(LlmSpans.Format.DEFAULT);
    assertThat(defaults.structuredOutput()).isTrue();

    var entities = optionsOf(registry, "ner-entities");
    assertThat(entities.format()).isEqualTo(
      new LlmSpans.Format("entities", "type", "value", List.of("person"))
    );
    assertThat(entities.structuredOutput()).isFalse();

    var bare = optionsOf(registry, "ner-bare");
    assertThat(bare.format().bareArray()).isTrue();
    assertThat(bare.format().label()).isEqualTo("label");
    assertThat(registry.get("bad-answer")).isEmpty();
  }

  private static LlmTokenClassifierEngine.Options optionsOf(ModelRegistry registry, String id) {
    return ((LlmTokenClassifierEngine) registry.get(id).orElseThrow().engine()).options();
  }

  private static String scoreOf(ModelRegistry registry, String id) {
    var engine = (LlmTokenClassifierEngine) registry.get(id).orElseThrow().engine();
    var score = engine.options().score();
    return score == null ? null : score.field();
  }
}
