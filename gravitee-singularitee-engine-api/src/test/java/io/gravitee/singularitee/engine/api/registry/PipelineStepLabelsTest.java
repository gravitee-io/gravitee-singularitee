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
package io.gravitee.singularitee.engine.api.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.EmbedRequest;
import io.gravitee.singularitee.engine.api.EmbedResponse;
import io.gravitee.singularitee.engine.api.EmbeddingEngine;
import io.gravitee.singularitee.engine.api.pipeline.model.ClassifierLabelsConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A step that brings its own labels is registered only over a model that takes request labels. */
class PipelineStepLabelsTest {

  private static final List<ClassifyLabel> LABELS = List.of(new ClassifyLabel("billing", ""));

  private record Labelled(String modelId, List<ClassifyLabel> labels) implements
    ClassifierLabelsConfig {}

  private static final class Classifier implements ClassifierEngine {

    private final boolean zeroShot;

    Classifier(boolean zeroShot) {
      this.zeroShot = zeroShot;
    }

    @Override
    public boolean acceptsRequestLabels() {
      return zeroShot;
    }

    @Override
    public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
      return Single.just(new ClassifyResponse("x", 1f, Map.of("x", 1f), List.of()));
    }

    @Override
    public void close() {}
  }

  private static final class Embedder implements EmbeddingEngine {

    @Override
    public Single<EmbedResponse> rxEmbed(EmbedRequest request) {
      return Single.just(new EmbedResponse(new float[] { 1 }, 1));
    }

    @Override
    public void close() {}
  }

  private PipelineRegistry registry;

  @BeforeEach
  void setUp() {
    var models = new ModelRegistry();
    models.register("gliner", "gliner", new Classifier(true), token -> {});
    models.register("toxicity", "toxicity", new Classifier(false), token -> {});
    models.register("embedder", "embedder", new Embedder(), token -> {});
    registry = new PipelineRegistry(models);
  }

  private static PipelineModel pipeline(String id, Object config) {
    return new PipelineModel(
      id,
      id,
      "topic",
      List.of(new StepModel("topic", "classify", StepRole.STEP_ROLE_OUTPUT, config)),
      Map.of(),
      null,
      false,
      null
    );
  }

  @Test
  void labels_on_a_zero_shot_model_register() {
    registry.register(pipeline("p", new Labelled("gliner", LABELS)));

    assertThat(registry.get("p")).isPresent();
  }

  @Test
  void labels_on_a_classifier_that_cannot_take_them_fail_naming_the_step_and_the_model() {
    assertThatThrownBy(() -> registry.register(pipeline("p", new Labelled("toxicity", LABELS))))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("model 'toxicity'")
      .hasMessageContaining("labels");
    assertThat(registry.get("p")).isEmpty();
  }

  @Test
  void labels_on_a_model_that_is_not_a_classifier_fail() {
    assertThatThrownBy(() -> registry.register(pipeline("p", new Labelled("embedder", LABELS))))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("model 'embedder'");
  }

  @Test
  void labels_without_a_model_fail() {
    assertThatThrownBy(() -> registry.register(pipeline("p", new Labelled("", LABELS))))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("labels");
  }

  @Test
  void a_step_without_labels_registers_on_any_classifier_as_before() {
    registry.register(pipeline("p", new Labelled("toxicity", List.of())));

    assertThat(registry.get("p")).isPresent();
  }

  @Test
  void a_replacement_with_labels_on_a_model_that_cannot_take_them_changes_nothing() {
    registry.register(pipeline("p", new Labelled("gliner", LABELS)));
    var before = registry.get("p").orElseThrow();

    assertThatThrownBy(() ->
      registry.prepareReplacement(List.of(pipeline("p", new Labelled("toxicity", LABELS))))
    )
      .hasMessageContaining("Step 'topic'")
      .hasMessageContaining("model 'toxicity'");
    assertThat(registry.get("p").orElseThrow()).isSameAs(before);
  }
}
