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
 * Pipeline task slugs exposed by Singularitee in
 * {@code GetModelResponse.task}.
 *
 * <p>Remote callers (gateway connectors, SDKs) use this single, familiar string
 * to decide which endpoint a model belongs on.
 *
 * <p>Mapping to internal engine types:
 * <ul>
 *   <li>{@link TextGenEngine}                                  → {@link #TEXT_GENERATION}</li>
 *   <li>{@link ClassifierEngine} in {@code SEQUENCE} mode      → {@link #TEXT_CLASSIFICATION}</li>
 *   <li>{@link ClassifierEngine} in {@code TOKEN}    mode      → {@link #TOKEN_CLASSIFICATION}</li>
 *   <li>{@link EmbeddingEngine}                                → {@link #FEATURE_EXTRACTION}</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ModelTasks {

  /** Causal / seq2seq text generation. Maps to /chat/completions, /completions, /responses. */
  public static final String TEXT_GENERATION = "text-generation";

  /** Sequence-level classification (sentiment, toxicity, topic). Maps to /classify without spans. */
  public static final String TEXT_CLASSIFICATION = "text-classification";

  /** Token-level classification / NER / PII. Maps to /classify with character spans. */
  public static final String TOKEN_CLASSIFICATION = "token-classification";

  /** Dense vector embeddings. Maps to /embeddings. */
  public static final String FEATURE_EXTRACTION = "feature-extraction";

  /** Cross-encoder reranking. Maps to /rerank. */
  public static final String RERANKING = "reranking";

  private ModelTasks() {}
}
