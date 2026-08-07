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
package io.gravitee.singularitee.inference.llama.cpp;

import io.gravitee.llama.cpp.AttentionType;
import io.gravitee.llama.cpp.FlashAttentionType;
import io.gravitee.llama.cpp.LlamaLogLevel;
import io.gravitee.llama.cpp.PoolingType;
import io.gravitee.llama.cpp.SpeculativeConfig;
import io.gravitee.llama.cpp.SplitMode;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import java.nio.file.Path;
import java.util.List;

/**
 * Complete configuration for a llama.cpp model.
 *
 * <p>Prefer constructing instances via the fluent {@link Builder}:
 * <pre>{@code
 * ModelConfig cfg = ModelConfig.builder(Path.of("model.gguf"))
 *     .nGpuLayers(999)
 *     .poolingType(PoolingType.MEAN)
 *     .build();
 * }</pre>
 *
 * <p>The only mandatory field is {@code modelPath}; every other field has a
 * sensible default that matches standard llama.cpp behaviour.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ModelConfig(
  Path modelPath,
  int nCtx,
  int nBatch,
  int nUBatch,
  int nSeqMax,
  int nThreads,
  int nThreadsBatch,
  int nGpuLayers,
  boolean useMlock,
  boolean useMmap,
  SplitMode splitMode,
  int mainGpu,
  PoolingType poolingType,
  AttentionType attentionType,
  FlashAttentionType flashAttnType,
  boolean offloadKQV,
  boolean noPerf,
  LlamaLogLevel logLevel,
  Path loraPath,
  Path mmprojPath,
  String mediaMarker,
  List<String> rpcServers,
  MemoryCheckPolicy memoryCheckPolicy,
  boolean mtp,
  SpeculativeConfig speculative,
  io.gravitee.llama.cpp.GgmlType cacheTypeK,
  io.gravitee.llama.cpp.GgmlType cacheTypeV,
  boolean promptCache,
  int promptCacheMinTokens,
  float eogRampStart,
  float eogRampMaxBias,
  Path draftPath,
  Path eagle3Path
) {
  /**
   * Returns true if this model configuration includes a multimodal projection file,
   * indicating the model supports vision and/or audio input.
   */
  public boolean isMultimodal() {
    return mmprojPath != null;
  }

  /**
   * Returns true if this model configuration includes RPC server endpoints
   * for distributed inference offloading.
   */
  public boolean hasRpcServers() {
    return rpcServers != null && !rpcServers.isEmpty();
  }

  /**
   * Returns a new {@link Builder} pre-set with all defaults.
   * Only {@code modelPath} must be supplied before calling {@link Builder#build()}.
   *
   * @param modelPath path to the GGUF model file
   */
  public static Builder builder(Path modelPath) {
    return new Builder(modelPath);
  }

  /**
   * Fluent builder for {@link ModelConfig}.
   *
   * <p>Default values:
   * <ul>
   *   <li>{@code nCtx} = 0 (defer to llama.cpp / model metadata)</li>
   *   <li>{@code nBatch} = 0 (defer to llama.cpp default)</li>
   *   <li>{@code nUBatch} = 0 (defer to llama.cpp default)</li>
   *   <li>{@code nSeqMax} = 0 (defer to llama.cpp default)</li>
   *   <li>{@code nThreads} / {@code nThreadsBatch} = 0 (defer to llama.cpp default — forcing
   *       all cores drags Apple Silicon E-cores into every decode sync, ~3x slower on Metal)</li>
   *   <li>{@code nGpuLayers} = 0 (CPU-only; set to 999 to offload everything to GPU)</li>
   *   <li>{@code useMlock} = false</li>
   *   <li>{@code useMmap} = true</li>
   *   <li>{@code splitMode} = {@link SplitMode#LAYER}</li>
   *   <li>{@code mainGpu} = 0</li>
   *   <li>{@code poolingType} = {@link PoolingType#UNSPECIFIED} (auto-detect in llama.cpp)</li>
   *   <li>{@code attentionType} = {@link AttentionType#UNSPECIFIED} (auto-detect)</li>
   *   <li>{@code flashAttnType} = {@link FlashAttentionType#AUTO}</li>
   *   <li>{@code offloadKQV} = true</li>
   *   <li>{@code noPerf} = false</li>
   *   <li>{@code logLevel} = {@link LlamaLogLevel#WARN}</li>
   *   <li>{@code loraPath} = null</li>
   *   <li>{@code mmprojPath} = null</li>
   *   <li>{@code rpcServers} = empty list</li>
   *   <li>{@code memoryCheckPolicy} = {@link MemoryCheckPolicy#WARN}</li>
   * </ul>
   */
  public static final class Builder {

    private final Path modelPath;
    private int nCtx = 0;
    private int nBatch = 0;
    private int nUBatch = 0;
    private int nSeqMax = 0;
    // 0 = keep llama.cpp's native default. Forcing availableProcessors() spans efficiency
    // cores on Apple Silicon and slows Metal-offloaded decode dramatically.
    private int nThreads = 0;
    private int nThreadsBatch = 0;
    private int nGpuLayers = 0;
    private boolean useMlock = false;
    private boolean useMmap = true;
    private SplitMode splitMode = SplitMode.LAYER;
    private int mainGpu = 0;
    private PoolingType poolingType = PoolingType.UNSPECIFIED;
    private AttentionType attentionType = AttentionType.UNSPECIFIED;
    private FlashAttentionType flashAttnType = FlashAttentionType.AUTO;
    private boolean offloadKQV = true;
    private boolean noPerf = false;
    private LlamaLogLevel logLevel = LlamaLogLevel.WARN;
    private Path loraPath = null;
    private Path mmprojPath = null;
    private String mediaMarker = null;
    private List<String> rpcServers = List.of();
    private MemoryCheckPolicy memoryCheckPolicy = MemoryCheckPolicy.WARN;
    private boolean mtp = false;
    private SpeculativeConfig speculative = null;
    private io.gravitee.llama.cpp.GgmlType cacheTypeK = null;
    private io.gravitee.llama.cpp.GgmlType cacheTypeV = null;
    private boolean promptCache = true;
    private int promptCacheMinTokens = 64;
    private float eogRampStart = -1f;
    private float eogRampMaxBias = 100f;
    private Path draftPath;
    private Path eagle3Path;

    private Builder(Path modelPath) {
      if (modelPath == null) throw new IllegalArgumentException("modelPath must not be null");
      this.modelPath = modelPath;
    }

    public Builder nCtx(int nCtx) {
      this.nCtx = nCtx;
      return this;
    }

    public Builder nBatch(int nBatch) {
      this.nBatch = nBatch;
      return this;
    }

    public Builder nUBatch(int nUBatch) {
      this.nUBatch = nUBatch;
      return this;
    }

    public Builder nSeqMax(int nSeqMax) {
      this.nSeqMax = nSeqMax;
      return this;
    }

    public Builder nThreads(int nThreads) {
      this.nThreads = nThreads;
      return this;
    }

    public Builder nThreadsBatch(int nThreadsBatch) {
      this.nThreadsBatch = nThreadsBatch;
      return this;
    }

    /** Number of model layers to offload to GPU. Use {@code 999} to offload all layers. */
    public Builder nGpuLayers(int nGpuLayers) {
      this.nGpuLayers = nGpuLayers;
      return this;
    }

    public Builder useMlock(boolean useMlock) {
      this.useMlock = useMlock;
      return this;
    }

    public Builder useMmap(boolean useMmap) {
      this.useMmap = useMmap;
      return this;
    }

    public Builder splitMode(SplitMode splitMode) {
      this.splitMode = splitMode;
      return this;
    }

    public Builder mainGpu(int mainGpu) {
      this.mainGpu = mainGpu;
      return this;
    }

    public Builder poolingType(PoolingType poolingType) {
      this.poolingType = poolingType;
      return this;
    }

    public Builder attentionType(AttentionType attentionType) {
      this.attentionType = attentionType;
      return this;
    }

    public Builder flashAttnType(FlashAttentionType flashAttnType) {
      this.flashAttnType = flashAttnType;
      return this;
    }

    public Builder offloadKQV(boolean offloadKQV) {
      this.offloadKQV = offloadKQV;
      return this;
    }

    public Builder noPerf(boolean noPerf) {
      this.noPerf = noPerf;
      return this;
    }

    public Builder logLevel(LlamaLogLevel logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    public Builder loraPath(Path loraPath) {
      this.loraPath = loraPath;
      return this;
    }

    public Builder mmprojPath(Path mmprojPath) {
      this.mmprojPath = mmprojPath;
      return this;
    }

    /** Overrides the multimodal media marker; {@code null}/blank keeps the mtmd library default. */
    public Builder mediaMarker(String mediaMarker) {
      this.mediaMarker = mediaMarker;
      return this;
    }

    public Builder rpcServers(List<String> rpcServers) {
      this.rpcServers = rpcServers != null ? rpcServers : List.of();
      return this;
    }

    public Builder memoryCheckPolicy(MemoryCheckPolicy memoryCheckPolicy) {
      this.memoryCheckPolicy = memoryCheckPolicy;
      return this;
    }

    /** Enables MTP self-speculative decoding; requires a model with an MTP head. */
    public Builder mtp(boolean mtp) {
      this.mtp = mtp;
      return this;
    }

    /** Speculative decoding tuning; {@code null} with {@code mtp} = greedy default window. */
    public Builder speculative(SpeculativeConfig speculative) {
      this.speculative = speculative;
      return this;
    }

    /** K-cache data type; {@code null} keeps the llama.cpp default (F16). */
    public Builder cacheTypeK(io.gravitee.llama.cpp.GgmlType cacheTypeK) {
      this.cacheTypeK = cacheTypeK;
      return this;
    }

    /** V-cache data type; {@code null} keeps F16. Quantized V requires flash attention. */
    public Builder cacheTypeV(io.gravitee.llama.cpp.GgmlType cacheTypeV) {
      this.cacheTypeV = cacheTypeV;
      return this;
    }

    /** Cross-request KV prefix cache (default true). */
    public Builder promptCache(boolean promptCache) {
      this.promptCache = promptCache;
      return this;
    }

    /** Minimum shared-prefix tokens to prefer a warm slot without a key match (default 64). */
    /** Draft model GGUF for model-drafting speculation; null disables it. */
    public Builder draftPath(Path draftPath) {
      this.draftPath = draftPath;
      return this;
    }

    /** EAGLE3 speculator head GGUF; null disables it. */
    public Builder eagle3Path(Path eagle3Path) {
      this.eagle3Path = eagle3Path;
      return this;
    }

    /** Fraction of maxTokens at which the EOG ramp begins; negative disables it. */
    public Builder eogRampStart(float eogRampStart) {
      this.eogRampStart = eogRampStart;
      return this;
    }

    /** Logit boost in nats at the cap. */
    public Builder eogRampMaxBias(float eogRampMaxBias) {
      this.eogRampMaxBias = eogRampMaxBias;
      return this;
    }

    public Builder promptCacheMinTokens(int promptCacheMinTokens) {
      this.promptCacheMinTokens = promptCacheMinTokens;
      return this;
    }

    public ModelConfig build() {
      if (mtp && mmprojPath != null) {
        throw new IllegalArgumentException(
          "MTP speculative decoding is not supported together with a multimodal projector (mmproj_path)"
        );
      }
      return new ModelConfig(
        modelPath,
        nCtx,
        nBatch,
        nUBatch,
        nSeqMax,
        nThreads,
        nThreadsBatch,
        nGpuLayers,
        useMlock,
        useMmap,
        splitMode,
        mainGpu,
        poolingType,
        attentionType,
        flashAttnType,
        offloadKQV,
        noPerf,
        logLevel,
        loraPath,
        mmprojPath,
        mediaMarker,
        rpcServers,
        memoryCheckPolicy,
        mtp,
        mtp && speculative == null ? SpeculativeConfig.greedy(2) : speculative,
        cacheTypeK,
        cacheTypeV,
        promptCache,
        promptCacheMinTokens,
        eogRampStart,
        eogRampMaxBias,
        draftPath,
        eagle3Path
      );
    }
  }
}
