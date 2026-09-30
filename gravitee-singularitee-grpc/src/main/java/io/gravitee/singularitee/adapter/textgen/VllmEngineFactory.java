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
import io.gravitee.singularitee.engine.api.ModelEngine;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.vllm.BatchEngine;
import io.gravitee.singularitee.inference.vllm.VllmConfig;
import io.gravitee.singularitee.workspace.MemoryCheckPolicyType;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

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
    /** No server-wide defaults: every topology value is left to vLLM. */
    public static final DistributedDefaults NONE = new DistributedDefaults(0, 0, null);
  }

  private final DistributedDefaults distributedDefaults;

  /** Creates a factory with no server-wide distributed defaults. */
  public VllmEngineFactory() {
    this(DistributedDefaults.NONE);
  }

  /** Creates a factory with the given server-wide distributed defaults; {@code null} means none. */
  public VllmEngineFactory(DistributedDefaults distributedDefaults) {
    this.distributedDefaults = distributedDefaults != null
      ? distributedDefaults
      : DistributedDefaults.NONE;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) {
    return create(request, null, null);
  }

  /**
   * Creates the engine against an already-downloaded model directory, with the model's default LoRA
   * adapter ({@code vllm.lora_path}) already on disk at {@code loraDir}. The adapter is checked before
   * vLLM starts: one that cannot be served would otherwise answer every request as the base model, with
   * nothing in the output to show it.
   *
   * @param request      the load request
   * @param resolvedPath local directory holding the weights, resolved by
   *                     {@code VllmModelResolver}; null lets vLLM resolve the
   *                     repo id itself (the offline-unfriendly path)
   * @param loraDir      the adapter directory, or null when the model has no default adapter
   */
  public ModelEngine create(ModelLoadRequest request, Path resolvedPath, Path loraDir) {
    var cfg = request.vllmConfig();
    var defaultLora = defaultLora(request.modelName(), cfg, loraDir);

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
      CheckpointModalities.read(resolvedPath, request.modelName()),
      defaultLora
    );
  }

  /** vLLM's own {@code max_lora_rank} when the workspace leaves it unset. */
  static final int VLLM_DEFAULT_MAX_LORA_RANK = 16;

  /** The only {@code max_lora_rank} values vLLM accepts; any other fails its engine configuration. */
  static final Set<Integer> VLLM_MAX_LORA_RANKS = Set.of(1, 8, 16, 32, 64, 128, 256, 320, 512);

  /**
   * Where {@code lora_path} points once its repository is on disk: an absolute path is taken as is, a relative
   * one is resolved inside {@code repoDir} (the {@code lora_repo} download, or the model's own) and may not
   * climb out of it.
   */
  public static Path loraDirectory(
    io.gravitee.singularitee.workspace.config.VllmConfig cfg,
    Path repoDir
  ) {
    Path path = Path.of(cfg.loraPath());
    if (path.isAbsolute()) {
      return path;
    }
    Path root = repoDir.normalize();
    Path dir = root.resolve(path).normalize();
    if (!dir.startsWith(root)) {
      throw new IllegalArgumentException(
        "vllm.lora_path '" +
          cfg.loraPath() +
          "' leaves its repository; use an absolute path instead"
      );
    }
    return dir;
  }

  /**
   * The default adapter to apply, or null when the model has none. Refuses, before vLLM starts, an adapter
   * that would not be served: LoRA not enabled, a {@code max_lora_rank} vLLM does not accept, no PEFT
   * {@code adapter_config.json}, no rank in it, or a rank above the engine's {@code max_lora_rank}.
   */
  static VllmTextGenEngine.DefaultLora defaultLora(
    String modelName,
    io.gravitee.singularitee.workspace.config.VllmConfig cfg,
    Path loraDir
  ) {
    if (!cfg.hasDefaultLora()) {
      return null;
    }
    if (!cfg.enableLora()) {
      throw new IllegalArgumentException(
        "Model '" +
          modelName +
          "': vllm.lora_path is set but vllm.enable_lora is false; the adapter would " +
          "never be applied"
      );
    }
    if (cfg.maxLoraRank() > 0 && !VLLM_MAX_LORA_RANKS.contains(cfg.maxLoraRank())) {
      throw new IllegalArgumentException(
        "Model '" +
          modelName +
          "': vllm.max_lora_rank " +
          cfg.maxLoraRank() +
          " is not one vLLM accepts " +
          new TreeSet<>(VLLM_MAX_LORA_RANKS)
      );
    }
    if (loraDir == null) {
      throw new IllegalArgumentException(
        "Model '" +
          modelName +
          "': vllm.lora_path '" +
          cfg.loraPath() +
          "' was not resolved to a directory"
      );
    }
    Path adapterConfig = loraDir.resolve("adapter_config.json");
    if (!Files.isRegularFile(adapterConfig)) {
      throw new IllegalArgumentException(
        "Model '" +
          modelName +
          "': no PEFT adapter at " +
          loraDir +
          " (adapter_config.json missing); " +
          "vLLM serves LoRA in PEFT format, not GGUF"
      );
    }
    int rank;
    try {
      rank = adapterRank(new JsonObject(Files.readString(adapterConfig)));
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException(
        "Model '" + modelName + "': cannot read " + adapterConfig + ": " + e.getMessage(),
        e
      );
    }
    if (rank <= 0) {
      throw new IllegalArgumentException(
        "Model '" + modelName + "': " + adapterConfig + " declares no positive rank 'r'"
      );
    }
    int maxRank = cfg.maxLoraRank() > 0 ? cfg.maxLoraRank() : VLLM_DEFAULT_MAX_LORA_RANK;
    if (rank > maxRank) {
      throw new IllegalArgumentException(
        "Model '" +
          modelName +
          "': the adapter at " +
          loraDir +
          " has rank " +
          rank +
          ", above vllm.max_lora_rank " +
          maxRank +
          "; raise max_lora_rank"
      );
    }
    String name = cfg.loraName().isEmpty() ? "default" : cfg.loraName();
    return new VllmTextGenEngine.DefaultLora(name, loraDir.toAbsolutePath().toString());
  }

  /**
   * The largest rank the adapter uses: {@code r}, or a higher per-module override in PEFT's
   * {@code rank_pattern}. Zero when {@code r} is absent.
   */
  private static int adapterRank(JsonObject adapterConfig) {
    int rank = adapterConfig.getInteger("r", 0);
    JsonObject pattern = adapterConfig.getJsonObject("rank_pattern");
    if (rank <= 0 || pattern == null) {
      return rank;
    }
    for (String module : pattern.fieldNames()) {
      rank = Math.max(rank, pattern.getInteger(module, 0));
    }
    return rank;
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
