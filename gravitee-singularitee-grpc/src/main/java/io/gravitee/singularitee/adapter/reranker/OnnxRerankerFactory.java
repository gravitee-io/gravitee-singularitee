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

import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import io.gravitee.singularitee.inference.onnx.bert.reranker.OnnxBertRerankerModel;
import io.gravitee.singularitee.inference.onnx.bert.resource.OnnxBertResource;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.gravitee.singularitee.workspace.config.OnnxRerankerConfig;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates an ONNX BERT cross-encoder reranker engine from a {@link ModelLoadRequest}.
 *
 * <p>By the time this factory is called, all paths in the request have been
 * resolved and verified by {@link io.gravitee.singularitee.grpc.resolver.OnnxModelResolver}
 * — they are guaranteed to exist as local filesystem paths.
 *
 * <p>This class and {@link OnnxRerankerEngine} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-onnx} reranker types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxRerankerFactory implements ModelEngineFactory {

  private final GioMaths gioMaths;
  private final Vertx vertx;

  public OnnxRerankerFactory(GioMaths gioMaths, Vertx vertx) {
    this.gioMaths = gioMaths;
    this.vertx = vertx;
  }

  @Override
  public ModelEngine create(ModelLoadRequest request) throws Exception {
    OnnxRerankerConfig cfg = request.onnxReranker();
    var resource = cfg.configJsonPath().isEmpty()
      ? new OnnxBertResource(Path.of(cfg.modelPath()), Path.of(cfg.tokenizerPath()))
      : new OnnxBertResource(
        Path.of(cfg.modelPath()),
        Path.of(cfg.tokenizerPath()),
        Path.of(cfg.configJsonPath())
      );

    Map<String, Object> onnxConfig = new HashMap<>();
    if (cfg.maxSequenceLength() > 0) onnxConfig.put("maxSequenceLength", cfg.maxSequenceLength());

    var bertConfig = new OnnxBertConfig(resource, gioMaths, onnxConfig);
    RerankScoring scoring = AbstractRerankerEngine.parseScoring(cfg.scoring());
    return new OnnxRerankerEngine(new OnnxBertRerankerModel(bertConfig, scoring), vertx);
  }
}
