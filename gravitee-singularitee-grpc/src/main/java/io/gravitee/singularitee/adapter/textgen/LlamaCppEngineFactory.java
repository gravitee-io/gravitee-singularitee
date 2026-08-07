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
package io.gravitee.singularitee.adapter.textgen;

import io.gravitee.llama.cpp.AttentionType;
import io.gravitee.llama.cpp.FlashAttentionType;
import io.gravitee.llama.cpp.PoolingType;
import io.gravitee.llama.cpp.SpeculativeConfig;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import java.nio.file.Path;

/**
 * Creates a llama.cpp-backed {@link LlamaCppTextGenEngine} from a {@link ModelLoadRequest}.
 *
 * <p>This class and {@link LlamaCppTextGenEngine} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-llama-cpp} types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppEngineFactory implements ModelEngineFactory {

  @Override
  public ModelEngine create(ModelLoadRequest request) throws Exception {
    String path = (request.modelPath() == null || request.modelPath().isEmpty())
      ? request.modelName()
      : request.modelPath();
    return create(request, Path.of(path));
  }

  /**
   * Creates the engine using a pre-resolved local model path (after HF download).
   * The {@code lora_path} / {@code mmproj_path} sidecar files are resolved from the
   * config as bare paths — prefer {@link #create(ModelLoadRequest, Path, Path, Path)}
   * when those sidecars also need HF resolution/download.
   *
   * @param request           the model load request with llama.cpp configuration
   * @param resolvedModelPath the local GGUF file path
   * @return a new {@link LlamaCppTextGenEngine}, not yet started
   */
  public ModelEngine create(ModelLoadRequest request, Path resolvedModelPath) {
    LlamaCppConfig cfg = request.llamaCppConfig();
    Path lora = cfg.loraPath().isEmpty() ? null : Path.of(cfg.loraPath());
    Path mmproj = cfg.mmprojPath().isEmpty() ? null : Path.of(cfg.mmprojPath());
    return create(request, resolvedModelPath, mmproj, lora);
  }

  /**
   * Creates the engine using pre-resolved local paths for the model and its
   * multimodal projection ({@code mmproj}) / LoRA sidecar files.
   *
   * @param request            the model load request with llama.cpp configuration
   * @param resolvedModelPath  the local GGUF file path
   * @param resolvedMmprojPath the local mmproj GGUF path, or {@code null} if none
   * @param resolvedLoraPath   the local LoRA adapter path, or {@code null} if none
   * @return a new {@link LlamaCppTextGenEngine}, not yet started
   */
  public ModelEngine create(
    ModelLoadRequest request,
    Path resolvedModelPath,
    Path resolvedMmprojPath,
    Path resolvedLoraPath
  ) {
    LlamaCppConfig cfg = request.llamaCppConfig();
    return create(
      request,
      resolvedModelPath,
      resolvedMmprojPath,
      resolvedLoraPath,
      cfg.draftPath().isEmpty() ? null : Path.of(cfg.draftPath()),
      cfg.eagle3Path().isEmpty() ? null : Path.of(cfg.eagle3Path())
    );
  }

  /**
   * As above, with the speculative sidecars pre-resolved.
   *
   * <p>A draft model or an EAGLE3 head usually lives in a DIFFERENT repository from the target,
   * so these cannot be resolved from the target's repo like mmproj and LoRA are.
   *
   * @param resolvedDraftPath  local draft GGUF, or {@code null}
   * @param resolvedEagle3Path local EAGLE3 head GGUF, or {@code null}
   */
  public ModelEngine create(
    ModelLoadRequest request,
    Path resolvedModelPath,
    Path resolvedMmprojPath,
    Path resolvedLoraPath,
    Path resolvedDraftPath,
    Path resolvedEagle3Path
  ) {
    LlamaCppConfig cfg = request.llamaCppConfig();
    int flavours =
      (cfg.mtp() ? 1 : 0) +
      (resolvedDraftPath != null ? 1 : 0) +
      (resolvedEagle3Path != null ? 1 : 0);
    if (flavours > 1) {
      throw new IllegalArgumentException(
        "Configure at most one speculative flavour: mtp, draft_path or eagle3_path — they are " +
          "three ways to produce the same draft tokens, not a stack."
      );
    }

    var modelConfig = ModelConfig.builder(resolvedModelPath)
      .nCtx(cfg.nCtx() > 0 ? cfg.nCtx() : 4096)
      .nBatch(cfg.nBatch() > 0 ? cfg.nBatch() : 2048)
      .nUBatch(cfg.nUbatch() > 0 ? cfg.nUbatch() : 512)
      .nSeqMax(cfg.nSeqMax() > 0 ? cfg.nSeqMax() : 8)
      .nGpuLayers(cfg.nGpuLayers() > 0 ? cfg.nGpuLayers() : 999)
      .poolingType(resolvePoolingType(cfg.poolingType()))
      .attentionType(resolveAttentionType(cfg.attentionType()))
      .flashAttnType(resolveFlashAttnType(cfg.flashAttnType()))
      // Default true (llama.cpp parity): offload_kqv=false keeps the KV cache and attention
      // on the CPU — ~3x decode throughput loss on Metal.
      .offloadKQV(cfg.offloadKqv() == null || cfg.offloadKqv())
      // Default true (llamaj.cpp Main parity): unpinned mmap'd weights get evicted under
      // memory pressure and a MoE model then decodes at SSD-fault speed.
      .useMlock(cfg.useMlock() == null || cfg.useMlock())
      .loraPath(resolvedLoraPath)
      .mmprojPath(resolvedMmprojPath)
      .mediaMarker(cfg.mediaMarker().isBlank() ? null : cfg.mediaMarker())
      .memoryCheckPolicy(toMemoryCheckPolicy(request.memoryCheckPolicy()))
      .mtp(cfg.mtp())
      .cacheTypeK(resolveGgmlType(cfg.cacheTypeK()))
      .cacheTypeV(resolveGgmlType(cfg.cacheTypeV()))
      // Default true when unset (`!has || get`) — cross-request KV prefix cache.
      .promptCache(cfg.promptCache() == null || cfg.promptCache())
      .promptCacheMinTokens(cfg.promptCacheMinTokens() > 0 ? cfg.promptCacheMinTokens() : 64)
      // <= 0 disables the soft landing; the unbiased path stays bit-identical.
      .eogRampStart(cfg.eogRampStart() > 0f ? cfg.eogRampStart() : -1f)
      .eogRampMaxBias(cfg.eogRampMaxBias() > 0f ? cfg.eogRampMaxBias() : 100f)
      .draftPath(resolvedDraftPath)
      .eagle3Path(resolvedEagle3Path)
      .speculative(
        cfg.mtp() && cfg.speculative() != null ? toSpeculativeConfig(cfg.speculative()) : null
      )
      .build();

    return new LlamaCppTextGenEngine(modelConfig);
  }

  private static SpeculativeConfig toSpeculativeConfig(
    io.gravitee.singularitee.workspace.config.SpeculativeConfig s
  ) {
    int nDraft = s.nDraft() > 0 ? s.nDraft() : 2;
    return new SpeculativeConfig(
      nDraft,
      s.temperature(),
      s.topK(),
      s.topP() > 0 ? s.topP() : 1.0f,
      s.seed(),
      s.draftMin() > 0 ? s.draftMin() : nDraft,
      s.pMin()
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

  private static PoolingType resolvePoolingType(String value) {
    if (value == null || value.isBlank()) return PoolingType.UNSPECIFIED;
    try {
      return PoolingType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return PoolingType.UNSPECIFIED;
    }
  }

  private static AttentionType resolveAttentionType(String value) {
    if (value == null || value.isBlank()) return AttentionType.UNSPECIFIED;
    try {
      return AttentionType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return AttentionType.UNSPECIFIED;
    }
  }

  private static io.gravitee.llama.cpp.GgmlType resolveGgmlType(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return io.gravitee.llama.cpp.GgmlType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
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
