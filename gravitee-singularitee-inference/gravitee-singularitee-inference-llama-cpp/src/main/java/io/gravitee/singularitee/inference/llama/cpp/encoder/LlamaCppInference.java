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

import io.gravitee.llama.cpp.*;
import io.gravitee.llama.cpp.nativelib.LlamaLibLoader;
import io.gravitee.singularitee.inference.api.InferenceModel;
import io.gravitee.singularitee.inference.llama.cpp.LlamaBackend;
import io.gravitee.singularitee.inference.llama.cpp.ModelConfig;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Abstract base for all llama.cpp-backed encoder inference models (embedding,
 * reranker, classifier, token-embedding). Manages the model lifecycle, context
 * creation, tokenization and batch execution.
 *
 * <p>Architecture-agnostic: handles both encoder (BERT, ModernBERT, Jina) and
 * decoder (Qwen3-Embedding, Qwen3-Reranker) models transparently.
 *
 * @param <INPUT>  the inference input type
 * @param <OUTPUT> the inference output type
 *
 * @author Remi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class LlamaCppInference<INPUT, OUTPUT>
  extends InferenceModel<ModelConfig, INPUT, OUTPUT> {

  static {
    LlamaBackend.init();
  }

  private static void loadAllBackends() {
    String location = LlamaLibLoader.load();
    try (Arena a = Arena.ofConfined()) {
      LlamaRuntime.ggml_backend_load_all_from_path(a, location);
    }
  }

  protected final Arena arena;
  protected final LlamaModel model;
  protected final LlamaContext context;
  protected final LlamaVocab vocab;
  protected final LlamaTokenizer tokenizer;

  protected LlamaCppInference(ModelConfig config) {
    super(config);
    this.arena = Arena.ofAuto();

    if (!config.hasRpcServers()) {
      loadAllBackends();
    }

    var modelParams = new LlamaModelParams(arena)
      .useMlock(config.useMlock())
      .useMmap(config.useMmap())
      .nGpuLayers(config.nGpuLayers());

    if (config.splitMode() != null) {
      modelParams.splitMode(config.splitMode());
    }
    if (config.mainGpu() >= 0) {
      modelParams.mainGpu(config.mainGpu());
    }

    this.model = new LlamaModel(arena, config.modelPath(), modelParams);

    var contextParams = new LlamaContextParams(arena)
      .nThreads(config.nThreads())
      .nThreadsBatch(config.nThreadsBatch())
      .embeddings(true)
      .nCtx(config.nCtx());

    if (config.nBatch() != 0) contextParams.nBatch(config.nBatch());
    if (config.nUBatch() != 0) contextParams.nUBatch(config.nUBatch());
    if (config.nSeqMax() != 0) contextParams.nSeqMax(config.nSeqMax());

    if (config.poolingType() != null) {
      contextParams.poolingType(config.poolingType());
    }
    if (config.attentionType() != null) {
      contextParams.attentionType(config.attentionType());
    }
    if (config.flashAttnType() != null) {
      contextParams.flashAttnType(config.flashAttnType());
    }
    contextParams.offloadKQV(config.offloadKQV());
    contextParams.noPerf(config.noPerf());

    this.context = new LlamaContext(arena, model, contextParams);
    this.vocab = new LlamaVocab(model);
    this.tokenizer = new LlamaTokenizer(vocab, context);
  }

  /**
   * Tokenizes text, packs all tokens into a single batch with all tokens marked as
   * outputs (required for pooling), decodes, and returns the pooled embedding via
   * {@link LlamaContext#getEmbeddingsSeq(int)}.
   */
  protected float[] decodePooled(String text, int seqId) {
    context.clearCache();
    try (Arena local = Arena.ofConfined()) {
      var tokenized = tokenizer.tokenize(local, text);
      var batch = new LlamaBatch(local, tokenized.size(), 0, 1);
      batch.enableCache();
      for (int i = 0; i < tokenized.size(); i++) {
        int tokenId = tokenized.data().getAtIndex(ValueLayout.JAVA_INT, i);
        batch.add(tokenId, i, List.of(seqId), true);
      }
      int ret = context.decode(batch);
      if (ret != 0) {
        batch.free();
        throw new LlamaException("decode() returned non-zero status: " + ret);
      }
      batch.free();
    }
    return context.getEmbeddingsSeq(seqId);
  }

  /**
   * Counts tokens for a given text without decoding.
   */
  protected int countTokens(String text) {
    try (Arena local = Arena.ofConfined()) {
      return tokenizer.tokenize(local, text).size();
    }
  }

  @Override
  public void close() {
    // The arena is Arena.ofAuto(); it's released by the GC when no references
    // remain. We only need to free the explicit native handles here.
    context.free();
    model.free();
  }
}
