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
package io.gravitee.singularitee.engine;

/**
 * Top-level abstraction for all model engines managed by Singularitee.
 *
 * <p>This sealed interface is the <strong>only</strong> type that layers above the
 * {@code adapter} package (registry, pipeline, service) are allowed to reference.
 * No {@code gravitee-inference-*} types ever cross this boundary.
 *
 * <p>Permitted subtypes cover the four engine categories:
 * <ul>
 *   <li>{@link TextGenEngine} — streaming token generation (llama.cpp, vLLM)</li>
 *   <li>{@link ClassifierEngine} — synchronous label + score classification (ONNX BERT)</li>
 *   <li>{@link EmbeddingEngine} — synchronous dense vector embedding (ONNX BERT)</li>
 *   <li>{@link RerankerEngine} — synchronous cross-encoder reranking (ONNX BERT)</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface ModelEngine
  extends AutoCloseable
  permits TextGenEngine, ClassifierEngine, EmbeddingEngine, RerankerEngine {
  /**
   * Returns the type category of this engine.
   *
   * @return the {@link ModelEngineType} of this engine
   */
  ModelEngineType type();

  /**
   * Returns the pipeline task slug for this engine (e.g.
   * {@code "text-generation"}, {@code "text-classification"},
   * {@code "token-classification"}, {@code "feature-extraction"}).
   *
   * <p>Remote callers use this to route requests to the appropriate
   * API endpoint without having to introspect engine-specific modes.
   *
   * <p>See {@link ModelTasks} for the known values.
   *
   * @return the task slug; never {@code null}
   */
  String task();

  /**
   * Releases all resources held by this engine (GPU memory, native handles, threads).
   * After this call the engine must not be used.
   */
  @Override
  void close() throws Exception;
}
