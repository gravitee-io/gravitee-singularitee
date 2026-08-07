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

import io.gravitee.singularitee.inference.api.embedding.EmbeddingTemplate;
import io.gravitee.singularitee.inference.api.embedding.EmbeddingTokenCount;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import java.util.List;

/**
 * llama.cpp-backed embedding model. Produces a single pooled dense vector per input text.
 *
 * <p>Supports both encoder (BERT, ModernBERT, Jina) and decoder (Qwen3-Embedding)
 * architectures. Pooling strategy (CLS, MEAN, LAST) is determined by the
 * {@link ModelConfig#poolingType()} setting.
 *
 * <p>Optionally wraps input text with an {@link EmbeddingTemplate} for
 * instruction-aware models (e.g. Qwen3-Embedding).
 *
 * @author Remi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class LlamaCppEmbeddingModel extends LlamaCppInference<String, EmbeddingTokenCount> {

  private final GioMaths gioMaths;
  private final EmbeddingTemplate template;

  public LlamaCppEmbeddingModel(ModelConfig config, GioMaths gioMaths) {
    this(config, gioMaths, EmbeddingTemplate.IDENTITY);
  }

  public LlamaCppEmbeddingModel(ModelConfig config, GioMaths gioMaths, EmbeddingTemplate template) {
    super(config);
    this.gioMaths = gioMaths;
    this.template = template;
  }

  @Override
  public EmbeddingTokenCount infer(String input) {
    String formatted = template.format(input);
    int tokenCount = countTokens(formatted);
    float[] embedding = decodePooled(formatted, 0);
    if (embedding == null) {
      throw new IllegalStateException(
        "getEmbeddingsSeq returned null - check that the GGUF supports pooled embeddings " +
          "and that poolingType is correctly configured in ModelConfig"
      );
    }
    float[] normalized = gioMaths.normalize(embedding);
    return new EmbeddingTokenCount(normalized, tokenCount);
  }

  @Override
  public List<EmbeddingTokenCount> inferAll(List<String> input) {
    return input.stream().map(this::infer).toList();
  }
}
