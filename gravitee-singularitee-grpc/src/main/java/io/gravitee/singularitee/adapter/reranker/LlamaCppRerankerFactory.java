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
package io.gravitee.singularitee.adapter.reranker;

import io.gravitee.llama.cpp.AttentionType;
import io.gravitee.llama.cpp.FlashAttentionType;
import io.gravitee.llama.cpp.PoolingType;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.api.reranker.RerankTemplate;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.inference.llama.cpp.encoder.LlamaCppRerankerModel;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppRerankerConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;

/**
 * Creates a llama.cpp-backed {@link LlamaCppRerankerEngine} from a
 * {@link ModelLoadRequest}.
 *
 * <p>The GGUF model file is resolved by {@link io.gravitee.singularitee.grpc.resolver.GgufModelResolver}
 * before this factory is invoked; the resolved path is passed via
 * {@link #create(ModelLoadRequest, Path)}.
 *
 * <p>This class and {@link LlamaCppRerankerEngine} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-llama-cpp} reranker types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppRerankerFactory implements ModelEngineFactory {

  private final GioMaths gioMaths;
  private final Vertx vertx;

  public LlamaCppRerankerFactory(GioMaths gioMaths, Vertx vertx) {
    this.gioMaths = gioMaths;
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) throws Exception {
    String path = (request.modelPath() == null || request.modelPath().isEmpty())
      ? request.modelName()
      : request.modelPath();
    return create(request, Path.of(path));
  }

  /**
   * Creates the engine using a pre-resolved local GGUF path (after HF download).
   *
   * @param request           the model load request with llama.cpp reranker configuration
   * @param resolvedModelPath the local GGUF file path
   * @return a new {@link LlamaCppRerankerEngine}, ready to use
   */
  public ModelEngine create(ModelLoadRequest request, Path resolvedModelPath) {
    LlamaCppRerankerConfig cfg = request.llamaCppReranker();
    LlamaCppConfig llamaCfg = cfg.llamaCppConfig() != null
      ? cfg.llamaCppConfig()
      : LlamaCppConfig.getDefaultInstance();

    var modelConfig = ModelConfig.builder(resolvedModelPath)
      .nCtx(llamaCfg.nCtx() > 0 ? llamaCfg.nCtx() : 512)
      .nBatch(llamaCfg.nBatch() > 0 ? llamaCfg.nBatch() : 512)
      .nUBatch(llamaCfg.nUbatch() > 0 ? llamaCfg.nUbatch() : 512)
      .nSeqMax(llamaCfg.nSeqMax() > 0 ? llamaCfg.nSeqMax() : 8)
      .nGpuLayers(llamaCfg.nGpuLayers() > 0 ? llamaCfg.nGpuLayers() : 999)
      // Reranker models must use RANK pooling — force it regardless of what the YAML says.
      .poolingType(PoolingType.RANK)
      .attentionType(resolveAttentionType(llamaCfg.attentionType()))
      .flashAttnType(resolveFlashAttnType(llamaCfg.flashAttnType()))
      .offloadKQV(llamaCfg.offloadKqv() == null || llamaCfg.offloadKqv())
      .loraPath(llamaCfg.loraPath().isEmpty() ? null : Path.of(llamaCfg.loraPath()))
      .memoryCheckPolicy(toMemoryCheckPolicy(request.memoryCheckPolicy()))
      .build();

    RerankScoring scoring = AbstractRerankerEngine.parseScoring(cfg.scoring());

    RerankTemplate template = cfg.rerankTemplate().isBlank()
      ? RerankTemplate.PLAIN
      : (query, document) ->
        cfg.rerankTemplate().replace("{query}", query).replace("{document}", document);

    return new LlamaCppRerankerEngine(
      new LlamaCppRerankerModel(modelConfig, gioMaths, template, scoring),
      vertx
    );
  }

  private static MemoryCheckPolicy toMemoryCheckPolicy(
    io.gravitee.singularitee.workspace.MemoryCheckPolicyType policy
  ) {
    if (policy == null) return MemoryCheckPolicy.WARN;
    return switch (policy) {
      case FAIL -> MemoryCheckPolicy.FAIL;
      case DISABLED -> MemoryCheckPolicy.DISABLED;
      default -> MemoryCheckPolicy.WARN;
    };
  }

  private static AttentionType resolveAttentionType(String value) {
    if (value == null || value.isBlank()) return AttentionType.UNSPECIFIED;
    try {
      return AttentionType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return AttentionType.UNSPECIFIED;
    }
  }

  private static FlashAttentionType resolveFlashAttnType(String value) {
    if (value == null || value.isBlank()) return FlashAttentionType.AUTO;
    try {
      return FlashAttentionType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return FlashAttentionType.AUTO;
    }
  }
}
