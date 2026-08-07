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
package io.gravitee.singularitee.inference.llama.cpp.encoder;

import io.gravitee.singularitee.inference.api.reranker.RerankPair;
import io.gravitee.singularitee.inference.api.reranker.RerankScoring;
import io.gravitee.singularitee.inference.api.reranker.RerankTemplate;
import io.gravitee.singularitee.inference.api.reranker.RerankTokenCount;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import java.util.List;

/**
 * llama.cpp-backed cross-encoder reranker model. Scores (query, document) pairs.
 *
 * <p>Supports both encoder (BGE-reranker, Jina-reranker) and decoder (Qwen3-Reranker)
 * architectures. The model must be loaded with {@code poolingType=RANK} in
 * {@link ModelConfig}.
 *
 * <p>Input formatting is delegated to a {@link RerankTemplate}. Scoring transformation
 * (sigmoid, softmax, raw logit) is controlled by {@link RerankScoring}.
 *
 * @author Remi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class LlamaCppRerankerModel extends LlamaCppInference<RerankPair, RerankTokenCount> {

  private final GioMaths gioMaths;
  private final RerankTemplate template;
  private final RerankScoring scoring;
  private final int nClsOut;

  public LlamaCppRerankerModel(ModelConfig config, GioMaths gioMaths) {
    this(config, gioMaths, RerankTemplate.PLAIN, null);
  }

  public LlamaCppRerankerModel(
    ModelConfig config,
    GioMaths gioMaths,
    RerankTemplate template,
    RerankScoring scoring
  ) {
    super(config);
    this.gioMaths = gioMaths;
    this.template = template != null ? template : RerankTemplate.PLAIN;
    this.nClsOut = model.nClsOut();
    this.scoring = scoring != null
      ? scoring
      : (nClsOut == 1 ? RerankScoring.SIGMOID : RerankScoring.SOFTMAX);
  }

  @Override
  public RerankTokenCount infer(RerankPair input) {
    String formatted = template.format(input.query(), input.document());
    int tokenCount = countTokens(formatted);
    float[] raw = decodePooled(formatted, 0);
    if (raw == null) {
      throw new IllegalStateException(
        "getEmbeddingsSeq returned null - ensure the GGUF has a classifier head " +
          "and poolingType=RANK is set in ModelConfig"
      );
    }
    float score = extractScore(raw);
    return new RerankTokenCount(score, tokenCount);
  }

  @Override
  public List<RerankTokenCount> inferAll(List<RerankPair> input) {
    return input.stream().map(this::infer).toList();
  }

  private float extractScore(float[] raw) {
    return switch (scoring) {
      case SIGMOID -> {
        float logit = nClsOut == 1 ? raw[0] : raw[1];
        yield gioMaths.sigmoid(new float[] { logit })[0];
      }
      case SOFTMAX -> {
        if (nClsOut < 2) {
          throw new IllegalStateException("SOFTMAX scoring requires nClsOut >= 2, got " + nClsOut);
        }
        yield gioMaths.softmax(raw)[0];
      }
      case LOGIT -> nClsOut == 1 ? raw[0] : raw[1] - raw[0];
    };
  }
}
