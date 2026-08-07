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
package io.gravitee.singularitee.workspace;

import io.gravitee.singularitee.workspace.config.*;
import java.util.Locale;

/**
 * Enumeration of supported model types in workspace definitions.
 * Maps YAML model type strings to {@link ModelLoadRequest} construction.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum ModelType {
  LLAMA_CPP("llama_cpp") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      LlamaCppConfig config = modelDef.llamaCpp() != null
        ? toLlamaCpp(modelDef.llamaCpp())
        : LlamaCppConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        config,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      );
    }

    private LlamaCppConfig toLlamaCpp(WorkspaceDefinition.LlamaCppDef d) {
      var b = LlamaCppConfig.newBuilder();
      if (d.nCtx() > 0) b.setNCtx(d.nCtx());
      if (d.nBatch() > 0) b.setNBatch(d.nBatch());
      if (d.nUbatch() > 0) b.setNUbatch(d.nUbatch());
      if (d.nSeqMax() > 0) b.setNSeqMax(d.nSeqMax());
      if (d.nGpuLayers() > 0) b.setNGpuLayers(d.nGpuLayers());
      if (d.poolingType() != null && !d.poolingType().isBlank()) b.setPoolingType(d.poolingType());
      if (d.attentionType() != null && !d.attentionType().isBlank()) b.setAttentionType(
        d.attentionType()
      );
      if (d.flashAttnType() != null && !d.flashAttnType().isBlank()) b.setFlashAttnType(
        d.flashAttnType()
      );
      if (d.offloadKqv() != null) b.setOffloadKqv(d.offloadKqv());
      if (d.loraPath() != null && !d.loraPath().isBlank()) b.setLoraPath(d.loraPath());
      if (d.mmprojPath() != null && !d.mmprojPath().isBlank()) b.setMmprojPath(d.mmprojPath());
      if (d.mediaMarker() != null && !d.mediaMarker().isBlank()) b.setMediaMarker(d.mediaMarker());
      b.setMtp(d.mtp());
      if (d.useMlock() != null) b.setUseMlock(d.useMlock());
      if (d.cacheTypeK() != null && !d.cacheTypeK().isBlank()) b.setCacheTypeK(d.cacheTypeK());
      if (d.cacheTypeV() != null && !d.cacheTypeV().isBlank()) b.setCacheTypeV(d.cacheTypeV());
      if (d.promptCache() != null) b.setPromptCache(d.promptCache());
      if (d.promptCacheMinTokens() > 0) b.setPromptCacheMinTokens(d.promptCacheMinTokens());
      if (d.eogRampStart() != null) b.setEogRampStart(d.eogRampStart());
      if (d.eogRampMaxBias() != null) b.setEogRampMaxBias(d.eogRampMaxBias());
      if (d.draftModel() != null && !d.draftModel().isBlank()) b.setDraftModel(d.draftModel());
      if (d.draftPath() != null && !d.draftPath().isBlank()) b.setDraftPath(d.draftPath());
      if (d.eagle3Model() != null && !d.eagle3Model().isBlank()) b.setEagle3Model(d.eagle3Model());
      if (d.eagle3Path() != null && !d.eagle3Path().isBlank()) b.setEagle3Path(d.eagle3Path());
      if (d.speculative() != null) {
        var s = d.speculative();
        var sb = SpeculativeConfig.newBuilder();
        if (s.nDraft() > 0) sb.setNDraft(s.nDraft());
        if (s.draftMin() > 0) sb.setDraftMin(s.draftMin());
        if (s.pMin() > 0) sb.setPMin(s.pMin());
        if (s.temperature() != null) sb.setTemperature(s.temperature());
        if (s.topK() > 0) sb.setTopK(s.topK());
        if (s.topP() != null) sb.setTopP(s.topP());
        if (s.seed() != null) sb.setSeed(s.seed());
        b.setSpeculative(sb);
      }
      return b.build();
    }
  },

  VLLM("vllm") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      VllmConfig config = modelDef.vllm() != null
        ? toVllm(modelDef.vllm())
        : VllmConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        config,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      );
    }

    private VllmConfig toVllm(WorkspaceDefinition.VllmDef d) {
      var b = VllmConfig.newBuilder();
      if (d.dtype() != null && !d.dtype().isBlank()) b.setDtype(d.dtype());
      if (d.maxModelLen() > 0) b.setMaxModelLen(d.maxModelLen());
      if (d.maxNumSeqs() > 0) b.setMaxNumSeqs(d.maxNumSeqs());
      if (d.gpuMemoryUtilization() > 0) b.setGpuMemoryUtilization(d.gpuMemoryUtilization());
      if (d.maxNumBatchedTokens() > 0) b.setMaxNumBatchedTokens(d.maxNumBatchedTokens());
      b.setEnforceEager(d.enforceEager());
      b.setTrustRemoteCode(d.trustRemoteCode());
      if (d.quantization() != null && !d.quantization().isBlank()) b.setQuantization(
        d.quantization()
      );
      if (d.seed() > 0) b.setSeed(d.seed());
      // `prompt_cache` is a unified alias for `enable_prefix_caching` — either enables it.
      // Left unset when the workspace mentions neither, so the engine keeps its own
      // default; an explicit false now reaches the engine as a real disable.
      if (d.enablePrefixCaching() != null || d.promptCache() != null) {
        b.setEnablePrefixCaching(
          Boolean.TRUE.equals(d.enablePrefixCaching()) || Boolean.TRUE.equals(d.promptCache())
        );
      }
      b.setEnableChunkedPrefill(d.enableChunkedPrefill());
      if (d.kvCacheDtype() != null && !d.kvCacheDtype().isBlank()) b.setKvCacheDtype(
        d.kvCacheDtype()
      );
      b.setEnableLora(d.enableLora());
      if (d.maxLoras() > 0) b.setMaxLoras(d.maxLoras());
      if (d.maxLoraRank() > 0) b.setMaxLoraRank(d.maxLoraRank());
      if (d.enableSleepMode() != null) b.setEnableSleepMode(d.enableSleepMode());
      if (d.tensorParallelSize() > 0) b.setTensorParallelSize(d.tensorParallelSize());
      if (d.pipelineParallelSize() > 0) b.setPipelineParallelSize(d.pipelineParallelSize());
      if (
        d.distributedExecutorBackend() != null && !d.distributedExecutorBackend().isBlank()
      ) b.setDistributedExecutorBackend(d.distributedExecutorBackend());
      return b.build();
    }
  },

  ONNX_CLASSIFIER("onnx_classifier") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      OnnxClassifierConfig config = modelDef.onnxClassifier() != null
        ? toOnnxClassifier(modelDef.onnxClassifier())
        : OnnxClassifierConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        config,
        null,
        null,
        null,
        null,
        null,
        null
      );
    }

    private OnnxClassifierConfig toOnnxClassifier(WorkspaceDefinition.OnnxClassifierDef d) {
      var b = OnnxClassifierConfig.newBuilder();
      if (d.modelPath() != null) b.setModelPath(d.modelPath());
      if (d.tokenizerPath() != null) b.setTokenizerPath(d.tokenizerPath());
      if (d.configJsonPath() != null && !d.configJsonPath().isBlank()) b.setConfigJsonPath(
        d.configJsonPath()
      );
      if (d.labels() != null) b.addAllLabels(d.labels());
      if (d.maxSequenceLength() > 0) b.setMaxSequenceLength(d.maxSequenceLength());
      if (d.classifierMode() != null && !d.classifierMode().isBlank()) b.setClassifierMode(
        d.classifierMode()
      );
      return b.build();
    }
  },

  ONNX_EMBEDDING("onnx_embedding") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      OnnxEmbeddingConfig config = modelDef.onnxEmbedding() != null
        ? toOnnxEmbedding(modelDef.onnxEmbedding())
        : OnnxEmbeddingConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        config,
        null,
        null,
        null,
        null,
        null
      );
    }

    private OnnxEmbeddingConfig toOnnxEmbedding(WorkspaceDefinition.OnnxEmbeddingDef d) {
      var b = OnnxEmbeddingConfig.newBuilder();
      if (d.modelPath() != null) b.setModelPath(d.modelPath());
      if (d.tokenizerPath() != null) b.setTokenizerPath(d.tokenizerPath());
      if (d.configJsonPath() != null && !d.configJsonPath().isBlank()) b.setConfigJsonPath(
        d.configJsonPath()
      );
      if (d.maxSequenceLength() > 0) b.setMaxSequenceLength(d.maxSequenceLength());
      if (d.poolingMode() != null && !d.poolingMode().isBlank()) b.setPoolingMode(d.poolingMode());
      b.setNormalize(d.normalize());
      return b.build();
    }
  },

  ONNX_RERANKER("onnx_reranker") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      OnnxRerankerConfig config = modelDef.onnxReranker() != null
        ? toOnnxReranker(modelDef.onnxReranker())
        : OnnxRerankerConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        null,
        null,
        null,
        config,
        null,
        null
      );
    }

    private OnnxRerankerConfig toOnnxReranker(WorkspaceDefinition.OnnxRerankerDef d) {
      var b = OnnxRerankerConfig.newBuilder();
      if (d.modelPath() != null) b.setModelPath(d.modelPath());
      if (d.tokenizerPath() != null) b.setTokenizerPath(d.tokenizerPath());
      if (d.configJsonPath() != null && !d.configJsonPath().isBlank()) b.setConfigJsonPath(
        d.configJsonPath()
      );
      if (d.maxSequenceLength() > 0) b.setMaxSequenceLength(d.maxSequenceLength());
      if (d.scoring() != null && !d.scoring().isBlank()) b.setScoring(d.scoring());
      return b.build();
    }
  },

  GLINER_CLASSIFIER("gliner_classifier") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      GlinerClassifierConfig config = modelDef.glinerClassifier() != null
        ? toGlinerClassifier(modelDef.glinerClassifier())
        : GlinerClassifierConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        null,
        config,
        null,
        null,
        null,
        null
      );
    }

    private GlinerClassifierConfig toGlinerClassifier(WorkspaceDefinition.GlinerClassifierDef d) {
      var b = GlinerClassifierConfig.newBuilder();
      if (d.modelDir() != null && !d.modelDir().isBlank()) b.setModelDir(d.modelDir());
      if (d.labels() != null) {
        for (var label : d.labels()) {
          var lb = GlinerLabelDef.newBuilder().setName(label.name());
          if (label.description() != null && !label.description().isBlank()) lb.setDescription(
            label.description()
          );
          b.addLabels(lb.build());
        }
      }
      if (d.threshold() > 0) b.setThreshold(d.threshold());
      if (d.variant() != null && !d.variant().isBlank()) b.setVariant(d.variant());
      if (d.tokenCap() > 0) b.setTokenCap(d.tokenCap());
      return b.build();
    }
  },

  GLINER_NER("gliner_ner") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      GlinerNerConfig config = modelDef.glinerNer() != null
        ? toGlinerNer(modelDef.glinerNer())
        : GlinerNerConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        null,
        null,
        config,
        null,
        null,
        null
      );
    }

    private GlinerNerConfig toGlinerNer(WorkspaceDefinition.GlinerNerDef d) {
      var b = GlinerNerConfig.newBuilder();
      if (d.modelDir() != null && !d.modelDir().isBlank()) b.setModelDir(d.modelDir());
      if (d.entities() != null) {
        for (var e : d.entities()) {
          var eb = GlinerEntityDef.newBuilder().setName(e.name());
          if (e.description() != null && !e.description().isBlank()) eb.setDescription(
            e.description()
          );
          b.addEntities(eb.build());
        }
      }
      if (d.threshold() > 0) b.setThreshold(d.threshold());
      if (d.variant() != null && !d.variant().isBlank()) b.setVariant(d.variant());
      if (d.tokenCap() > 0) b.setTokenCap(d.tokenCap());
      return b.build();
    }
  },

  // ── Remote model types ────────────────────────────────────────────────────

  REMOTE_LLM("remote_llm") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    } // remote — no engine config needed
  },

  REMOTE_CLASSIFIER("remote_classifier") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    }
  },

  REMOTE_EMBEDDING("remote_embedding") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    }
  },

  REMOTE_RERANKER("remote_reranker") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    }
  },

  // ── Client-local model types ──────────────────────────────────────────────

  REGEX("regex") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    }
  },

  COMPOSITE_CLASSIFIER("composite_classifier") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      return null;
    }
  },

  // ── llama.cpp encoder model types ─────────────────────────────────────────

  LLAMA_CPP_EMBEDDING("llama_cpp_embedding") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      LlamaCppEmbeddingConfig config = modelDef.llamaCppEmbedding() != null
        ? toLlamaCppEmbedding(modelDef.llamaCppEmbedding())
        : LlamaCppEmbeddingConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        config,
        null
      );
    }

    private LlamaCppEmbeddingConfig toLlamaCppEmbedding(
      WorkspaceDefinition.LlamaCppEmbeddingDef d
    ) {
      var b = LlamaCppEmbeddingConfig.newBuilder();
      if (d.llamaCpp() != null) {
        b.setLlamaCppConfig(toLlamaCpp(d.llamaCpp()));
      }
      if (d.embeddingTemplate() != null && !d.embeddingTemplate().isBlank()) {
        b.setEmbeddingTemplate(d.embeddingTemplate());
      }
      return b.build();
    }

    private LlamaCppConfig toLlamaCpp(WorkspaceDefinition.LlamaCppDef d) {
      var b = LlamaCppConfig.newBuilder();
      if (d.nCtx() > 0) b.setNCtx(d.nCtx());
      if (d.nBatch() > 0) b.setNBatch(d.nBatch());
      if (d.nUbatch() > 0) b.setNUbatch(d.nUbatch());
      if (d.nSeqMax() > 0) b.setNSeqMax(d.nSeqMax());
      if (d.nGpuLayers() > 0) b.setNGpuLayers(d.nGpuLayers());
      if (d.poolingType() != null && !d.poolingType().isBlank()) b.setPoolingType(d.poolingType());
      if (d.attentionType() != null && !d.attentionType().isBlank()) b.setAttentionType(
        d.attentionType()
      );
      if (d.flashAttnType() != null && !d.flashAttnType().isBlank()) b.setFlashAttnType(
        d.flashAttnType()
      );
      if (d.offloadKqv() != null) b.setOffloadKqv(d.offloadKqv());
      if (d.loraPath() != null && !d.loraPath().isBlank()) b.setLoraPath(d.loraPath());
      if (d.mmprojPath() != null && !d.mmprojPath().isBlank()) b.setMmprojPath(d.mmprojPath());
      if (d.mediaMarker() != null && !d.mediaMarker().isBlank()) b.setMediaMarker(d.mediaMarker());
      return b.build();
    }
  },

  LLAMA_CPP_RERANKER("llama_cpp_reranker") {
    @Override
    ModelLoadRequest toModelLoadRequest(
      String modelId,
      String modelName,
      String modelPath,
      MemoryCheckPolicyType memoryCheckPolicy,
      WorkspaceDefinition.ModelDefinition modelDef
    ) {
      LlamaCppRerankerConfig config = modelDef.llamaCppReranker() != null
        ? toLlamaCppReranker(modelDef.llamaCppReranker())
        : LlamaCppRerankerConfig.getDefaultInstance();
      return new ModelLoadRequest(
        modelId,
        modelName,
        modelPath,
        memoryCheckPolicy,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        config
      );
    }

    private LlamaCppRerankerConfig toLlamaCppReranker(WorkspaceDefinition.LlamaCppRerankerDef d) {
      var b = LlamaCppRerankerConfig.newBuilder();
      if (d.llamaCpp() != null) {
        b.setLlamaCppConfig(toLlamaCpp(d.llamaCpp()));
      }
      if (d.scoring() != null && !d.scoring().isBlank()) b.setScoring(d.scoring());
      if (d.rerankTemplate() != null && !d.rerankTemplate().isBlank()) b.setRerankTemplate(
        d.rerankTemplate()
      );
      return b.build();
    }

    private LlamaCppConfig toLlamaCpp(WorkspaceDefinition.LlamaCppDef d) {
      var b = LlamaCppConfig.newBuilder();
      if (d.nCtx() > 0) b.setNCtx(d.nCtx());
      if (d.nBatch() > 0) b.setNBatch(d.nBatch());
      if (d.nUbatch() > 0) b.setNUbatch(d.nUbatch());
      if (d.nSeqMax() > 0) b.setNSeqMax(d.nSeqMax());
      if (d.nGpuLayers() > 0) b.setNGpuLayers(d.nGpuLayers());
      if (d.poolingType() != null && !d.poolingType().isBlank()) b.setPoolingType(d.poolingType());
      if (d.attentionType() != null && !d.attentionType().isBlank()) b.setAttentionType(
        d.attentionType()
      );
      if (d.flashAttnType() != null && !d.flashAttnType().isBlank()) b.setFlashAttnType(
        d.flashAttnType()
      );
      if (d.offloadKqv() != null) b.setOffloadKqv(d.offloadKqv());
      if (d.loraPath() != null && !d.loraPath().isBlank()) b.setLoraPath(d.loraPath());
      if (d.mmprojPath() != null && !d.mmprojPath().isBlank()) b.setMmprojPath(d.mmprojPath());
      if (d.mediaMarker() != null && !d.mediaMarker().isBlank()) b.setMediaMarker(d.mediaMarker());
      return b.build();
    }
  };

  private final String yamlKey;

  ModelType(String yamlKey) {
    this.yamlKey = yamlKey;
  }

  /**
   * Returns {@code true} if this model type represents a remote model.
   */
  public boolean isRemote() {
    return (
      this == REMOTE_LLM ||
      this == REMOTE_CLASSIFIER ||
      this == REMOTE_EMBEDDING ||
      this == REMOTE_RERANKER
    );
  }

  /**
   * Returns {@code true} if this model type represents a client-local model
   * — a pure-Java engine that runs in-process on either server or client with
   * no native library, no GPU, and no gRPC call.
   */
  public boolean isClientLocal() {
    return (this == REGEX || this == COMPOSITE_CLASSIFIER);
  }

  /**
   * Builds a {@link ModelLoadRequest} for the given model definition.
   * Returns {@code null} for remote and client-local types (they don't use this path).
   */
  abstract ModelLoadRequest toModelLoadRequest(
    String modelId,
    String modelName,
    String modelPath,
    MemoryCheckPolicyType memoryCheckPolicy,
    WorkspaceDefinition.ModelDefinition modelDef
  );

  /**
   * Parses a YAML model type string to its corresponding enum value.
   *
   * @param typeStr the model type string from YAML (case-insensitive)
   * @return the matching ModelType enum value
   * @throws IllegalArgumentException if the type is not recognized
   */
  public static ModelType parse(String typeStr) {
    if (typeStr == null || typeStr.isBlank()) {
      throw new IllegalArgumentException("Model type cannot be null or blank");
    }
    String normalized = typeStr.toLowerCase(Locale.ENGLISH);
    for (ModelType type : ModelType.values()) {
      if (type.yamlKey.equals(normalized)) {
        return type;
      }
    }
    throw new IllegalArgumentException(
      "Unknown model type '" +
        typeStr +
        "'. Supported types: " +
        String.join(
          ", ",
          new String[] {
            "llama_cpp",
            "vllm",
            "onnx_classifier",
            "onnx_embedding",
            "onnx_reranker",
            "gliner_classifier",
            "gliner_ner",
            "remote_llm",
            "remote_classifier",
            "remote_embedding",
            "remote_reranker",
            "regex",
            "composite_classifier",
            "llama_cpp_embedding",
            "llama_cpp_reranker",
          }
        )
    );
  }
}
