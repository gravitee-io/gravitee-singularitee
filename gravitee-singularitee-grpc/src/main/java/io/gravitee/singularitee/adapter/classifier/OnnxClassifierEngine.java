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
package io.gravitee.singularitee.adapter.classifier;

import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.adapter.batching.BatchingConfig;
import io.gravitee.singularitee.adapter.batching.MicroBatcher;
import io.gravitee.singularitee.engine.api.*;
import io.gravitee.singularitee.inference.api.classifier.ClassifierMode;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResult;
import io.gravitee.singularitee.inference.api.classifier.ClassifierResults;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.onnx.bert.classifier.OnnxBertClassifierModel;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClassifierEngine} backed by an ONNX BERT classifier model.
 *
 * <p>In both modes, long inputs are split into model-sized chunks (real tokenizer boundaries,
 * semantic first) and every chunk is submitted to a shared {@link MicroBatcher}, so chunks from
 * concurrent requests fuse into a single batched ONNX run. Tune via the
 * {@code GRAVITEE_ONNX_BATCH_*} knobs (see {@link BatchingConfig}). In {@code TOKEN} mode each
 * token span is shifted back from its chunk to the original text.
 *
 * <p>This class and {@link OnnxClassifierFactory} are the <strong>only</strong>
 * files permitted to import {@code gravitee-inference-onnx} classifier types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OnnxClassifierEngine
  extends BlockingEngineAdapter<OnnxBertClassifierModel>
  implements ClassifierEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnnxClassifierEngine.class);

  private final ClassifierMode mode;

  /** Coalesces the chunks of all concurrent requests into one batched run of the mode's head. */
  private final MicroBatcher<String, ClassifierResults> batcher;

  OnnxClassifierEngine(OnnxBertClassifierModel delegate, ClassifierMode mode, Vertx vertx) {
    super(delegate, vertx);
    this.mode = mode == null ? ClassifierMode.SEQUENCE : mode;
    if (!delegate.acceptsBatches()) {
      LOGGER.warn(
        "ONNX classifier graph pins its batch size to 1: inputs run one at a time, not batched"
      );
    }
    this.batcher = BatchingConfig.fromEnv("GRAVITEE_ONNX_BATCH").newBatcher(
      "onnx-classifier",
      this.mode == ClassifierMode.SEQUENCE ? delegate::classifySequences : delegate::classifyTokens
    );
  }

  @Override
  public String task() {
    return mode == ClassifierMode.TOKEN
      ? ModelTasks.TOKEN_CLASSIFICATION
      : ModelTasks.TEXT_CLASSIFICATION;
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return mode == ClassifierMode.SEQUENCE
      ? rxClassifySequenceBatched(request.text())
      : rxClassifyTokensBatched(request.text());
  }

  @Override
  public Single<List<ClassifyResponse>> rxClassifyBatch(
    List<ClassifyRequest> requests,
    List<ClassifierEngine.ClassifyLabel> labels
  ) {
    // concatMapEager (not the sequential concatMap default): subscribes to every request at once
    // so the micro-batcher can coalesce their chunks, while emitting responses in request order.
    return Flowable.fromIterable(requests)
      .concatMapEager(req -> rxClassify(req, labels).toFlowable())
      .toList();
  }

  /**
   * Sequence classification via the shared batcher: single-chunk inputs are submitted whole (and
   * keep today's no-span result shape); split inputs submit each chunk and rebuild one row per
   * (label, chunk) tagged with the chunk's char span, mirroring the delegate's single-call path.
   */
  private Single<ClassifyResponse> rxClassifySequenceBatched(String text) {
    return Single.defer(() -> {
      var chunks = delegate.split(text);
      if (chunks.size() <= 1) {
        return Single.fromCompletionStage(
          batcher.submit(text, EstimatedTokens.estimateTokens(text))
        )
          .map(this::toLocalResponse)
          .observeOn(eventLoopScheduler());
      }
      List<Single<ChunkResults>> perChunk = new ArrayList<>(chunks.size());
      for (RecursiveTextSplitter.Chunk chunk : chunks) {
        perChunk.add(
          Single.fromCompletionStage(
            batcher.submit(chunk.text(), EstimatedTokens.estimateTokens(chunk.text()))
          ).map(results -> new ChunkResults(chunk, results))
        );
      }
      return Single.zip(perChunk, OnnxClassifierEngine::perChunkRows)
        .map(this::toLocalResponse)
        .observeOn(eventLoopScheduler());
      // split() is CPU-bound tokenizer work running at subscription time; keep it off the
      // caller's event loop.
    }).subscribeOn(workerScheduler());
  }

  /**
   * Token classification via the shared batcher: every chunk is submitted and each token row is
   * shifted by its chunk's start, so spans index the original text.
   */
  private Single<ClassifyResponse> rxClassifyTokensBatched(String text) {
    return Single.defer(() -> {
      List<Single<List<ClassifierResult>>> perChunk = new ArrayList<>();
      for (RecursiveTextSplitter.Chunk chunk : delegate.split(text)) {
        perChunk.add(
          Single.fromCompletionStage(
            batcher.submit(chunk.text(), EstimatedTokens.estimateTokens(chunk.text()))
          ).map(results -> shiftSpans(results, chunk.start()))
        );
      }
      if (perChunk.isEmpty()) {
        return Single.just(new ClassifierResults(List.of()));
      }
      return Single.zip(perChunk, OnnxClassifierEngine::concatRows);
    })
      // split() is CPU-bound tokenizer work running at subscription time; keep it off the
      // caller's event loop.
      .subscribeOn(workerScheduler())
      .map(this::toLocalResponse)
      .observeOn(eventLoopScheduler());
  }

  private static List<ClassifierResult> shiftSpans(ClassifierResults results, int offset) {
    return results
      .results()
      .stream()
      .map(r ->
        new ClassifierResult(r.label(), r.score(), r.token(), r.start() + offset, r.end() + offset)
      )
      .toList();
  }

  @SuppressWarnings("unchecked")
  private static ClassifierResults concatRows(Object[] perChunk) {
    List<ClassifierResult> rows = new ArrayList<>();
    for (Object chunkRows : perChunk) {
      rows.addAll((List<ClassifierResult>) chunkRows);
    }
    return new ClassifierResults(rows);
  }

  /** One row per (label, chunk) tagged with the chunk's char span, mirroring the delegate's perSplitRows. */
  private static ClassifierResults perChunkRows(Object[] chunkResults) {
    List<ClassifierResult> rows = new ArrayList<>();
    for (Object o : chunkResults) {
      ChunkResults cr = (ChunkResults) o;
      for (ClassifierResult r : cr.results().results()) {
        rows.add(
          new ClassifierResult(r.label(), r.score(), null, cr.chunk().start(), cr.chunk().end())
        );
      }
    }
    return new ClassifierResults(rows);
  }

  private record ChunkResults(RecursiveTextSplitter.Chunk chunk, ClassifierResults results) {}

  @Override
  public void close() throws Exception {
    batcher.close();
    delegate.close();
  }

  private ClassifyResponse toLocalResponse(ClassifierResults results) {
    var local = results
      .results()
      .stream()
      .map(r -> new ClassifyResult(r.label(), r.score(), r.token(), r.start(), r.end()))
      .toList();

    // Headline score per label = the strongest occurrence across splits (or tokens). When a
    // sequence input was split, `local` holds one row per (label, split); collapsing with max
    // keeps "any split crossing the line flags the input".
    Map<String, Float> allScores = new LinkedHashMap<>();
    for (ClassifierResult r : results.results()) {
      allScores.merge(r.label(), r.score(), Math::max);
    }

    var top = allScores.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
    String topLabel = top == null ? "" : top.getKey();
    float topScore = top == null ? 0f : top.getValue();

    return new ClassifyResponse(topLabel, topScore, allScores, local);
  }
}
