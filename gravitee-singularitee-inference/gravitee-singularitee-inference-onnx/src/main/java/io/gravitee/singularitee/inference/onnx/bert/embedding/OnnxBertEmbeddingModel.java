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
package io.gravitee.singularitee.inference.onnx.bert.embedding;

import static io.gravitee.singularitee.inference.api.Constants.POOLING_MODE;
import static java.lang.Math.max;

import ai.onnxruntime.OrtException;
import io.gravitee.singularitee.inference.api.embedding.EmbeddingTokenCount;
import io.gravitee.singularitee.inference.api.embedding.PoolingMode;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.onnx.FloatTensor;
import io.gravitee.singularitee.inference.onnx.bert.OnnxBertInference;
import io.gravitee.singularitee.inference.onnx.bert.config.OnnxBertConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OnnxBertEmbeddingModel extends OnnxBertInference<String, EmbeddingTokenCount> {

  public OnnxBertEmbeddingModel(OnnxBertConfig onnxBertConfig) {
    super(onnxBertConfig);
  }

  @Override
  public EmbeddingTokenCount infer(String input) {
    var tokens = tokenizer.tokenize(input);
    List<Chunk> chunks = splitter.split(input);
    var pooled = encodePooled(chunks.stream().map(Chunk::text).toList());

    if (pooled.isEmpty()) {
      // blank input — nothing to embed
      return new EmbeddingTokenCount(new float[0], tokens.size());
    }
    // Keep the historical token count (full-input tokenize, incl. special tokens) rather than
    // combine()'s content-token sum, so infer() behavior is unchanged by the chunk-level split.
    return new EmbeddingTokenCount(combine(pooled).embedding(), tokens.size());
  }

  /**
   * Full-input token count (single tokenize pass, incl. special tokens) — the count
   * {@link #infer(String)} reports. Exposed so batched callers that recompose
   * split → encodePooled → combine themselves can report the same count.
   */
  public int countTokens(String input) {
    return tokenizer.tokenize(input).size();
  }

  /** One chunk's pooled embedding vector with its content-token count (weighting/batching cost). */
  public record ChunkEmbedding(float[] vector, int contentTokens) {}

  /**
   * Embeds pre-split chunk texts (each must fit the model's sequence budget — see
   * {@link #split(String)}) in ONE batched forward pass, pooling each chunk's hidden states. This is
   * the batch entry point used by micro-batching callers to fuse chunks from concurrent requests
   * into a single GPU run; {@link #infer(String)} recomposes split → encodePooled → combine.
   */
  public List<ChunkEmbedding> encodePooled(List<String> chunkTexts) {
    if (chunkTexts.isEmpty()) {
      return List.of();
    }
    var pooled = new ArrayList<ChunkEmbedding>(chunkTexts.size());
    var encodingResults = encodeAll(chunkTexts);
    try (var result = encodingResults.result()) {
      // Flat read (one bulk copy) instead of getValue()'s reflective nested-array
      // materialization; only each chunk's unpadded rows are copied out below.
      var batch = FloatTensor.of(result.get(0));
      for (int i = 0; i < chunkTexts.size(); i++) {
        // real (unpadded) length of this chunk, including [CLS] and [SEP]
        int sequenceLength = encodingResults.encoding().get(i).getIds().length;
        // content tokens drive the weighting
        pooled.add(new ChunkEmbedding(pool(batch, i, sequenceLength), max(sequenceLength - 2, 0)));
      }
    } catch (OrtException e) {
      throw new IllegalArgumentException(e);
    }
    return pooled;
  }

  /**
   * Reduces per-chunk pooled embeddings to a single token-weighted, normalized vector. The token
   * count is the sum of the chunks' content tokens (excludes special tokens).
   */
  public EmbeddingTokenCount combine(List<ChunkEmbedding> pooled) {
    if (pooled.isEmpty()) {
      return new EmbeddingTokenCount(new float[0], 0);
    }
    var embeddings = new float[pooled.size()][];
    var weights = new float[pooled.size()];
    int tokenCount = 0;
    for (int i = 0; i < pooled.size(); i++) {
      embeddings[i] = pooled.get(i).vector();
      weights[i] = pooled.get(i).contentTokens();
      tokenCount += pooled.get(i).contentTokens();
    }
    return new EmbeddingTokenCount(
      new EmbeddingsWithWeights(embeddings, weights).toNormalizedWeighted(config.gioMath()),
      tokenCount
    );
  }

  /**
   * Pools one batch entry's hidden states into one vector, ignoring padding by only considering
   * the first {@code sequenceLength} positions. CLS copies a single row; MEAN materializes only
   * the unpadded rows and reuses the SIMD mean.
   */
  private float[] pool(FloatTensor batch, int batchIdx, int sequenceLength) {
    int hiddenSize = batch.dim(2);
    long base = batchIdx * batch.stride(0);
    return switch (config.<PoolingMode>get(POOLING_MODE)) {
      case CLS -> batch.row(base, hiddenSize);
      case MEAN -> config.gioMath().mean(batch.rows(base, sequenceLength, hiddenSize));
    };
  }
}
