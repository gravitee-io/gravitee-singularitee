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
package io.gravitee.singularitee.adapter.gliner;

import static io.gravitee.lab.gliner4j.runtime.ExecutionProvider.AUTO;

import io.gravitee.lab.gliner4j.GLiNER4jClassifier;
import io.gravitee.lab.gliner4j.runtime.ExecutionProvider;
import io.gravitee.lab.gliner4j.runtime.RuntimeConfig;
import io.gravitee.lab.gliner4j.schema.ClassificationLabel;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.GlinerClassifierConfig;
import io.gravitee.singularitee.workspace.config.GlinerLabelDef;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates a GLiNER4j zero-shot classifier engine from a {@link ModelLoadRequest}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerClassifierFactory implements ModelEngineFactory {

  private final Vertx vertx;

  public GlinerClassifierFactory(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) throws Exception {
    GlinerClassifierConfig cfg = request.glinerClassifier();

    List<ClassificationLabel> labels = cfg
      .labels()
      .stream()
      .map(GlinerClassifierFactory::toClassificationLabel)
      .toList();

    String variant = cfg.variant().isBlank() ? "onnx" : cfg.variant();
    float threshold = cfg.threshold();
    int tokenCap = cfg.tokenCap();
    List<String> labelNames = cfg.labels().stream().map(GlinerLabelDef::name).toList();

    GLiNER4jClassifier classifier = GLiNER4jClassifier.load(
      Path.of(cfg.modelDir()),
      labels,
      variant,
      getDefaults()
    );

    return new GlinerClassifierEngine(classifier, threshold, tokenCap, labelNames, vertx);
  }

  private static RuntimeConfig getDefaults() {
    ExecutionProvider provider = ExecutionProvider.resolve(AUTO);
    return GlinerRuntimeConfigs.applyEnvThreads(
      RuntimeConfig.builder().executionProvider(provider)
    ).build();
  }

  private static ClassificationLabel toClassificationLabel(GlinerLabelDef def) {
    if (def.description().isBlank()) return new ClassificationLabel(def.name());
    return new ClassificationLabel(def.name(), def.description());
  }
}
