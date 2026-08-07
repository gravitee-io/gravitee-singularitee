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
package io.gravitee.singularitee.adapter.reranker;

import io.gravitee.singularitee.engine.RerankerEngine;
import io.gravitee.singularitee.inference.onnx.bert.reranker.OnnxBertRerankerModel;
import io.vertx.rxjava3.core.Vertx;

/**
 * {@link RerankerEngine} backed by an ONNX BERT cross-encoder reranker model.
 *
 * <p>All scoring/sorting/topK logic lives in {@link AbstractRerankerEngine}; this
 * class only binds the ONNX delegate type.
 *
 * <p>This class and {@link OnnxRerankerFactory} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-onnx} reranker types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxRerankerEngine extends AbstractRerankerEngine<OnnxBertRerankerModel> {

  OnnxRerankerEngine(OnnxBertRerankerModel delegate, Vertx vertx) {
    super(delegate, vertx);
  }
}
