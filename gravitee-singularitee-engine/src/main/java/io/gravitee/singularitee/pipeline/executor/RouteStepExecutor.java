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
package io.gravitee.singularitee.pipeline.executor;

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.EmbedRequest;
import io.gravitee.singularitee.engine.EmbeddingEngine;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.Pipeline;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.RouteRule;
import io.gravitee.singularitee.protocol.RouteStepConfig;
import io.gravitee.singularitee.protocol.RoutingStrategy;
import io.gravitee.singularitee.protocol.StepType;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a ROUTE step: classifies/embeds input and routes to different branches.
 *
 * <p>For {@link RoutingStrategy#ROUTING_STRATEGY_EMBEDDING_KNN}, reference embeddings
 * are pre-computed at workspace load time via {@link #rxWarmupEmbeddings(Pipeline)}
 * and cached so that runtime routing only requires a single embedding call.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RouteStepExecutor implements StepExecutor<RouteStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RouteStepExecutor.class);

  private final StepExecutionContext execContext;

  private final ConcurrentHashMap<String, List<RuleEmbedding>> embeddingCache =
    new ConcurrentHashMap<>();

  record RuleEmbedding(String label, float[] embedding) {}

  public RouteStepExecutor(StepExecutionContext execContext) {
    this.execContext = execContext;
  }

  // ---------------------------------------------------------------------------
  // Embedding warm-up (reactive, called at workspace load time)
  // ---------------------------------------------------------------------------

  /**
   * Pre-computes reference embeddings for all KNN route steps in the given pipeline.
   * Returns a {@link Completable} that completes when all embeddings are ready.
   */
  public Completable rxWarmupEmbeddings(Pipeline pipeline) {
    List<Completable> warmups = new ArrayList<>();
    for (var step : pipeline.getStepsList()) {
      if (step.getType() != StepType.STEP_TYPE_ROUTE) continue;
      var cfg = step.getRouteConfig();
      if (cfg.getStrategy() != RoutingStrategy.ROUTING_STRATEGY_EMBEDDING_KNN) continue;

      var entryOpt = execContext.lookupModel(cfg.getModelId());
      if (entryOpt.isEmpty()) {
        LOGGER.warn(
          "Warmup: embedding model '{}' not found for KNN route step '{}' — skipping",
          cfg.getModelId(),
          step.getStepId()
        );
        continue;
      }
      if (!(entryOpt.get().engine() instanceof EmbeddingEngine ee)) {
        LOGGER.warn(
          "Warmup: model '{}' is not an EmbeddingEngine for KNN route step '{}' — skipping",
          cfg.getModelId(),
          step.getStepId()
        );
        continue;
      }

      String cacheKey = cacheKey(pipeline.getPipelineId(), step.getStepId());
      warmups.add(
        rxComputeRuleEmbeddings(cfg, ee)
          .doOnSuccess(embeddings -> {
            embeddingCache.put(cacheKey, embeddings);
            LOGGER.info(
              "Pre-computed {} reference embedding(s) for KNN route step '{}'",
              embeddings.size(),
              step.getStepId()
            );
          })
          .ignoreElement()
      );
    }
    return warmups.isEmpty() ? Completable.complete() : Completable.merge(warmups);
  }

  // ---------------------------------------------------------------------------
  // Step execution
  // ---------------------------------------------------------------------------

  @Override
  public RouteStepConfig extractConfig(PipelineStep step) {
    return step.getRouteConfig();
  }

  @Override
  public Maybe<String> execute(String stepId, RouteStepConfig cfg, StepContext ctx) {
    String text = ctx
      .pipelineContext()
      .get(cfg.getInputField().isBlank() ? PipelineContext.KEY_PROMPT : cfg.getInputField());

    return rxResolveRouteLabel(cfg, text, stepId, ctx).flatMapMaybe(resolvedLabel -> {
      for (RouteRule rule : cfg.getRulesList()) {
        if (rule.getLabel().equals(resolvedLabel)) {
          LOGGER.debug(
            "RouteStep '{}': label='{}' → step '{}'",
            stepId,
            resolvedLabel,
            rule.getNextStepId()
          );
          return Maybe.just(rule.getNextStepId());
        }
      }
      String defaultStep = cfg.getDefaultStepId();
      LOGGER.debug(
        "RouteStep '{}': no rule matched label='{}' → default step '{}'",
        stepId,
        resolvedLabel,
        defaultStep
      );
      return (defaultStep == null || defaultStep.isBlank())
        ? Maybe.empty()
        : Maybe.just(defaultStep);
    });
  }

  // ---------------------------------------------------------------------------
  // Strategy dispatch
  // ---------------------------------------------------------------------------

  private Single<String> rxResolveRouteLabel(
    RouteStepConfig cfg,
    String text,
    String stepId,
    StepContext ctx
  ) {
    if (text == null || text.isBlank()) return Single.just("");

    RoutingStrategy strategy = cfg.getStrategy();

    if (strategy == RoutingStrategy.ROUTING_STRATEGY_CLASSIFIER) {
      return rxClassifierRoute(cfg, text, stepId);
    } else if (strategy == RoutingStrategy.ROUTING_STRATEGY_EMBEDDING_KNN) {
      return rxEmbeddingKnnRoute(cfg, text, stepId, ctx);
    } else if (strategy == RoutingStrategy.ROUTING_STRATEGY_LLM_STRUCTURED) {
      return rxLlmStructuredRoute(cfg, text, stepId);
    }
    return Single.just("");
  }

  // ---------------------------------------------------------------------------
  // Classifier strategy
  // ---------------------------------------------------------------------------

  private Single<String> rxClassifierRoute(RouteStepConfig cfg, String text, String stepId) {
    var entryOpt = execContext.lookupModel(cfg.getModelId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("RouteStep '{}': classifier model '{}' not found", stepId, cfg.getModelId());
      return Single.just("");
    }
    var entry = entryOpt.get();
    if (!(entry.engine() instanceof ClassifierEngine ce)) {
      execContext.logTypeError(
        stepId,
        cfg.getModelId(),
        "ClassifierEngine",
        entry.engine().getClass().getSimpleName()
      );
      return Single.just("");
    }
    return ce.rxClassify(new ClassifyRequest(text)).map(r -> r.topLabel());
  }

  // ---------------------------------------------------------------------------
  // LLM structured strategy (pass-through: the LLM output IS the label)
  // ---------------------------------------------------------------------------

  private Single<String> rxLlmStructuredRoute(RouteStepConfig cfg, String text, String stepId) {
    // The input text (typically from a prior LLM judge step) is the label itself.
    // Normalize: trim whitespace, strip quotes, and lowercase for fuzzy matching.
    String normalized = text.strip().toLowerCase();
    // Strip surrounding quotes if present (e.g. "tool use request" → tool use request)
    if (
      normalized.length() >= 2 &&
      ((normalized.startsWith("\"") && normalized.endsWith("\"")) ||
        (normalized.startsWith("'") && normalized.endsWith("'")))
    ) {
      normalized = normalized.substring(1, normalized.length() - 1).strip();
    }
    // Match against rule labels (case-insensitive, using contains for flexibility)
    for (RouteRule rule : cfg.getRulesList()) {
      if (normalized.contains(rule.getLabel().toLowerCase())) {
        LOGGER.debug(
          "RouteStep '{}': LLM_STRUCTURED matched label='{}' from text='{}'",
          stepId,
          rule.getLabel(),
          text.strip()
        );
        return Single.just(rule.getLabel());
      }
    }
    LOGGER.debug("RouteStep '{}': LLM_STRUCTURED no match from text='{}'", stepId, text.strip());
    return Single.just("");
  }

  // ---------------------------------------------------------------------------
  // Embedding KNN strategy
  // ---------------------------------------------------------------------------

  private Single<String> rxEmbeddingKnnRoute(
    RouteStepConfig cfg,
    String text,
    String stepId,
    StepContext ctx
  ) {
    var entryOpt = execContext.lookupModel(cfg.getModelId());
    if (entryOpt.isEmpty()) {
      LOGGER.warn("RouteStep '{}': embedding model '{}' not found", stepId, cfg.getModelId());
      return Single.just("");
    }
    if (!(entryOpt.get().engine() instanceof EmbeddingEngine ee)) {
      execContext.logTypeError(
        stepId,
        cfg.getModelId(),
        "EmbeddingEngine",
        entryOpt.get().engine().getClass().getSimpleName()
      );
      return Single.just("");
    }

    String cacheKey = ctx.pipeline() != null
      ? cacheKey(ctx.pipeline().getPipelineId(), stepId)
      : stepId;

    // Check cache first — if populated (by warmup), no async embedding needed
    List<RuleEmbedding> cached = embeddingCache.get(cacheKey);
    if (cached != null) {
      return ee
        .rxEmbed(new EmbedRequest(text))
        .map(resp -> findNearestLabel(resp.embedding(), cached, cfg));
    }

    // Cache miss — compute reference embeddings reactively, then embed the query
    return rxComputeRuleEmbeddings(cfg, ee)
      .doOnSuccess(embeddings -> embeddingCache.put(cacheKey, embeddings))
      .flatMap(references ->
        ee
          .rxEmbed(new EmbedRequest(text))
          .map(resp -> findNearestLabel(resp.embedding(), references, cfg))
      );
  }

  // ---------------------------------------------------------------------------
  // Embedding helpers
  // ---------------------------------------------------------------------------

  private Single<List<RuleEmbedding>> rxComputeRuleEmbeddings(
    RouteStepConfig cfg,
    EmbeddingEngine ee
  ) {
    List<Single<RuleEmbedding>> singles = new ArrayList<>();
    for (var rule : cfg.getRulesList()) {
      List<String> sentences = rule.getSentencesList();
      List<String> texts = sentences.isEmpty() ? List.of(rule.getLabel()) : sentences;
      for (String t : texts) {
        String label = rule.getLabel();
        singles.add(
          ee.rxEmbed(new EmbedRequest(t)).map(r -> new RuleEmbedding(label, r.embedding()))
        );
      }
    }
    if (singles.isEmpty()) return Single.just(List.of());
    return Single.zip(singles, arr -> {
      List<RuleEmbedding> result = new ArrayList<>();
      for (Object o : arr) result.add((RuleEmbedding) o);
      return result;
    });
  }

  private static String findNearestLabel(
    float[] query,
    List<RuleEmbedding> references,
    RouteStepConfig cfg
  ) {
    if (references.isEmpty()) return "";
    String bestLabel = "";
    float bestSim = -1f;
    for (var ref : references) {
      float sim = cosineSimilarity(query, ref.embedding());
      if (sim > bestSim) {
        bestSim = sim;
        bestLabel = ref.label();
      }
    }
    return bestLabel;
  }

  private static float cosineSimilarity(float[] a, float[] b) {
    int len = Math.min(a.length, b.length);
    float dot = 0f,
      normA = 0f,
      normB = 0f;
    for (int i = 0; i < len; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
    return denom == 0 ? 0f : dot / denom;
  }

  private static String cacheKey(String pipelineId, String stepId) {
    return pipelineId + ":" + stepId;
  }
}
