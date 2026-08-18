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

import io.gravitee.singularitee.workspace.config.GlinerClassifierConfig;
import io.gravitee.singularitee.workspace.config.GlinerNerConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppEmbeddingConfig;
import io.gravitee.singularitee.workspace.config.LlamaCppRerankerConfig;
import io.gravitee.singularitee.workspace.config.OnnxClassifierConfig;
import io.gravitee.singularitee.workspace.config.OnnxEmbeddingConfig;
import io.gravitee.singularitee.workspace.config.OnnxRerankerConfig;
import io.gravitee.singularitee.workspace.config.VllmConfig;
import java.util.List;

/**
 * Internal DTO carrying the information needed to construct a local {@link io.gravitee.singularitee.engine.ModelEngine}.
 *
 * <p>Replaces {@code PublishModelRequest} (the removed gRPC wire message) as the
 * handoff type between {@link YamlWorkspaceLoader} and the engine factory layer.
 * Exactly one of the engine-config fields is non-{@code null}; all others are
 * {@code null}. The non-null field determines which {@link ModelEngineFactoryType}
 * is selected.
 *
 * <p>Engine configs ({@link io.gravitee.singularitee.workspace.config.LlamaCppConfig},
 * {@link io.gravitee.singularitee.workspace.config.VllmConfig}, etc.) are plain
 * records — they carry only configuration and never touch an RPC, so they no
 * longer live in proto at all.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ModelLoadRequest(
  /** Optional stable caller-supplied model ID; empty string means auto-assign. */
  String modelId,
  /** Human-readable model name (HuggingFace repo ID or display label). */
  String modelName,
  /**
   * Local filesystem path or HuggingFace GGUF filename for llama.cpp models.
   * Empty for all other engine types.
   */
  String modelPath,
  /** Memory-check behaviour at load time. */
  MemoryCheckPolicyType memoryCheckPolicy,
  // ── Engine configs — exactly one is non-null ──────────────────────────
  LlamaCppConfig llamaCppConfig,
  VllmConfig vllmConfig,
  OnnxClassifierConfig onnxClassifier,
  OnnxEmbeddingConfig onnxEmbedding,
  GlinerClassifierConfig glinerClassifier,
  GlinerNerConfig glinerNer,
  OnnxRerankerConfig onnxReranker,
  LlamaCppEmbeddingConfig llamaCppEmbedding,
  LlamaCppRerankerConfig llamaCppReranker,
  /**
   * Glob patterns for repository files to skip when downloading this model, from
   * {@code download.exclude:} in the workspace. Never {@code null} — an empty
   * list means "download whatever the engine's own selection rules picked".
   *
   * @see WorkspaceDefinition.DownloadDef
   */
  List<String> downloadExclude,
  /**
   * Task slug declared by {@code task:} in the workspace, overriding the one the
   * engine reports for itself. Empty means "ask the engine".
   */
  String task,
  /**
   * Whether the model joins the public catalogue. {@code false} keeps it out of
   * the listings and off the OpenAI HTTP surface while leaving it callable as a
   * pipeline dependency.
   */
  boolean visible
) {
  public ModelLoadRequest {
    downloadExclude = downloadExclude == null ? List.of() : List.copyOf(downloadExclude);
    task = task == null ? "" : task;
  }

  /**
   * Builds a request with no download excludes.
   *
   * <p>Every {@link ModelType} branch uses this: excludes are model-level rather
   * than engine-specific, so they are applied once by
   * {@link YamlWorkspaceLoader} via {@link #withDownloadExclude(List)} instead of
   * being threaded through each branch's wall of engine-config arguments.
   */
  public ModelLoadRequest(
    String modelId,
    String modelName,
    String modelPath,
    MemoryCheckPolicyType memoryCheckPolicy,
    LlamaCppConfig llamaCppConfig,
    VllmConfig vllmConfig,
    OnnxClassifierConfig onnxClassifier,
    OnnxEmbeddingConfig onnxEmbedding,
    GlinerClassifierConfig glinerClassifier,
    GlinerNerConfig glinerNer,
    OnnxRerankerConfig onnxReranker,
    LlamaCppEmbeddingConfig llamaCppEmbedding,
    LlamaCppRerankerConfig llamaCppReranker
  ) {
    this(
      modelId,
      modelName,
      modelPath,
      memoryCheckPolicy,
      llamaCppConfig,
      vllmConfig,
      onnxClassifier,
      onnxEmbedding,
      glinerClassifier,
      glinerNer,
      onnxReranker,
      llamaCppEmbedding,
      llamaCppReranker,
      List.of(),
      "",
      true
    );
  }

  /** Returns a copy of this request carrying {@code exclude} as its download excludes. */
  public ModelLoadRequest withDownloadExclude(List<String> exclude) {
    return new ModelLoadRequest(
      modelId,
      modelName,
      modelPath,
      memoryCheckPolicy,
      llamaCppConfig,
      vllmConfig,
      onnxClassifier,
      onnxEmbedding,
      glinerClassifier,
      glinerNer,
      onnxReranker,
      llamaCppEmbedding,
      llamaCppReranker,
      exclude,
      task,
      visible
    );
  }

  /**
   * Returns a copy of this request carrying the workspace's publication metadata —
   * the declared task override and catalogue visibility.
   *
   * <p>Applied by {@link YamlWorkspaceLoader} for the same reason as
   * {@link #withDownloadExclude(List)}: both are model-level and mean the same
   * thing for every engine, so neither is threaded through the {@link ModelType}
   * branches.
   */
  public ModelLoadRequest withPublication(String task, boolean visible) {
    return new ModelLoadRequest(
      modelId,
      modelName,
      modelPath,
      memoryCheckPolicy,
      llamaCppConfig,
      vllmConfig,
      onnxClassifier,
      onnxEmbedding,
      glinerClassifier,
      glinerNer,
      onnxReranker,
      llamaCppEmbedding,
      llamaCppReranker,
      downloadExclude,
      task,
      visible
    );
  }

  /** Returns {@code true} if this request carries a llama.cpp config. */
  public boolean hasLlamaCppConfig() {
    return llamaCppConfig != null;
  }

  /** Returns {@code true} if this request carries a vLLM config. */
  public boolean hasVllmConfig() {
    return vllmConfig != null;
  }

  /** Returns {@code true} if this request carries an ONNX classifier config. */
  public boolean hasOnnxClassifier() {
    return onnxClassifier != null;
  }

  /** Returns {@code true} if this request carries an ONNX embedding config. */
  public boolean hasOnnxEmbedding() {
    return onnxEmbedding != null;
  }

  /** Returns {@code true} if this request carries a GLiNER classifier config. */
  public boolean hasGlinerClassifier() {
    return glinerClassifier != null;
  }

  /** Returns {@code true} if this request carries a GLiNER NER config. */
  public boolean hasGlinerNer() {
    return glinerNer != null;
  }

  /** Returns {@code true} if this request carries an ONNX reranker (cross-encoder) config. */
  public boolean hasOnnxReranker() {
    return onnxReranker != null;
  }

  /** Returns {@code true} if this request carries a llama.cpp embedding config. */
  public boolean hasLlamaCppEmbedding() {
    return llamaCppEmbedding != null;
  }

  /** Returns {@code true} if this request carries a llama.cpp reranker config. */
  public boolean hasLlamaCppReranker() {
    return llamaCppReranker != null;
  }
}
