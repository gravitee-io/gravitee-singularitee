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
package io.gravitee.singularitee.service;

import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.EmbeddingEngine;
import io.gravitee.singularitee.engine.RerankerEngine;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x gRPC service implementation for vector operations (embed, cosine similarity, rank).
 *
 * <p>Delegates embedding calls to the {@link EmbeddingEngine} registered in the model registry.
 * All engine calls are fully non-blocking: the engine schedules ONNX work on a worker thread
 * via {@code subscribeOn(blockingScheduler)} and delivers results back on the event loop via
 * {@code observeOn(eventLoopScheduler)}. The {@link Future} bridge uses a plain {@link Promise}
 * whose {@code complete/fail} callbacks run on the event loop — no {@code CompletionStage},
 * no context capture needed.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeVectorServiceImpl extends GraviteeVectorServiceGrpcService {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeVectorServiceImpl.class);

  private final ModelRegistry registry;
  private final io.gravitee.singularitee.inference.math.api.GioMaths gioMaths;
  private final ServiceInstrumentation instrumentation;

  public GraviteeVectorServiceImpl(
    Vertx vertx,
    ModelRegistry registry,
    io.gravitee.singularitee.inference.math.api.GioMaths gioMaths,
    Tracer tracer,
    InferenceMetrics metrics
  ) {
    this.registry = registry;
    this.gioMaths = gioMaths;
    this.instrumentation = new ServiceInstrumentation(vertx, tracer, metrics);
  }

  // ---------------------------------------------------------------------------
  // Embed (single text)
  // ---------------------------------------------------------------------------

  @Override
  public Future<EmbedResponse> embed(EmbedRequest request) {
    return instrumentation.traceUnary("ai.embed", "embed", request.getModelId(), () -> {
      var entryOpt = registry.get(request.getModelId());
      if (entryOpt.isEmpty()) {
        return Future.failedFuture("Model not found: " + request.getModelId());
      }
      var engine = entryOpt.get().engine();
      if (!(engine instanceof EmbeddingEngine embeddingEngine)) {
        return Future.failedFuture(
          "Model '" + request.getModelId() + "' is not an embedding model"
        );
      }

      Promise<EmbedResponse> promise = Promise.promise();
      embeddingEngine
        .rxEmbed(new io.gravitee.singularitee.engine.EmbedRequest(request.getText()))
        .map(engineResp -> {
          var vecBuilder = FloatVector.newBuilder();
          for (float v : engineResp.embedding()) {
            vecBuilder.addValues(v);
          }
          return EmbedResponse.newBuilder()
            .setEmbedding(vecBuilder.build())
            .setTokenCount(engineResp.tokenCount())
            .build();
        })
        .subscribe(promise::complete, promise::fail);
      return promise.future();
    });
  }

  // ---------------------------------------------------------------------------
  // Embed batch
  // ---------------------------------------------------------------------------

  @Override
  public Future<EmbedBatchResponse> embedBatch(EmbedBatchRequest request) {
    return instrumentation.traceUnary("ai.embed.batch", "embed", request.getModelId(), () -> {
      var entryOpt = registry.get(request.getModelId());
      if (entryOpt.isEmpty()) {
        return Future.failedFuture("Model not found: " + request.getModelId());
      }
      var engine = entryOpt.get().engine();
      if (!(engine instanceof EmbeddingEngine embeddingEngine)) {
        return Future.failedFuture(
          "Model '" + request.getModelId() + "' is not an embedding model"
        );
      }

      Promise<EmbedBatchResponse> promise = Promise.promise();
      embeddingEngine
        .rxEmbedBatch(request.getTextsList())
        .map(responses -> {
          var builder = EmbedBatchResponse.newBuilder();
          for (var engineResp : responses) {
            var vecBuilder = FloatVector.newBuilder();
            for (float v : engineResp.embedding()) {
              vecBuilder.addValues(v);
            }
            builder.addItems(
              EmbedBatchItem.newBuilder()
                .setEmbedding(vecBuilder.build())
                .setTokenCount(engineResp.tokenCount())
                .build()
            );
          }
          return builder.build();
        })
        .subscribe(promise::complete, promise::fail);
      return promise.future();
    });
  }

  // ---------------------------------------------------------------------------
  // Cosine similarity
  // ---------------------------------------------------------------------------

  @Override
  public Future<CosineSimilarityResponse> cosineSimilarity(CosineSimilarityRequest request) {
    try {
      float[] a = toFloatArray(request.getA());
      float[] b = toFloatArray(request.getB());
      float score = gioMaths.cosineSimilarity(a, b);
      return Future.succeededFuture(CosineSimilarityResponse.newBuilder().setScore(score).build());
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Rank (top-k nearest neighbours)
  // ---------------------------------------------------------------------------

  @Override
  public Future<RankResponse> rank(RankRequest request) {
    try {
      float[] query = toFloatArray(request.getQuery());
      int topK = request.getTopK() > 0 ? request.getTopK() : request.getCandidatesCount();

      record Scored(int index, float score) {}
      var scored = new java.util.ArrayList<Scored>();
      for (int i = 0; i < request.getCandidatesCount(); i++) {
        float[] candidate = toFloatArray(request.getCandidates(i));
        float score = gioMaths.cosineSimilarity(query, candidate);
        scored.add(new Scored(i, score));
      }

      scored.sort((x, y) -> Float.compare(y.score(), x.score()));

      var builder = RankResponse.newBuilder();
      for (int i = 0; i < Math.min(topK, scored.size()); i++) {
        var s = scored.get(i);
        builder.addResults(
          RankResponse.RankedResult.newBuilder().setIndex(s.index()).setScore(s.score()).build()
        );
      }
      return Future.succeededFuture(builder.build());
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Text similarity (high-level: text in, scores out)
  // ---------------------------------------------------------------------------

  @Override
  public Future<TextSimilarityResponse> textSimilarity(TextSimilarityRequest request) {
    return instrumentation.traceUnary("ai.text_similarity", "embed", request.getModelId(), () ->
      textSimilarityInternal(request)
    );
  }

  private Future<TextSimilarityResponse> textSimilarityInternal(TextSimilarityRequest request) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      return Future.failedFuture("Model not found: " + request.getModelId());
    }
    var engine = entryOpt.get().engine();
    if (!(engine instanceof EmbeddingEngine embeddingEngine)) {
      return Future.failedFuture("Model '" + request.getModelId() + "' is not an embedding model");
    }

    List<String> allTexts = new ArrayList<>();
    allTexts.addAll(request.getInputList());
    allTexts.addAll(request.getCandidatesList());

    int inputCount = request.getInputCount();
    int candidateCount = request.getCandidatesCount();

    Promise<TextSimilarityResponse> promise = Promise.promise();
    embeddingEngine
      .rxEmbedBatch(allTexts)
      .map(responses -> {
        int totalTokens = 0;
        float[][] vectors = new float[responses.size()][];
        for (int i = 0; i < responses.size(); i++) {
          vectors[i] = responses.get(i).embedding();
          totalTokens += responses.get(i).tokenCount();
        }

        var builder = TextSimilarityResponse.newBuilder()
          .setInputCount(inputCount)
          .setCandidateCount(candidateCount)
          .setTotalTokens(totalTokens);

        if (request.getMode() == SimilarityMode.SIMILARITY_MODE_ZIPPED) {
          // Zipped: score[i] = cosine(input[i], candidates[i])
          for (int i = 0; i < inputCount; i++) {
            float score = gioMaths.cosineSimilarity(vectors[i], vectors[inputCount + i]);
            builder.addScores(score);
          }
        } else {
          // CROSS (default): full matrix, row-major
          // results[i * candidateCount + j] = cosine(input[i], candidates[j])
          for (int i = 0; i < inputCount; i++) {
            for (int j = 0; j < candidateCount; j++) {
              float score = gioMaths.cosineSimilarity(vectors[i], vectors[inputCount + j]);
              builder.addScores(score);
            }
          }
        }

        return builder.build();
      })
      .subscribe(promise::complete, promise::fail);
    return promise.future();
  }

  // ---------------------------------------------------------------------------
  // Text rerank (high-level: query + documents in, ranked results out)
  // ---------------------------------------------------------------------------

  @Override
  public Future<TextRerankResponse> textRerank(TextRerankRequest request) {
    return instrumentation.traceUnary("ai.text_rerank", "rerank", request.getModelId(), () ->
      textRerankInternal(request)
    );
  }

  private Future<TextRerankResponse> textRerankInternal(TextRerankRequest request) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      return Future.failedFuture("Model not found: " + request.getModelId());
    }
    var engine = entryOpt.get().engine();

    // Cross-encoder path: direct (query, doc) scoring via RerankerEngine.
    if (engine instanceof RerankerEngine reranker) {
      Promise<TextRerankResponse> promise = Promise.promise();
      reranker
        .rxRerank(
          new io.gravitee.singularitee.engine.RerankRequest(
            request.getQuery(),
            request.getDocumentsList(),
            request.getTopK()
          )
        )
        .map(response -> {
          var builder = TextRerankResponse.newBuilder().setTotalTokens(response.totalTokens());
          for (var r : response.results()) {
            builder.addResults(
              TextRerankResult.newBuilder().setIndex(r.index()).setScore(r.score()).build()
            );
          }
          return builder.build();
        })
        .subscribe(promise::complete, promise::fail);
      return promise.future();
    }

    // Bi-encoder fallback: embed query + docs, rank by cosine similarity.
    if (!(engine instanceof EmbeddingEngine embeddingEngine)) {
      return Future.failedFuture(
        "Model '" + request.getModelId() + "' is neither an embedding nor a reranker model"
      );
    }

    List<String> allTexts = new ArrayList<>();
    allTexts.add(request.getQuery());
    allTexts.addAll(request.getDocumentsList());

    int docCount = request.getDocumentsCount();
    int topK = request.getTopK() > 0 ? request.getTopK() : docCount;

    Promise<TextRerankResponse> promise = Promise.promise();
    embeddingEngine
      .rxEmbedBatch(allTexts)
      .map(responses -> {
        int totalTokens = 0;
        float[][] vectors = new float[responses.size()][];
        for (int i = 0; i < responses.size(); i++) {
          vectors[i] = responses.get(i).embedding();
          totalTokens += responses.get(i).tokenCount();
        }

        float[] queryVec = vectors[0];
        record Scored(int index, float score) {}
        var scored = new ArrayList<Scored>();
        for (int i = 0; i < docCount; i++) {
          float score = gioMaths.cosineSimilarity(queryVec, vectors[1 + i]);
          scored.add(new Scored(i, score));
        }

        scored.sort((x, y) -> Float.compare(y.score(), x.score()));

        var builder = TextRerankResponse.newBuilder().setTotalTokens(totalTokens);
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
          var s = scored.get(i);
          builder.addResults(
            TextRerankResult.newBuilder().setIndex(s.index()).setScore(s.score()).build()
          );
        }
        return builder.build();
      })
      .subscribe(promise::complete, promise::fail);
    return promise.future();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static float[] toFloatArray(FloatVector vec) {
    float[] arr = new float[vec.getValuesCount()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = vec.getValues(i);
    }
    return arr;
  }
}
