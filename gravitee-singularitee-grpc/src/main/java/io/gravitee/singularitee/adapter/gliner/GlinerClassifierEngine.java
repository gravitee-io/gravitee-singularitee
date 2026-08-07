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
package io.gravitee.singularitee.adapter.gliner;

import io.gravitee.lab.gliner4j.GLiNER4jClassifier;
import io.gravitee.lab.gliner4j.schema.ClassificationLabel;
import io.gravitee.lab.gliner4j.schema.ClassificationResult;
import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.adapter.batching.MicroBatcher;
import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
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
 * {@link ClassifierEngine} backed by a GLiNER4j zero-shot classifier.
 *
 * <p>Produces sequence-level results (no spans) — suitable for toxicity guards
 * and classifier-based routing.
 *
 * <p>Long inputs are split into model-sized, disjoint chunks (see {@link GlinerChunking}) since
 * gliner4j truncates internally at the encoder window. The per-chunk text budget is the configured
 * {@code tokenCap} (default 512) minus an estimate of the label prompt; each chunk is classified
 * and per-label scores aggregate as the max across chunks, so any chunk crossing the threshold
 * flags the whole input.
 *
 * <p>This class and {@link GlinerClassifierFactory} are the <strong>only</strong>
 * files permitted to import {@code gliner4j-core} classifier types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerClassifierEngine
  extends BlockingEngineAdapter<GLiNER4jClassifier>
  implements ClassifierEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlinerClassifierEngine.class);

  private final float effectiveThreshold;
  private final int tokenCap;
  private final List<String> configLabelNames;

  /**
   * Coalesces chunk classifications from all concurrent default-schema requests into a single batched
   * {@code classifyBatch} GPU run. gliner4j's {@code classifyBatch} takes no per-request label list,
   * so custom-label requests can't batch.
   */
  private final MicroBatcher<String, List<ClassificationResult>> batcher;

  GlinerClassifierEngine(
    GLiNER4jClassifier delegate,
    float threshold,
    int tokenCap,
    List<String> configLabelNames,
    Vertx vertx
  ) {
    super(delegate, vertx);
    this.effectiveThreshold = threshold > 0 ? threshold : 0f;
    this.tokenCap = tokenCap;
    this.configLabelNames = configLabelNames == null ? List.of() : configLabelNames;
    this.batcher = GlinerBatching.CONFIG.newBatcher("gliner-classifier", texts ->
      delegate.classifyBatch(texts, effectiveThreshold)
    );
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return rxClassifyBatched(request.text(), configLabelNames);
  }

  @Override
  public Single<ClassifyResponse> rxClassify(
    ClassifyRequest request,
    List<ClassifierEngine.ClassifyLabel> labels
  ) {
    if (labels == null || labels.isEmpty()) {
      return rxClassify(request);
    }
    var classificationLabels = labels
      .stream()
      .map(l -> new ClassificationLabel(l.name(), l.description()))
      .toList();
    var names = labels.stream().map(ClassifierEngine.ClassifyLabel::name).toList();
    // Custom labels can't use classifyBatch (no per-request overload) — single-call path.
    return rxInfer(() -> classifyChunked(request.text(), classificationLabels, names));
  }

  @Override
  public Single<List<ClassifyResponse>> rxClassifyBatch(
    List<ClassifyRequest> requests,
    List<ClassifierEngine.ClassifyLabel> labels
  ) {
    // concatMapEager (not concatMap): subscribes to every request at once so the micro-batcher can
    // coalesce their chunks on the GPU, while still emitting responses in request order.
    return Flowable.fromIterable(requests)
      .concatMapEager(req -> rxClassify(req, labels).toFlowable())
      .toList();
  }

  @Override
  public void close() throws Exception {
    batcher.close();
    delegate.close();
  }

  /**
   * Default-schema classification: split to fit the encoder window, submit every chunk to the shared
   * {@link MicroBatcher} (so chunks from concurrent requests fill one GPU batch), and keep the max
   * score per label across chunks. Blank input short-circuits without touching the model.
   */
  private Single<ClassifyResponse> rxClassifyBatched(String text, List<String> labelNames) {
    if (text == null || text.isBlank()) {
      return Single.just(
        GlinerChunking.classifierResponse(new LinkedHashMap<>(), new ArrayList<>())
      );
    }
    return Single.defer(() -> {
      int budget = GlinerChunking.textBudget(tokenCap, labelNames);
      var chunks = GlinerChunking.splitter(budget).split(text);
      LOGGER.debug(
        "GLiNER classifier: split into {} chunk(s) at budget {} tokens (batched)",
        chunks.size(),
        budget
      );
      List<Single<ChunkScores>> perChunk = new ArrayList<>(chunks.size());
      for (RecursiveTextSplitter.Chunk chunk : chunks) {
        perChunk.add(
          Single.fromCompletionStage(
            batcher.submit(chunk.text(), GlinerChunking.estimateTokens(chunk.text()))
          ).map(results -> new ChunkScores(chunk, results))
        );
      }
      return Single.zip(perChunk, GlinerClassifierEngine::assembleScores);
    }).observeOn(eventLoopScheduler());
  }

  /** Rolls per-chunk classifications into per-chunk rows plus a max-per-label summary. */
  private static ClassifyResponse assembleScores(Object[] chunkResults) {
    List<ClassifyResult> rows = new ArrayList<>();
    Map<String, Float> maxByLabel = new LinkedHashMap<>();
    for (Object o : chunkResults) {
      ChunkScores cs = (ChunkScores) o;
      for (ClassificationResult r : cs.results()) {
        rows.add(
          new ClassifyResult(r.label(), r.confidence(), null, cs.chunk().start(), cs.chunk().end())
        );
        maxByLabel.merge(r.label(), r.confidence(), Math::max);
      }
    }
    return GlinerChunking.classifierResponse(maxByLabel, rows);
  }

  private record ChunkScores(
    RecursiveTextSplitter.Chunk chunk,
    List<ClassificationResult> results
  ) {}

  /**
   * Splits {@code text} to fit the encoder window (budget = tokenCap − estimated label prompt),
   * classifies each chunk, and keeps the max score per label across chunks. Used for the custom-label
   * path, which gliner4j cannot batch.
   */
  private ClassifyResponse classifyChunked(
    String text,
    List<ClassificationLabel> labels,
    List<String> labelNames
  ) {
    int budget = GlinerChunking.textBudget(tokenCap, labelNames);
    var chunks = GlinerChunking.splitter(budget).split(text);
    LOGGER.debug(
      "GLiNER classifier: split into {} chunk(s) at budget {} tokens",
      chunks.size(),
      budget
    );
    List<ClassifyResult> rows = new ArrayList<>();
    Map<String, Float> maxByLabel = new LinkedHashMap<>();
    for (RecursiveTextSplitter.Chunk chunk : chunks) {
      List<ClassificationResult> results = delegate.classify(
        chunk.text(),
        labels,
        effectiveThreshold
      );
      for (ClassificationResult r : results) {
        // one row per (label, chunk), tagged with the chunk's char span so the per-chunk
        // classifications stay visible in the response (not just the max-per-label rollup).
        rows.add(new ClassifyResult(r.label(), r.confidence(), null, chunk.start(), chunk.end()));
        maxByLabel.merge(r.label(), r.confidence(), Math::max);
      }
    }
    return GlinerChunking.classifierResponse(maxByLabel, rows);
  }
}
