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
package io.gravitee.singularitee.adapter.decision;

import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.adapter.textgen.VllmEngineFactory;
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.decision.DecisionBackend;
import io.gravitee.singularitee.inference.decision.DecisionRunner;
import io.gravitee.singularitee.inference.vllm.decision.VllmDecisionModel;
import io.gravitee.singularitee.inference.vllm.decision.VllmDecisionModelConfig;
import io.gravitee.singularitee.inference.vllm.decision.VllmLabelBackend;
import io.gravitee.singularitee.protocol.ModelType;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.VllmDecisionConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;

/**
 * Creates a vLLM decision engine from a {@link ModelLoadRequest}:
 *
 * <ul>
 *   <li>{@code decision1} / {@code decision2}: a {@link VllmDecisionEngine} over vLLM Semantic
 *       Router's runtime, which resolves, downloads and verifies the model's files itself (pinned
 *       by revision and checksum) under the server's models directory unless {@code cache_dir}
 *       says otherwise;</li>
 *   <li>{@code custom}: a {@link DecisionRunnerEngine} scoring any causal model on label codes,
 *       on a vLLM engine built from the {@code vllm:} block; its weights are downloaded by
 *       {@code VllmModelResolver} and come in through {@link #create(ModelLoadRequest, Path)}.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmDecisionFactory implements ModelEngineFactory {

  /** Sub-directory of the models directory the runtime downloads into. */
  static final String CACHE_SUBDIR = "vllm-decision";

  private final Path modelsDir;
  private final String hfToken;
  private final ChatTemplateRenderer renderer;
  private final VllmEngineFactory engines;
  private final Vertx vertx;

  /**
   * @param modelsDir the server's models directory, or {@code null} for the Hugging Face cache
   * @param hfToken   Hugging Face token for gated repositories, or {@code null}
   * @param renderer  the renderer custom models' prompts go through
   * @param engines   maps a {@code vllm:} block to engine configuration, with the deployment's
   *                  GPU topology
   */
  public VllmDecisionFactory(
    Path modelsDir,
    String hfToken,
    ChatTemplateRenderer renderer,
    VllmEngineFactory engines,
    Vertx vertx
  ) {
    this.modelsDir = modelsDir;
    this.hfToken = hfToken;
    this.renderer = renderer;
    this.engines = engines;
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) {
    VllmDecisionConfig cfg = request.vllmDecision();
    return cfg.custom() ? create(request, null) : runtime(request, cfg);
  }

  /**
   * Creates a custom model against already-downloaded weights.
   *
   * @param resolvedPath the local weights directory, or {@code null} to let vLLM resolve the
   *                     repository itself
   */
  public ModelEngine create(ModelLoadRequest request, Path resolvedPath) {
    VllmDecisionConfig cfg = request.vllmDecision();
    var backend = VllmLabelBackend.load(
      engines.engineConfig(
        request.modelName(),
        cfg.vllm(),
        resolvedPath,
        request.memoryCheckPolicy()
      ),
      cfg.codes().isEmpty() ? DecisionBackend.DEFAULT_CODES : cfg.codes()
    );
    try {
      return DecisionClassifierEngine.wrap(
        new DecisionRunnerEngine(
          new DecisionRunner(
            backend,
            DecisionRunnerEngine.promptSource(
              cfg.promptSource(),
              cfg.promptTemplate(),
              cfg.prefill()
            ),
            renderer,
            cfg.temperatures(),
            cfg.reverse()
          ),
          ModelType.MODEL_TYPE_VLLM_DECISION,
          vertx
        ),
        cfg.classifier()
      );
    } catch (RuntimeException e) {
      backend.close();
      throw e;
    }
  }

  private ModelEngine runtime(ModelLoadRequest request, VllmDecisionConfig cfg) {
    String model = request.modelName();
    String cacheDir = cfg.cacheDir() != null && !cfg.cacheDir().isBlank()
      ? cfg.cacheDir()
      : modelsDir == null
        ? null
        : modelsDir.resolve(CACHE_SUBDIR).toString();
    var loaded = VllmDecisionModel.load(
      new VllmDecisionModelConfig(
        model,
        VllmDecisionModel.familyOf(model, cfg.family()),
        cfg.revision(),
        cfg.device(),
        cfg.profile(),
        cfg.engine(),
        cfg.memoryBudgetGib(),
        cfg.threads(),
        cacheDir,
        cfg.offline(),
        null,
        hfToken
      )
    );
    try {
      return DecisionClassifierEngine.wrap(new VllmDecisionEngine(loaded, vertx), cfg.classifier());
    } catch (RuntimeException e) {
      loaded.close();
      throw e;
    }
  }
}
