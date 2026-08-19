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

import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.vllm.BatchEngine;
import io.gravitee.singularitee.inference.vllm.VllmConfig;
import io.gravitee.singularitee.workspace.ModelLoadRequest;

/**
 * Creates a vLLM-backed {@link VllmTextGenEngine} from a {@link ModelLoadRequest}.
 *
 * <p>This class and {@link VllmTextGenEngine} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-vllm} types.
 *
 * <p>Carries the server-wide distributed defaults so a workspace does not have
 * to repeat the GPU topology on every model: the deployment sets it once (see
 * {@code ai.vllm.*} in gravitee.yml or the matching {@code GRAVITEE_*} env
 * vars) and a model overrides it only when it needs something different.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VllmEngineFactory implements ModelEngineFactory {

  /**
   * Server-wide fallbacks for the vLLM GPU topology, applied when a model does
   * not set its own.
   *
   * @param tensorParallelSize   GPUs per layer shard, or 0 to leave it to vLLM
   * @param pipelineParallelSize pipeline stages, or 0 to leave it to vLLM
   * @param distributedExecutorBackend {@code "mp"} / {@code "ray"}, or null
   */
  public record DistributedDefaults(
    int tensorParallelSize,
    int pipelineParallelSize,
    String distributedExecutorBackend
  ) {
    public static final DistributedDefaults NONE = new DistributedDefaults(0, 0, null);
  }

  private final DistributedDefaults distributedDefaults;

  public VllmEngineFactory() {
    this(DistributedDefaults.NONE);
  }

  public VllmEngineFactory(DistributedDefaults distributedDefaults) {
    this.distributedDefaults = distributedDefaults != null
      ? distributedDefaults
      : DistributedDefaults.NONE;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) {
    return create(request, null);
  }

  /**
   * Creates the engine against an already-downloaded model directory.
   *
   * @param request      the load request
   * @param resolvedPath local directory holding the weights, resolved by
   *                     {@code VllmModelResolver}; null lets vLLM resolve the
   *                     repo id itself (the offline-unfriendly path)
   */
  public ModelEngine create(ModelLoadRequest request, java.nio.file.Path resolvedPath) {
    var cfg = request.vllmConfig();

    var vllmConfig = new VllmConfig(
      request.modelName(),
      resolvedPath,
      cfg.dtype().isEmpty() ? "auto" : cfg.dtype(),
      cfg.maxModelLen(),
      cfg.maxNumSeqs() > 0 ? cfg.maxNumSeqs() : 1,
      cfg.gpuMemoryUtilization() > 0 ? cfg.gpuMemoryUtilization() : 0.5,
      cfg.maxNumBatchedTokens(),
      cfg.enforceEager(),
      cfg.trustRemoteCode(),
      cfg.quantization().isEmpty() ? null : cfg.quantization(),
      0.0,
      cfg.seed() > 0 ? cfg.seed() : null,
      cfg.enablePrefixCaching(),
      cfg.enableChunkedPrefill(),
      cfg.kvCacheDtype().isEmpty() ? null : cfg.kvCacheDtype(),
      cfg.enableLora(),
      cfg.maxLoras(),
      cfg.maxLoraRank(),
      null,
      toMemoryCheckPolicy(request.memoryCheckPolicy()),
      0L,
      0,
      0,
      0,
      0,
      false,
      0,
      null,
      cfg.enableSleepMode(),
      // Per-model wins; otherwise the deployment-wide default.
      cfg.tensorParallelSize() > 0
        ? cfg.tensorParallelSize()
        : distributedDefaults.tensorParallelSize(),
      cfg.pipelineParallelSize() > 0
        ? cfg.pipelineParallelSize()
        : distributedDefaults.pipelineParallelSize(),
      cfg.distributedExecutorBackend().isEmpty()
        ? distributedDefaults.distributedExecutorBackend()
        : cfg.distributedExecutorBackend()
    );

    return new VllmTextGenEngine(
      new BatchEngine(vllmConfig),
      CheckpointModalities.read(resolvedPath, request.modelName())
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
}
