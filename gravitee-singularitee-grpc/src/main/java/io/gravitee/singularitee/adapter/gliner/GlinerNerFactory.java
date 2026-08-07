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

import io.gravitee.lab.gliner4j.GLiNER4jNER;
import io.gravitee.lab.gliner4j.runtime.RuntimeConfig;
import io.gravitee.lab.gliner4j.schema.EntityDefinition;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.GlinerNerConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates a GLiNER4j zero-shot NER engine from a {@link ModelLoadRequest}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerNerFactory implements ModelEngineFactory {

  private final Vertx vertx;

  public GlinerNerFactory(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) throws Exception {
    GlinerNerConfig cfg = request.glinerNer();

    List<EntityDefinition> entities = cfg
      .entities()
      .stream()
      .map(e ->
        e.description().isBlank()
          ? new EntityDefinition(e.name())
          : new EntityDefinition(e.name(), e.description())
      )
      .toList();

    String variant = cfg.variant().isBlank() ? "onnx" : cfg.variant();
    float threshold = cfg.threshold();
    int tokenCap = cfg.tokenCap();
    List<String> entityNames = cfg
      .entities()
      .stream()
      .map(e -> e.name())
      .toList();

    GLiNER4jNER ner = GLiNER4jNER.load(
      Path.of(cfg.modelDir()),
      entities,
      variant,
      GlinerRuntimeConfigs.applyEnvThreads(RuntimeConfig.builder()).build()
    );
    return new GlinerNerEngine(ner, threshold, tokenCap, entityNames, vertx);
  }
}
