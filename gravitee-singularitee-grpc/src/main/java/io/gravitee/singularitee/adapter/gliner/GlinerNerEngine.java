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

import io.gravitee.lab.gliner4j.GLiNER4jNER;
import io.gravitee.lab.gliner4j.schema.EntityDefinition;
import io.gravitee.lab.gliner4j.schema.EntitySpan;
import io.gravitee.singularitee.adapter.BlockingEngineAdapter;
import io.gravitee.singularitee.adapter.batching.MicroBatcher;
import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClassifierEngine} backed by a GLiNER4j zero-shot NER model.
 *
 * <p>Produces token-level (span) results — suitable for PII redaction guards.
 *
 * <p>Long inputs are split into model-sized, disjoint chunks (see {@link GlinerChunking}) since
 * gliner4j truncates internally at the encoder window. The per-chunk text budget is the configured
 * {@code tokenCap} (default 512) minus an estimate of the entity prompt; each chunk is extracted
 * independently and its spans are shifted back to absolute character offsets before aggregation.
 *
 * <p>This class and {@link GlinerNerFactory} are the <strong>only</strong>
 * files permitted to import {@code gliner4j-core} NER types.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GlinerNerEngine
  extends BlockingEngineAdapter<GLiNER4jNER>
  implements ClassifierEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlinerNerEngine.class);

  private final float effectiveThreshold;
  private final int tokenCap;
  private final List<String> configEntityNames;

  /**
   * Coalesces chunk extractions from all concurrent default-schema requests into a single batched
   * {@code extractBatch} GPU run. Only the configured-entity path uses it — gliner4j's
   * {@code extractBatch} takes no per-request entity list, so custom-label requests can't batch.
   */
  private final MicroBatcher<String, Map<String, List<EntitySpan>>> batcher;

  GlinerNerEngine(
    GLiNER4jNER delegate,
    float threshold,
    int tokenCap,
    List<String> configEntityNames,
    Vertx vertx
  ) {
    super(delegate, vertx);
    this.effectiveThreshold = threshold > 0 ? threshold : 0f;
    this.tokenCap = tokenCap;
    this.configEntityNames = configEntityNames == null ? List.of() : configEntityNames;
    this.batcher = GlinerBatching.CONFIG.newBatcher("gliner-ner", texts ->
      delegate.extractBatch(texts, effectiveThreshold)
    );
  }

  @Override
  public String task() {
    return ModelTasks.TOKEN_CLASSIFICATION;
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return rxExtractBatched(request.text(), configEntityNames);
  }

  @Override
  public Single<ClassifyResponse> rxClassify(
    ClassifyRequest request,
    List<ClassifierEngine.ClassifyLabel> labels
  ) {
    if (labels == null || labels.isEmpty()) {
      return rxClassify(request);
    }
    var entityDefs = labels
      .stream()
      .map(l -> new EntityDefinition(l.name(), l.description()))
      .toList();
    var names = labels.stream().map(ClassifierEngine.ClassifyLabel::name).toList();
    // Custom entities can't use extractBatch (no per-request overload) — single-call path.
    return rxInfer(() -> extractChunked(request.text(), entityDefs, names));
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
   * Default-schema extraction: split to fit the encoder window, submit every chunk to the shared
   * {@link MicroBatcher} (so chunks from concurrent requests fill one GPU batch), then re-base each
   * chunk's spans to absolute offsets. Blank input short-circuits without touching the model.
   */
  private Single<ClassifyResponse> rxExtractBatched(String text, List<String> labelNames) {
    if (text == null || text.isBlank()) {
      return Single.just(GlinerChunking.nerResponse(new ArrayList<>()));
    }
    return Single.defer(() -> {
      int budget = GlinerChunking.textBudget(tokenCap, labelNames);
      var chunks = GlinerChunking.splitter(budget).split(text);
      LOGGER.debug(
        "GLiNER NER: split into {} chunk(s) at budget {} tokens (batched)",
        chunks.size(),
        budget
      );
      List<Single<ChunkSpans>> perChunk = new ArrayList<>(chunks.size());
      for (RecursiveTextSplitter.Chunk chunk : chunks) {
        perChunk.add(
          Single.fromCompletionStage(
            batcher.submit(chunk.text(), GlinerChunking.estimateTokens(chunk.text()))
          ).map(spansByType -> new ChunkSpans(chunk, spansByType))
        );
      }
      return Single.zip(perChunk, GlinerNerEngine::assembleSpans);
    }).observeOn(eventLoopScheduler());
  }

  /** Flattens per-chunk span maps into re-based, absolute-offset results. */
  private static ClassifyResponse assembleSpans(Object[] chunkResults) {
    List<ClassifyResult> spans = new ArrayList<>();
    for (Object o : chunkResults) {
      ChunkSpans cs = (ChunkSpans) o;
      for (var entry : cs.spansByType().entrySet()) {
        for (EntitySpan span : entry.getValue()) {
          spans.add(
            new ClassifyResult(
              span.type(),
              span.confidence(),
              span.text(),
              span.start() + cs.chunk().start(),
              span.end() + cs.chunk().start()
            )
          );
        }
      }
    }
    LOGGER.debug("GLiNER NER: {} span(s) across chunked input", spans.size());
    return GlinerChunking.nerResponse(spans);
  }

  private record ChunkSpans(
    RecursiveTextSplitter.Chunk chunk,
    Map<String, List<EntitySpan>> spansByType
  ) {}

  /**
   * Splits {@code text} to fit the encoder window (budget = tokenCap − estimated entity prompt),
   * extracts each chunk, and re-bases span offsets to the original text. Used for the custom-entity
   * path, which gliner4j cannot batch.
   */
  private ClassifyResponse extractChunked(
    String text,
    List<EntityDefinition> entityDefs,
    List<String> labelNames
  ) {
    int budget = GlinerChunking.textBudget(tokenCap, labelNames);
    var chunks = GlinerChunking.splitter(budget).split(text);
    LOGGER.debug("GLiNER NER: split into {} chunk(s) at budget {} tokens", chunks.size(), budget);
    List<ClassifyResult> spans = new ArrayList<>();
    for (RecursiveTextSplitter.Chunk chunk : chunks) {
      Map<String, List<EntitySpan>> spansByType = delegate.extract(
        chunk.text(),
        entityDefs,
        effectiveThreshold
      );
      for (var entry : spansByType.entrySet()) {
        for (EntitySpan span : entry.getValue()) {
          spans.add(
            new ClassifyResult(
              span.type(),
              span.confidence(),
              span.text(),
              span.start() + chunk.start(),
              span.end() + chunk.start()
            )
          );
        }
      }
    }
    LOGGER.debug("GLiNER NER: {} span(s) across chunked input", spans.size());
    return GlinerChunking.nerResponse(spans);
  }
}
