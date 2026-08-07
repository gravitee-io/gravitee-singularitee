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

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * A non-blocking text-classification engine (ONNX BERT backend).
 *
 * <p>Classification is fully reactive: the caller submits a {@link ClassifyRequest} and
 * receives a {@link Single} that emits a {@link ClassifyResponse} containing labels and
 * scores when the model has finished evaluating. Implementations are responsible for
 * offloading CPU-bound ONNX work (e.g. via {@code vertx.rxExecuteBlocking}) so that
 * the Vert.x event loop is never blocked.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public non-sealed interface ClassifierEngine extends ModelEngine {
  /**
   * A label/entity definition that can be passed at request time to override
   * the model's default labels (GLiNER zero-shot models only).
   *
   * @param name        the label or entity type name
   * @param description an optional human-readable description used by the model
   */
  record ClassifyLabel(String name, String description) {}

  @Override
  default ModelEngineType type() {
    return ModelEngineType.CLASSIFIER;
  }

  /**
   * Returns the task slug for this classifier: either
   * {@link ModelTasks#TEXT_CLASSIFICATION} (sequence-level, no spans) or
   * {@link ModelTasks#TOKEN_CLASSIFICATION} (token-level, with character spans).
   *
   * <p>Defaults to {@link ModelTasks#TEXT_CLASSIFICATION}; token-level
   * classifiers (e.g. GLiNER NER, ONNX TOKEN mode) must override to return
   * {@link ModelTasks#TOKEN_CLASSIFICATION}.
   */
  @Override
  default String task() {
    return ModelTasks.TEXT_CLASSIFICATION;
  }

  /**
   * Classifies a single input text.
   *
   * @param request the classification input
   * @return a {@link Single} that emits label(s) and score(s) for the input
   */
  Single<ClassifyResponse> rxClassify(ClassifyRequest request);

  /**
   * Classifies a single input text with per-request label overrides.
   *
   * <p>When {@code labels} is non-empty, implementations that support dynamic
   * labels (e.g. GLiNER) will use them instead of the model's default labels.
   * The default implementation ignores the labels and delegates to
   * {@link #rxClassify(ClassifyRequest)}.
   *
   * @param request the classification input
   * @param labels  optional label overrides; empty list means use model defaults
   * @return a {@link Single} that emits label(s) and score(s) for the input
   */
  default Single<ClassifyResponse> rxClassify(ClassifyRequest request, List<ClassifyLabel> labels) {
    return rxClassify(request);
  }

  /**
   * Classifies a single input that the caller has <em>already</em> chunked to fit
   * a character budget — so implementations that do their own character-budget
   * splitting must skip it here and process the text as one unit. This lets a
   * {@link io.gravitee.singularitee.engine.classifier.CompositeClassifierEngine
   * composite} split a huge input once for the whole model and disable the
   * redundant per-delegate re-split.
   *
   * <p>Splitting that a model <em>hard-requires</em> (e.g. an ONNX token window)
   * still applies — this only disables voluntary character-budget chunking.
   *
   * <p>The default implementation simply delegates to {@link #rxClassify}: engines
   * that never split are already correct.
   *
   * @param request the classification input, already within the caller's budget
   * @return a {@link Single} that emits label(s) and score(s) for the input
   */
  default Single<ClassifyResponse> rxClassifyPresplit(ClassifyRequest request) {
    return rxClassify(request);
  }

  /**
   * Classifies a batch of input texts. Each text is classified independently.
   * Default implementation calls {@link #rxClassify} for each input sequentially.
   *
   * @param requests the list of classification inputs
   * @return a {@link Single} emitting a list of responses in the same order as inputs
   */
  default Single<List<ClassifyResponse>> rxClassifyBatch(List<ClassifyRequest> requests) {
    return Flowable.fromIterable(requests).concatMapSingle(this::rxClassify).toList();
  }

  /**
   * Classifies a batch of input texts with per-request label overrides.
   *
   * <p>When {@code labels} is non-empty, implementations that support dynamic
   * labels (e.g. GLiNER) will use them instead of the model's default labels.
   * The default implementation ignores the labels and delegates to
   * {@link #rxClassifyBatch(List)}.
   *
   * @param requests the list of classification inputs
   * @param labels   optional label overrides; empty list means use model defaults
   * @return a {@link Single} emitting a list of responses in the same order as inputs
   */
  default Single<List<ClassifyResponse>> rxClassifyBatch(
    List<ClassifyRequest> requests,
    List<ClassifyLabel> labels
  ) {
    return rxClassifyBatch(requests);
  }
}
