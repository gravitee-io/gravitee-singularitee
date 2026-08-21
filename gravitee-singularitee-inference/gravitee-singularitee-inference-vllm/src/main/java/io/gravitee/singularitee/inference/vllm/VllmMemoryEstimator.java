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
package io.gravitee.singularitee.inference.vllm;

import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import io.gravitee.vllm.engine.GpuMemoryQuery;
import io.gravitee.vllm.engine.GpuMemoryQuery.GpuMemoryInfo;

/**
 * Pre-flight VRAM estimator for models loaded via vLLM.
 *
 * <p>Accepts only primitives: the caller resolves the model shape into
 * {@code totalParams} and {@code bitsPerParam} before building {@link VllmConfig}.
 *
 * <p>Multimodal models: the parameter count already includes the vision/audio
 * encoder and projection layers, but inference adds transient memory the
 * estimate cannot capture. Encoder activations take roughly 1-3 GB for vision
 * and 0.5-1.5 GB for audio, and each media item expands into hundreds to
 * thousands of KV-cache tokens by an amount that depends on the input and the
 * architecture. When {@code multimodal} is {@code true} the safety margin is
 * therefore raised from 10 % to 25 %, and the estimate stays labelled
 * approximate. Multimodal is detected from {@code vision_config} or
 * {@code audio_config} in the model's {@code config.json}.
 *
 * <p>LoRA: with {@code enableLora}, vLLM pre-allocates buffers for
 * {@code maxLoras} adapters up to {@code maxLoraRank}. Typical configurations
 * (4 adapters, rank 16-64) cost 200 MB to 1 GB, which the safety margins cover.
 * The buffers are not sized explicitly because the allocation depends on which
 * layers each adapter touches. For {@code maxLoras >= 8} with a high rank, raise
 * {@code gpu_memory_utilization} or reduce {@code maxLoras}.
 *
 * <p>Pure computation: no side effects, never throws.
 * {@link MemoryEstimate#unknown()} is returned whenever estimation is not possible.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmMemoryEstimator {

  /**
   * Safety margin for text-only models: keep 10 % of VRAM free for CUDA
   * driver overhead, CUDA graph workspace, and vLLM internal buffers.
   */
  private static final double SAFETY_MARGIN_TEXT = 0.10;

  /**
   * Safety margin for multimodal models: keep 25 % of VRAM free to absorb
   * transient vision/audio encoder activations and media token expansion in the
   * KV cache.
   */
  private static final double SAFETY_MARGIN_MULTIMODAL = 0.25;

  /** KV-cache: F16, key + value = 2 tensors, 2 bytes each. */
  private static final int KV_BYTES_PER_TOKEN_PER_LAYER_PER_HEAD = 2 * 2;

  private VllmMemoryEstimator() {}

  /**
   * Default GPU memory utilization when the user has not configured one.
   * Matches vLLM's own default of 90 %.
   */
  private static final double DEFAULT_GPU_MEMORY_UTILIZATION = 0.9;

  /**
   * Estimates the VRAM required to load a model with vLLM.
   *
   * @param totalParams            total parameter count (e.g. from safetensors metadata).
   *                               {@code <= 0} causes a {@link MemoryEstimate#unknown()} return.
   * @param bitsPerParam           bits per parameter (16 for BF16/FP16, 32 for FP32,
   *                               4 for AWQ/GPTQ 4-bit weights).
   *                               {@code <= 0} causes a {@link MemoryEstimate#unknown()} return.
   * @param numHiddenLayers        transformer layer count (from {@code config.json}).
   * @param numKvHeads             number of KV attention heads.
   * @param headDim                per-head hidden dimension.
   * @param maxModelLen            context window size used for KV-cache sizing.
   * @param maxNumSeqs             maximum number of concurrent sequences. The total
   *                               KV-cache is estimated for {@code maxModelLen * maxNumSeqs}
   *                               tokens. {@code <= 0} defaults to {@code 1}.
   * @param gpuMemoryUtilization   fraction of total GPU memory vLLM is allowed to use
   *                               (0.0-1.0). {@code <= 0} defaults to {@code 0.9}.
   * @param multimodal             {@code true} if the model has a vision or audio encoder
   *                               (detected via {@code vision_config} or {@code audio_config}
   *                               in config.json). Bumps the safety margin from 10 % to 25 %.
   * @return an approximate {@link MemoryEstimate}, or {@link MemoryEstimate#unknown()}.
   */
  public static MemoryEstimate estimate(
    long totalParams,
    int bitsPerParam,
    int numHiddenLayers,
    int numKvHeads,
    int headDim,
    int maxModelLen,
    int maxNumSeqs,
    double gpuMemoryUtilization,
    boolean multimodal
  ) {
    if (totalParams <= 0 || bitsPerParam <= 0) {
      return MemoryEstimate.unknown();
    }
    try {
      double utilization = gpuMemoryUtilization > 0
        ? gpuMemoryUtilization
        : DEFAULT_GPU_MEMORY_UTILIZATION;
      int seqs = maxNumSeqs > 0 ? maxNumSeqs : 1;
      double margin = multimodal ? SAFETY_MARGIN_MULTIMODAL : SAFETY_MARGIN_TEXT;
      return doEstimate(
        totalParams,
        bitsPerParam,
        numHiddenLayers,
        numKvHeads,
        headDim,
        maxModelLen,
        seqs,
        utilization,
        margin,
        multimodal
      );
    } catch (Exception e) {
      return MemoryEstimate.unknown();
    }
  }

  // --- private implementation ---

  private static MemoryEstimate doEstimate(
    long totalParams,
    int bitsPerParam,
    int numHiddenLayers,
    int numKvHeads,
    int headDim,
    int maxModelLen,
    int maxNumSeqs,
    double gpuMemoryUtilization,
    double safetyMargin,
    boolean multimodal
  ) {
    GpuMemoryInfo cuda = GpuMemoryQuery.query();
    if (cuda == null) {
      return MemoryEstimate.unknown();
    }

    long weightBytes = (totalParams * bitsPerParam) / 8;
    long kvBytes = computeKvBytes(maxModelLen, maxNumSeqs, numHiddenLayers, numKvHeads, headDim);
    long totalRequired = weightBytes + kvBytes;

    // vLLM's usable budget: totalGpuMemory x gpuMemoryUtilization.
    // Within that budget we also reserve a safety margin for CUDA context,
    // NCCL buffers, FlashInfer workspace, and CUDA graph captures.
    long usableBudget = (long) (cuda.totalBytes() * gpuMemoryUtilization * (1.0 - safetyMargin));

    double suggestedUtilization = computeSuggestedUtilization(
      totalRequired,
      cuda.totalBytes(),
      safetyMargin
    );
    String suggestion = buildSuggestion(
      totalRequired,
      usableBudget,
      cuda.totalBytes(),
      suggestedUtilization,
      gpuMemoryUtilization,
      safetyMargin,
      multimodal
    );

    return MemoryEstimate.of(totalRequired, usableBudget, cuda.totalBytes(), suggestion, true);
  }

  private static long computeKvBytes(
    int maxModelLen,
    int maxNumSeqs,
    int numHiddenLayers,
    int numKvHeads,
    int headDim
  ) {
    return (
      (long) maxModelLen *
      maxNumSeqs *
      numHiddenLayers *
      numKvHeads *
      headDim *
      KV_BYTES_PER_TOKEN_PER_LAYER_PER_HEAD
    );
  }

  private static double computeSuggestedUtilization(
    long totalRequired,
    long totalBytes,
    double safetyMargin
  ) {
    if (totalBytes <= 0) return 0.9;
    double needed = totalRequired / (double) totalBytes;
    double suggested = needed / (1.0 - safetyMargin);
    return Math.max(0.1, Math.min(0.95, suggested));
  }

  private static String buildSuggestion(
    long required,
    long usableBudget,
    long totalBytes,
    double suggestedUtilization,
    double gpuMemoryUtilization,
    double safetyMargin,
    boolean multimodal
  ) {
    String mmNote = multimodal
      ? " Multimodal model detected: using 25%% safety margin for encoder activations."
      : "";
    if (required <= usableBudget) {
      double headroom = (100.0 * (usableBudget - required)) / usableBudget;
      return (
        "Model fits within %.0f%% of %.1f GiB usable budget (gpu_memory_utilization=%.0f%%, %.0f%% safety margin).%s"
      ).formatted(
        headroom,
        usableBudget / (1024.0 * 1024.0 * 1024.0),
        gpuMemoryUtilization * 100,
        safetyMargin * 100,
        mmNote
      );
    }
    return (
      "Model may not fit. Estimated %.2f GiB needed but only %.2f GiB usable " +
      "(gpu_memory_utilization=%.0f%%, %.0f%% safety margin). " +
      "Try gpu_memory_utilization=%.2f, or reduce max_model_len.%s"
    ).formatted(
      required / (1024.0 * 1024.0 * 1024.0),
      usableBudget / (1024.0 * 1024.0 * 1024.0),
      gpuMemoryUtilization * 100,
      safetyMargin * 100,
      suggestedUtilization,
      mmNote
    );
  }
}
