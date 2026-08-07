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

import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import java.nio.file.Path;

/**
 * Configuration for a vLLM engine instance.
 *
 * <p>Unlike llama.cpp which takes a local GGUF file path, vLLM takes a
 * HuggingFace model identifier and downloads/loads the model itself via Python.
 *
 * @param model HuggingFace model ID (e.g. "Qwen/Qwen3-0.6B"), used for logging and
 *              for the pre-flight shape lookup
 * @param modelPath Local directory holding the already-downloaded model. The server
 *                  resolves this before construction (see VllmModelResolver), so vLLM
 *                  loads from disk and never downloads anything itself. Null falls back
 *                  to letting vLLM resolve {@code model} on its own.
 * @param dtype Torch dtype string (e.g. "auto", "float16", "bfloat16")
 * @param maxModelLen Maximum context length override, or 0 for model default
 * @param maxNumSeqs Maximum number of concurrent sequences (batch size cap)
 * @param gpuMemoryUtilization Fraction of GPU memory to use (0.0-1.0), or 0 for default
 * @param maxNumBatchedTokens Maximum tokens per batch, or 0 for default
 * @param enforceEager Whether to disable CUDA graphs
 * @param trustRemoteCode Whether to allow custom code from HuggingFace
 * @param quantization Quantization method (e.g. "awq", "gptq"), or null for none
 * @param swapSpace CPU swap space in GB, or 0 for default
 * @param seed Global random seed, or null for random
 * @param enablePrefixCaching Whether to enable KV cache prefix caching, or {@code null}
 *                            to leave the engine default in place
 * @param enableChunkedPrefill Whether to enable chunked prefill for long prompts
 * @param kvCacheDtype KV cache dtype (e.g. "auto", "fp8"), or null for default
 * @param enableLora Whether to enable LoRA adapter support
 * @param maxLoras Maximum number of concurrent LoRA adapters, or 0 for default
 * @param maxLoraRank Maximum LoRA rank, or 0 for default
 * @param venvPath Path to the Python venv, or null for auto-detection
 * @param memoryCheckPolicy Pre-flight VRAM check policy (FAIL / WARN / DISABLED). Default: WARN.
 * @param totalParams Total model parameter count, used for VRAM weight estimation.
 *                    0 means unknown — memory check is skipped.
 * @param bytesPerParam Bytes per parameter (2 for BF16/FP16, 4 for FP32).
 *                      0 means unknown — memory check is skipped.
 * @param numHiddenLayers Transformer layer count from config.json, used for KV-cache estimation.
 * @param numKvHeads Number of KV attention heads from config.json.
 * @param headDim Per-head hidden dimension from config.json.
 * @param multimodal Whether the model is a multimodal model (VLM or audio-LM).
 *                   Detected from the presence of {@code vision_config} or
 *                   {@code audio_config} in the model's {@code config.json}.
 *                   When {@code true}, the VRAM safety margin is increased
 *                   from 10 % to 25 % to account for transient encoder
 *                   activations and media token expansion.
 * @param maxPositionEmbeddings Maximum sequence length the model's positional encoding
 *                              supports, from {@code config.json}. Used as the fallback
 *                              context length for KV-cache sizing when the user does not
 *                              explicitly set {@code maxModelLen}. 0 means unknown.
 * @param hfToken Optional HuggingFace access token for gated/private models.
 *                Injected as HF_TOKEN and HUGGING_FACE_HUB_TOKEN env vars before model load.
 * @param enableSleepMode Whether to enable vLLM sleep mode (CuMemAllocator). Null = use default
 *                        (true on CUDA, false elsewhere). Set to false to use cudaMalloc instead.
 * @param tensorParallelSize Number of GPUs to shard each layer across, or 0 for vLLM's default (1).
 *                           Required to serve a model whose weights do not fit on one card.
 * @param pipelineParallelSize Number of pipeline stages to split the layers into, or 0 for the
 *                             default (1). Combined with tensor parallelism for multi-node.
 * @param distributedExecutorBackend Executor for distributed workers ("mp" or "ray"), or null for
 *                                   vLLM's default. Setting either parallel size above 1, or this
 *                                   backend, switches vLLM4j to the V1 engine with subprocess
 *                                   workers.
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record VllmConfig(
  String model,
  Path modelPath,
  String dtype,
  int maxModelLen,
  int maxNumSeqs,
  double gpuMemoryUtilization,
  int maxNumBatchedTokens,
  boolean enforceEager,
  boolean trustRemoteCode,
  String quantization,
  double swapSpace,
  Integer seed,
  Boolean enablePrefixCaching,
  boolean enableChunkedPrefill,
  String kvCacheDtype,
  boolean enableLora,
  int maxLoras,
  int maxLoraRank,
  Path venvPath,
  MemoryCheckPolicy memoryCheckPolicy,
  long totalParams,
  int bytesPerParam,
  int numHiddenLayers,
  int numKvHeads,
  int headDim,
  boolean multimodal,
  int maxPositionEmbeddings,
  String hfToken,
  Boolean enableSleepMode,
  int tensorParallelSize,
  int pipelineParallelSize,
  String distributedExecutorBackend
) {
  public VllmConfig {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be null or blank");
    }
    if (dtype == null || dtype.isBlank()) {
      dtype = "auto";
    }
    if (memoryCheckPolicy == null) {
      memoryCheckPolicy = MemoryCheckPolicy.WARN;
    }
  }
}
