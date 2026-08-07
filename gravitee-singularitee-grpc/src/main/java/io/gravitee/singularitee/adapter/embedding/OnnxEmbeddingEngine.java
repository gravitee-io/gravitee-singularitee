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
package io.gravitee.singularitee.adapter.embedding;

import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.adapter.batching.BatchingConfig;
import io.gravitee.singularitee.adapter.batching.MicroBatcher;
import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.onnx.bert.embedding.OnnxBertEmbeddingModel;
import io.gravitee.singularitee.inference.onnx.bert.embedding.OnnxBertEmbeddingModel.ChunkEmbedding;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link EmbeddingEngine} backed by an ONNX BERT embedding model.
 *
 * <p>Long inputs are split into model-sized chunks (real tokenizer boundaries) and every chunk is
 * submitted to a shared {@link MicroBatcher}, so chunks from concurrent requests fuse into a single
 * batched ONNX run instead of each request paying its own forward pass. Tune via the
 * {@code GRAVITEE_ONNX_BATCH_*} knobs (see {@link BatchingConfig}).
 *
 * <p>This class and {@link OnnxEmbeddingFactory} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-onnx} embedding types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxEmbeddingEngine
  extends BlockingEngineAdapter<OnnxBertEmbeddingModel>
  implements EmbeddingEngine {

  /** Coalesces chunk encodes from all concurrent requests into single batched ONNX runs. */
  private final MicroBatcher<String, ChunkEmbedding> batcher;

  OnnxEmbeddingEngine(OnnxBertEmbeddingModel delegate, Vertx vertx) {
    super(delegate, vertx);
    this.batcher = BatchingConfig.fromEnv("GRAVITEE_ONNX_BATCH").newBatcher(
      "onnx-embedding",
      delegate::encodePooled
    );
  }

  @Override
  public Single<EmbedResponse> rxEmbed(EmbedRequest request) {
    return Single.defer(() -> {
      // Measured first, because this count is needed either way — and when the input
      // already fits, knowing it lets us skip split() entirely. split() tokenises the
      // whole text again to find its boundaries, so for the common case of a short
      // input that was a third full tokenizer pass for a result we could predict:
      // content tokens <= countTokens <= budget means the splitter returns the text
      // unchanged. Longer inputs fall through to the unchanged splitting path.
      int fullTokenCount = delegate.countTokens(request.text());

      if (fullTokenCount > 0 && fullTokenCount <= delegate.sequenceBudget()) {
        return Single.fromCompletionStage(batcher.submit(request.text(), fullTokenCount))
          // Still through combine(): it normalises even a single chunk, so bypassing it
          // would return a differently-scaled vector than the split path produces.
          .map(chunk ->
            new EmbedResponse(delegate.combine(List.of(chunk)).embedding(), fullTokenCount)
          )
          .observeOn(eventLoopScheduler());
      }

      var chunks = delegate.split(request.text());
      if (chunks.isEmpty()) {
        // blank input — preserve infer()'s token count (full-input tokenize) semantics cheaply
        return rxInfer(() -> {
          var result = delegate.infer(request.text());
          return new EmbedResponse(result.embedding(), result.tokenCount());
        });
      }
      List<Single<ChunkEmbedding>> perChunk = new ArrayList<>(chunks.size());
      for (RecursiveTextSplitter.Chunk chunk : chunks) {
        perChunk.add(
          Single.fromCompletionStage(
            batcher.submit(chunk.text(), EstimatedTokens.estimateTokens(chunk.text()))
          )
        );
      }
      // infer()'s contract: token count = full-input tokenize incl. special tokens, not
      // combine()'s content-token sum — measured above, on this worker thread.
      return Single.zip(perChunk, OnnxEmbeddingEngine::toChunkEmbeddings)
        .map(pooled -> {
          var combined = delegate.combine(pooled);
          return new EmbedResponse(combined.embedding(), fullTokenCount);
        })
        .observeOn(eventLoopScheduler());
      // split() + countTokens() are CPU-bound tokenizer work running at subscription time —
      // keep them off the caller's event loop.
    }).subscribeOn(workerScheduler());
  }

  @Override
  public Single<List<EmbedResponse>> rxEmbedBatch(List<String> texts) {
    // concatMapEager: subscribes to every text at once (so the micro-batcher fuses chunks across
    // the batch's texts and concurrent requests) while emitting responses in input order.
    return Flowable.fromIterable(texts)
      .concatMapEager(text -> rxEmbed(new EmbedRequest(text)).toFlowable())
      .toList();
  }

  private static List<ChunkEmbedding> toChunkEmbeddings(Object[] results) {
    return Arrays.stream(results).map(ChunkEmbedding.class::cast).toList();
  }

  @Override
  public void close() throws Exception {
    batcher.close();
    delegate.close();
  }
}
