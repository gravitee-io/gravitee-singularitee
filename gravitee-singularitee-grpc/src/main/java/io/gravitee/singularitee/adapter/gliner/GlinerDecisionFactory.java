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

import io.gravitee.lab.gliner4j.GLiNER4jDecider;
import io.gravitee.lab.gliner4j.runtime.ExecutionProvider;
import io.gravitee.lab.gliner4j.runtime.RuntimeConfig;
import io.gravitee.lab.gliner4j.schema.Decision;
import io.gravitee.lab.gliner4j.schema.DecisionQuestion;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.adapter.decision.DecisionClassifierEngine;
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.GlinerDecisionConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;

/**
 * Creates a GLiNER4j decision engine from a {@link ModelLoadRequest}: a GLiNER2 bundle (such as
 * GLiNER2.5-Decide) answering typed questions.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerDecisionFactory implements ModelEngineFactory {

  private final Vertx vertx;

  /** Creates the factory with the Vert.x instance engines schedule on. */
  public GlinerDecisionFactory(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) {
    GlinerDecisionConfig cfg = request.glinerDecision();
    String variant = cfg.variant().isBlank() ? "onnx" : cfg.variant();
    GLiNER4jDecider decider = GLiNER4jDecider.load(
      Path.of(cfg.modelDir()),
      variant,
      runtimeConfig()
    );
    try {
      return DecisionClassifierEngine.wrap(
        new GlinerDecisionEngine(new Gliner4jDecider(decider), vertx),
        cfg.classifier()
      );
    } catch (RuntimeException e) {
      decider.close();
      throw e;
    }
  }

  private static RuntimeConfig runtimeConfig() {
    ExecutionProvider provider = ExecutionProvider.resolve(AUTO);
    return GlinerRuntimeConfigs.applyEnvThreads(
      RuntimeConfig.builder().executionProvider(provider)
    ).build();
  }

  private record Gliner4jDecider(GLiNER4jDecider decider) implements GlinerDecisionEngine.Decider {
    @Override
    public Decision decide(String state, DecisionQuestion question) {
      return decider.decide(state, question);
    }

    @Override
    public void close() {
      decider.close();
    }
  }
}
