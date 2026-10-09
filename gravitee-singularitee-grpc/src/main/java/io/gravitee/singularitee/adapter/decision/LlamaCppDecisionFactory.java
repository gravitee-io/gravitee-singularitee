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
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.decision.DecisionBackend;
import io.gravitee.singularitee.inference.decision.DecisionRunner;
import io.gravitee.singularitee.inference.llama.cpp.decision.DecisionModelConfig;
import io.gravitee.singularitee.inference.llama.cpp.decision.LlamaCppDecisionBackend;
import io.gravitee.singularitee.protocol.ModelType;
import io.gravitee.singularitee.workspace.MemoryCheckPolicyType;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.LlamaCppDecisionConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;

/**
 * Creates a {@link DecisionRunnerEngine} from a {@link ModelLoadRequest}.
 *
 * <p>The GGUF (and its LoRA, when set) are resolved by {@code GgufModelResolver} before this
 * factory runs; the resolved paths come in through {@link #create(ModelLoadRequest, Path, Path)}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppDecisionFactory implements ModelEngineFactory {

  private final ChatTemplateRenderer renderer;
  private final Vertx vertx;

  /** Creates the factory with the renderer decision prompts go through. */
  public LlamaCppDecisionFactory(ChatTemplateRenderer renderer, Vertx vertx) {
    this.renderer = renderer;
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) {
    var cfg = request.llamaCppDecision();
    return create(
      request,
      Path.of(request.modelPath().isEmpty() ? request.modelName() : request.modelPath()),
      cfg.loraPath().isEmpty() ? null : Path.of(cfg.loraPath())
    );
  }

  /**
   * Creates the engine from pre-resolved local files.
   *
   * @param request   the load request carrying the decision config
   * @param modelPath the local GGUF file
   * @param loraPath  the local LoRA file, or {@code null}
   */
  public ModelEngine create(ModelLoadRequest request, Path modelPath, Path loraPath) {
    LlamaCppDecisionConfig cfg = request.llamaCppDecision();
    var backend = new LlamaCppDecisionBackend(
      new DecisionModelConfig(
        modelPath,
        loraPath,
        cfg.nGpuLayers() > 0 ? cfg.nGpuLayers() : 999,
        cfg.nCtx() > 0 ? cfg.nCtx() : null,
        cfg.nBatch() > 0 ? cfg.nBatch() : null,
        cfg.nSeqMax() > 0 ? cfg.nSeqMax() : null,
        cfg.nParallel(),
        cfg.custom() ? (cfg.codes().isEmpty() ? DecisionBackend.DEFAULT_CODES : cfg.codes()) : null,
        toMemoryCheckPolicy(request.memoryCheckPolicy())
      )
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
          ModelType.MODEL_TYPE_LLAMA_CPP_DECISION,
          vertx
        ),
        cfg.classifier()
      );
    } catch (RuntimeException e) {
      backend.close();
      throw e;
    }
  }

  private static MemoryCheckPolicy toMemoryCheckPolicy(MemoryCheckPolicyType policy) {
    if (policy == null) return MemoryCheckPolicy.WARN;
    return switch (policy) {
      case FAIL -> MemoryCheckPolicy.FAIL;
      case DISABLED -> MemoryCheckPolicy.DISABLED;
      default -> MemoryCheckPolicy.WARN;
    };
  }
}
