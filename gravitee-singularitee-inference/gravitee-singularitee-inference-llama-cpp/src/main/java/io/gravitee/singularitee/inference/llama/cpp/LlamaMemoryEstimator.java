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

import io.gravitee.llama.cpp.CpuMemoryQuery;
import io.gravitee.llama.cpp.CpuMemoryQuery.CpuMemoryInfo;
import io.gravitee.llama.cpp.GpuMemoryQuery;
import io.gravitee.llama.cpp.GpuMemoryQuery.GpuMemoryInfo;
import io.gravitee.llama.cpp.LlamaLogLevel;
import io.gravitee.llama.cpp.LlamaLogger;
import io.gravitee.llama.cpp.LlamaModelDims;
import io.gravitee.llama.cpp.RpcMemoryQuery;
import io.gravitee.llama.cpp.RpcMemoryQuery.RpcMemoryInfo;
import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.List;

/**
 * Pre-flight memory estimator for GGUF models loaded via llama.cpp.
 *
 * <p>Uses primitive-only queries from the {@code llamaj.cpp} binding library
 * ({@link LlamaModelDims#loadFrom(Path)}, {@link GpuMemoryQuery#queryBest()},
 * and {@link CpuMemoryQuery#query()}) and returns a {@link MemoryEstimate}
 * from {@code gravitee-inference-api}.
 *
 * <h3>GPU vs CPU vs RPC mode</h3>
 * <ul>
 *   <li><b>RPC mode</b> (when {@code rpcServers} is non-empty): queries remote
 *       GPU memory via {@link RpcMemoryQuery} — direct network calls to each
 *       RPC server without registering them as backends. The bottleneck
 *       (minimum free VRAM across servers) is used as available memory.</li>
 *   <li><b>GPU mode</b> ({@code nGpuLayers > 0}, no RPC): queries local GPU VRAM
 *       via GGML backend devices. If no GPU is found, falls back to CPU memory.</li>
 *   <li><b>CPU mode</b> ({@code nGpuLayers == 0}): queries system RAM directly
 *       via {@link CpuMemoryQuery}. All weights stay in RAM so the estimate
 *       compares total model bytes against free system memory with no safety
 *       margin (exact).</li>
 * </ul>
 *
 * <h3>Multimodal support</h3>
 * <p>When a multimodal projection file ({@code mmproj}) is present, its GGUF
 * header is also loaded via {@code noAlloc=true} and its weight size is added
 * to the total memory requirement. The mmproj file contains the vision encoder
 * and vision-language projector weights — typically 200 MB – 2 GB depending
 * on the ViT variant. This ensures VLM models (e.g. LLaVA, Qwen2-VL) get
 * an accurate weight estimate without downloading or allocating any tensors.
 *
 * <p>Note: transient activation memory used during vision encoder forward
 * passes is <b>not</b> captured — it is temporary (freed after each image)
 * and varies with image resolution. The 10 % GPU safety margin covers typical
 * cases.
 *
 * <h3>LoRA adapter support</h3>
 * <p>When a LoRA adapter file is present, its GGUF header is loaded the same
 * way and its weight size is added to the total. LoRA adapters are typically
 * small (10–200 MB) but are loaded entirely to GPU/RAM. The estimate is exact
 * since the adapter file size is read directly from the GGUF metadata.
 *
 * <h3>Native logging</h3>
 * <p>The metadata-only model load ({@link LlamaModelDims#loadFrom(Path)}) triggers
 * native llama.cpp log output (GGUF header parsing, tensor enumeration, etc.).
 * When a {@link io.gravitee.llama.cpp.LlamaLogLevel} is provided, a scoped
 * {@link io.gravitee.llama.cpp.LlamaLogger} is installed for the duration of the
 * estimate so that the native verbosity matches the model's configured level.
 *
 * <p>Pure computation — on any failure returns {@link MemoryEstimate#unknown()}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaMemoryEstimator {

  /** GPU safety margin — keep 10 % of VRAM free for driver/OS overhead. */
  private static final double GPU_SAFETY_MARGIN = 0.10;

  /** CPU safety margin — exact (no margin); system RAM is reported accurately by the OS. */
  private static final double CPU_SAFETY_MARGIN = 0.0;

  /** KV-cache bytes per token per layer per head: F16 = 2 bytes, key + value = ×2. */
  private static final int KV_BYTES_PER_TOKEN_PER_LAYER_PER_HEAD = 2 * 2;

  /** Bytes per GiB, used for human-readable formatting. */
  private static final double GB = 1024.0 * 1024.0 * 1024.0;

  private LlamaMemoryEstimator() {}

  /**
   * Estimates the memory required to load a GGUF model with the given parameters.
   *
   * <p>Priority order:
   * <ol>
   *   <li>If {@code rpcServers} is non-empty, queries remote GPU VRAM via RPC.</li>
   *   <li>If {@code nGpuLayers > 0}, queries local GPU VRAM. Falls back to CPU
   *       if no local GPU is found.</li>
   *   <li>If {@code nGpuLayers == 0}, queries system RAM (CPU-only mode).</li>
   * </ol>
   *
   * @param modelPath   Absolute path to the main GGUF model file.
   * @param mmprojPath  Absolute path to the multimodal projection GGUF file,
   *                    or {@code null} for text-only models.
   * @param loraPath    Absolute path to a LoRA adapter GGUF file, or {@code null}.
   * @param nGpuLayers  Number of transformer layers to offload to GPU.
   *                    Use {@code 0} for CPU-only inference.
   * @param nCtx        Per-sequence context window size in tokens.
   * @param nSeqMax     Maximum number of concurrent sequences. The total KV-cache
   *                    is allocated for {@code nCtx * nSeqMax} tokens.
   * @param rpcServers  RPC server endpoints in "host:port" format, or {@code null}
   *                    / empty for local-only inference.
   * @param logLevel    The llama.cpp native log level to use during the metadata-only
   *                    model load, or {@code null} to leave the default (which may
   *                    produce verbose native output). When non-null, a
   *                    {@link LlamaLogger} is installed before loading and remains
   *                    active for the duration of the estimate.
   * @return An exact {@link MemoryEstimate} ({@code isApproximate=false}),
   *         or {@link MemoryEstimate#unknown()} if estimation is not possible.
   */
  public static MemoryEstimate estimate(
    Path modelPath,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    int nSeqMax,
    List<String> rpcServers,
    LlamaLogLevel logLevel
  ) {
    try {
      LlamaBackend.init();
      if (logLevel != null) {
        try (Arena logArena = Arena.ofConfined()) {
          LlamaLogger llamaLogger = new LlamaLogger(logArena);
          if (logLevel == LlamaLogLevel.NONE) {
            llamaLogger.setLogging(logLevel, s -> {});
          } else {
            llamaLogger.setLogging(logLevel);
          }
          return doEstimate(modelPath, mmprojPath, loraPath, nGpuLayers, nCtx, nSeqMax, rpcServers);
        }
      }
      return doEstimate(modelPath, mmprojPath, loraPath, nGpuLayers, nCtx, nSeqMax, rpcServers);
    } catch (Exception e) {
      return MemoryEstimate.unknown();
    }
  }

  /**
   * Convenience overload without log level — native logging uses the default level.
   *
   * @see #estimate(Path, Path, Path, int, int, int, List, LlamaLogLevel)
   */
  public static MemoryEstimate estimate(
    Path modelPath,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    int nSeqMax,
    List<String> rpcServers
  ) {
    return estimate(modelPath, mmprojPath, loraPath, nGpuLayers, nCtx, nSeqMax, rpcServers, null);
  }

  /**
   * Convenience overload for local-only inference (no RPC servers, no custom log level).
   *
   * @see #estimate(Path, Path, Path, int, int, int, List, LlamaLogLevel)
   */
  public static MemoryEstimate estimate(
    Path modelPath,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    int nSeqMax
  ) {
    return estimate(modelPath, mmprojPath, loraPath, nGpuLayers, nCtx, nSeqMax, null, null);
  }

  // --- private implementation ---

  private static MemoryEstimate doEstimate(
    Path modelPath,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    int nSeqMax,
    List<String> rpcServers
  ) {
    LlamaModelDims dims = LlamaModelDims.loadFrom(modelPath);
    int totalCtx = nCtx * Math.max(1, nSeqMax);

    // RPC mode: query remote GPU VRAM (takes priority over local GPU)
    if (rpcServers != null && !rpcServers.isEmpty()) {
      return estimateRpc(dims, mmprojPath, loraPath, nGpuLayers, totalCtx, rpcServers);
    }

    // CPU-only mode: skip GPU entirely, check system RAM
    if (nGpuLayers <= 0) {
      return estimateCpu(dims, mmprojPath, loraPath, totalCtx);
    }

    // GPU mode: try GPU first, fall back to CPU if no GPU found
    GpuMemoryInfo gpuMemory = GpuMemoryQuery.queryBest();
    if (gpuMemory != null) {
      return estimateGpu(dims, mmprojPath, loraPath, nGpuLayers, totalCtx, gpuMemory);
    }

    // No GPU found — fall back to CPU memory check
    return estimateCpu(dims, mmprojPath, loraPath, totalCtx);
  }

  // --- GPU estimation (existing logic) ---

  private static MemoryEstimate estimateGpu(
    LlamaModelDims dims,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    GpuMemoryInfo gpuMemory
  ) {
    int effectiveGpuLayers = Math.min(nGpuLayers, dims.nLayers());
    long gpuWeightBytes = computeGpuWeightBytes(
      dims.totalWeightBytes(),
      effectiveGpuLayers,
      dims.nLayers()
    );

    long mmprojBytes = loadAuxiliaryWeightBytes(mmprojPath);
    long loraBytes = loadAuxiliaryWeightBytes(loraPath);
    long kvBytes = computeKvBytes(nCtx, effectiveGpuLayers, dims.nHeadKv(), dims.headDim());
    long totalRequired = gpuWeightBytes + mmprojBytes + loraBytes + kvBytes;

    long fixedOverhead = mmprojBytes + loraBytes;
    int suggestedLayers = computeSuggestedLayers(dims, fixedOverhead, nCtx, gpuMemory.freeBytes());
    String breakdown = buildBreakdown(gpuWeightBytes, kvBytes, mmprojBytes, loraBytes);
    String suggestion = buildGpuSuggestion(
      breakdown,
      totalRequired,
      gpuMemory.freeBytes(),
      effectiveGpuLayers,
      suggestedLayers
    );

    return MemoryEstimate.of(
      totalRequired,
      gpuMemory.freeBytes(),
      gpuMemory.totalBytes(),
      GPU_SAFETY_MARGIN,
      suggestion,
      false
    );
  }

  // --- RPC estimation ---

  private static MemoryEstimate estimateRpc(
    LlamaModelDims dims,
    Path mmprojPath,
    Path loraPath,
    int nGpuLayers,
    int nCtx,
    List<String> rpcServers
  ) {
    RpcMemoryInfo rpcMemory = RpcMemoryQuery.queryAll(rpcServers);
    if (rpcMemory == null) {
      return MemoryEstimate.unknown();
    }

    // RPC distributes layers across servers proportionally to free VRAM
    int effectiveGpuLayers = Math.min(nGpuLayers, dims.nLayers());
    long gpuWeightBytes = computeGpuWeightBytes(
      dims.totalWeightBytes(),
      effectiveGpuLayers,
      dims.nLayers()
    );

    long mmprojBytes = loadAuxiliaryWeightBytes(mmprojPath);
    long loraBytes = loadAuxiliaryWeightBytes(loraPath);
    long kvBytes = computeKvBytes(nCtx, effectiveGpuLayers, dims.nHeadKv(), dims.headDim());
    long totalRequired = gpuWeightBytes + mmprojBytes + loraBytes + kvBytes;

    String suggestion = buildRpcSuggestion(
      gpuWeightBytes,
      kvBytes,
      mmprojBytes,
      loraBytes,
      totalRequired,
      rpcMemory.freeBytes(),
      rpcMemory.serverCount()
    );

    return MemoryEstimate.of(
      totalRequired,
      rpcMemory.freeBytes(),
      rpcMemory.totalBytes(),
      GPU_SAFETY_MARGIN,
      suggestion,
      false
    );
  }

  // --- CPU estimation ---

  private static MemoryEstimate estimateCpu(
    LlamaModelDims dims,
    Path mmprojPath,
    Path loraPath,
    int nCtx
  ) {
    CpuMemoryInfo cpuMemory = CpuMemoryQuery.query();
    if (cpuMemory == null) {
      return MemoryEstimate.unknown();
    }

    // On CPU: all weights + KV-cache + auxiliary files reside in system RAM
    long modelBytes = dims.totalWeightBytes();
    long mmprojBytes = loadAuxiliaryWeightBytes(mmprojPath);
    long loraBytes = loadAuxiliaryWeightBytes(loraPath);
    long kvBytes = computeKvBytes(nCtx, dims.nLayers(), dims.nHeadKv(), dims.headDim());
    long totalRequired = modelBytes + mmprojBytes + loraBytes + kvBytes;

    String breakdown = buildBreakdown(modelBytes, kvBytes, mmprojBytes, loraBytes);
    String suggestion = buildCpuSuggestion(breakdown, totalRequired, cpuMemory.freeBytes());

    return MemoryEstimate.of(
      totalRequired,
      cpuMemory.freeBytes(),
      cpuMemory.totalBytes(),
      CPU_SAFETY_MARGIN,
      suggestion,
      false
    );
  }

  private static long computeGpuWeightBytes(long totalWeightBytes, int gpuLayers, int totalLayers) {
    if (totalLayers == 0) return totalWeightBytes;
    return (totalWeightBytes * gpuLayers) / totalLayers;
  }

  /**
   * Loads the GGUF header of an auxiliary file (mmproj or LoRA) and returns
   * its total weight size in bytes. Returns 0 if the path is null or the
   * file cannot be read.
   */
  private static long loadAuxiliaryWeightBytes(Path path) {
    if (path == null) return 0;
    try {
      return LlamaModelDims.loadFrom(path).totalWeightBytes();
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static long computeKvBytes(int nCtx, int gpuLayers, int nHeadKv, int headDim) {
    return ((long) nCtx * gpuLayers * nHeadKv * headDim * KV_BYTES_PER_TOKEN_PER_LAYER_PER_HEAD);
  }

  /** Binary-searches for the max number of layers that fit within available VRAM. */
  private static int computeSuggestedLayers(
    LlamaModelDims dims,
    long mmprojBytes,
    int nCtx,
    long freeBytes
  ) {
    long usable = (long) (freeBytes * (1.0 - GPU_SAFETY_MARGIN)) - mmprojBytes;
    if (usable <= 0) return 0;
    int lo = 0,
      hi = dims.nLayers(),
      best = 0;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      long w = computeGpuWeightBytes(dims.totalWeightBytes(), mid, dims.nLayers());
      long kv = computeKvBytes(nCtx, mid, dims.nHeadKv(), dims.headDim());
      if (w + kv <= usable) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }

  private static String buildGpuSuggestion(
    String breakdown,
    long required,
    long available,
    int requestedLayers,
    int suggestedLayers
  ) {
    if (required <= available * (1.0 - GPU_SAFETY_MARGIN)) {
      return "%s Model fits with %.0f%% headroom.".formatted(
        breakdown,
        (100.0 * (available - required)) / available
      );
    }
    if (suggestedLayers > 0) {
      return "%s Try nGpuLayers=%d (requested %d) to fit within available VRAM.".formatted(
        breakdown,
        suggestedLayers,
        requestedLayers
      );
    }
    return "%s Model is too large for GPU. Consider CPU-only inference (nGpuLayers=0).".formatted(
      breakdown
    );
  }

  private static String buildCpuSuggestion(String breakdown, long required, long available) {
    if (required <= available) {
      return "%s Model fits in system RAM with %.0f%% headroom.".formatted(
        breakdown,
        (100.0 * (available - required)) / available
      );
    }
    return "%s Model requires more RAM than currently available. Free up system memory or use a smaller model.".formatted(
      breakdown
    );
  }

  private static String buildRpcSuggestion(
    long gpuWeightBytes,
    long kvBytes,
    long mmprojBytes,
    long loraBytes,
    long totalRequired,
    long totalFreeBytes,
    int serverCount
  ) {
    String breakdown = buildBreakdown(gpuWeightBytes, kvBytes, mmprojBytes, loraBytes);
    String servers = serverCount == 1 ? "1 RPC server" : serverCount + " RPC servers";
    if (totalRequired <= totalFreeBytes * (1.0 - GPU_SAFETY_MARGIN)) {
      return "%s Model fits across %s (combined free=%.2f GiB) with %.0f%% headroom.".formatted(
        breakdown,
        servers,
        totalFreeBytes / GB,
        (100.0 * (totalFreeBytes - totalRequired)) / totalFreeBytes
      );
    }
    return "%s Model is too large for %s (combined free=%.2f GiB). Add more servers or use a smaller model.".formatted(
      breakdown,
      servers,
      totalFreeBytes / GB
    );
  }

  /** Builds a human-readable breakdown of estimated memory components. */
  private static String buildBreakdown(
    long weightBytes,
    long kvBytes,
    long mmprojBytes,
    long loraBytes
  ) {
    StringBuilder sb = new StringBuilder();
    sb.append(
      "Breakdown: weights=%.2f GiB, KV-cache=%.2f GiB".formatted(weightBytes / GB, kvBytes / GB)
    );
    if (mmprojBytes > 0) {
      sb.append(", mmproj=%.2f GiB".formatted(mmprojBytes / GB));
    }
    if (loraBytes > 0) {
      sb.append(", LoRA=%.2f GiB".formatted(loraBytes / GB));
    }
    sb.append(".");
    return sb.toString();
  }
}
