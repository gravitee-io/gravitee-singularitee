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
 * Categorises a {@link ModelEngine} by its inference modality.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public enum ModelEngineType {
  /** Streaming text / token generation (llama.cpp or vLLM backend). */
  TEXT_GEN,

  /** Synchronous sequence or token classification (ONNX BERT backend). */
  CLASSIFIER,

  /** Synchronous dense-vector embedding (ONNX BERT backend). */
  EMBEDDING,

  /** Synchronous cross-encoder reranking (ONNX BERT backend). */
  RERANKER,
}
