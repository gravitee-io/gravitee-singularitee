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
package io.gravitee.singularitee.engine.classifier;

import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.ClassifyResult;
import io.gravitee.singularitee.engine.ModelTasks;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ClassifierEngine} that fans out a classify request to a list of
 * delegate engines and merges their responses into a unified verdict.
 *
 * <p>Use this to compose multiple simple guards (e.g. a {@link RegexClassifierEngine}
 * + an ONNX classifier) into a single logical
 * model. A single {@code STEP_TYPE_GUARD} step then collects all matches
 * across every delegate in one pass — including the character spans needed
 * for the REDACT action.
 *
 * <p>Delegates are invoked sequentially in declaration order for deterministic
 * results. If any delegate errors, the composite propagates the error.
 *
 * <p><strong>Single split for the whole composite.</strong> Rather than letting
 * each delegate split a huge input on its own (which would re-split the same text
 * once per delegate), the composite splits the input <em>once</em> — on semantic
 * boundaries, up to {@link #DEFAULT_TOKEN_BUDGET} estimated tokens per chunk — and runs
 * every delegate over the shared chunks, shifting each delegate's per-chunk match
 * offsets back to the original text before merging. Inputs that fit the budget
 * produce a single chunk, so the common path is byte-for-byte the previous
 * behaviour (each delegate simply sees the full text); only genuinely huge inputs
 * are chunked, and then the expensive split happens once, not per delegate. A
 * delegate that does its own finer splitting (e.g. an ONNX classifier with a
 * token budget) still splits its chunk as needed — correctness is unaffected;
 * only the redundant top-level split is removed.
 *
 * <p>Result merging:
 * <ul>
 *   <li>{@code results} — union of all delegate {@link ClassifyResult} entries,
 *       preserving the original character spans.</li>
 *   <li>{@code allScores} — merged flat map. If two delegates emit the same
 *       label, the higher score wins.</li>
 *   <li>{@code topLabel} — the first non-{@code null} {@code topLabel} from
 *       any delegate (in delegate order). When no delegate matches, the
 *       composite returns an empty response ({@code topLabel = null},
 *       {@code topScore = 0.0f}, empty scores and results).</li>
 *   <li>{@code topScore} — the maximum score across all delegate top scores.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class CompositeClassifierEngine implements ClassifierEngine {

  private static final ClassifyResponse EMPTY_RESPONSE = new ClassifyResponse(
    null,
    0.0f,
    Map.of(),
    List.of()
  );

  /**
   * Default per-chunk token budget (estimated — see {@link EstimatedTokens}) used
   * when splitting a huge input once for the whole composite. Only large documents
   * are chunked; smaller inputs stay a single chunk (common path unchanged).
   * 4096 tokens ≈ 14k characters at {@value EstimatedTokens#CHARS_PER_TOKEN} chars/token.
   */
  public static final int DEFAULT_TOKEN_BUDGET = 4096;

  private final List<ClassifierEngine> delegates;

  /**
   * Splits a huge input once, on semantic boundaries, into contiguous
   * non-overlapping chunks that each fit the token budget. Shared by every
   * delegate so the split cost is paid once, not once per delegate.
   */
  private final RecursiveTextSplitter splitter;

  /**
   * Creates a new composite engine from the given delegate list, using the
   * {@link #DEFAULT_TOKEN_BUDGET default token budget}.
   *
   * @param delegates the delegate classifier engines (must not be {@code null}
   *                  or empty)
   */
  public CompositeClassifierEngine(List<ClassifierEngine> delegates) {
    this(delegates, DEFAULT_TOKEN_BUDGET);
  }

  /**
   * Creates a new composite engine from the given delegate list.
   *
   * @param delegates   the delegate classifier engines (must not be {@code null}
   *                    or empty)
   * @param tokenBudget maximum estimated tokens per shared chunk; must be {@code > 0}
   */
  public CompositeClassifierEngine(List<ClassifierEngine> delegates, int tokenBudget) {
    Objects.requireNonNull(delegates, "delegates");
    if (delegates.isEmpty()) {
      throw new IllegalArgumentException(
        "CompositeClassifierEngine requires at least one delegate"
      );
    }
    this.delegates = List.copyOf(delegates);
    this.splitter = new RecursiveTextSplitter(EstimatedTokens::endOffsets, tokenBudget);
  }

  /**
   * A composite reports the broadest task of its delegates: if any delegate is
   * a {@link ModelTasks#TOKEN_CLASSIFICATION token classifier}, the composite
   * is a token classifier (its {@code results} may carry character spans),
   * otherwise it is a {@link ModelTasks#TEXT_CLASSIFICATION text classifier}.
   */
  @Override
  public String task() {
    for (ClassifierEngine d : delegates) {
      if (ModelTasks.TOKEN_CLASSIFICATION.equals(d.task())) {
        return ModelTasks.TOKEN_CLASSIFICATION;
      }
    }
    return ModelTasks.TEXT_CLASSIFICATION;
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return Single.defer(() -> {
      List<Chunk> chunks = splitter.split(request.text());
      // Common path: the input fits the budget (single chunk) — hand each delegate
      // the full text exactly as before, so behaviour is unchanged for normal inputs.
      if (chunks.size() <= 1) {
        return Observable.fromIterable(delegates)
          .concatMapSingle(delegate -> delegate.rxClassify(request))
          .toList()
          .map(CompositeClassifierEngine::merge);
      }
      // Huge input: split once here, then run every delegate over the shared chunks.
      return Observable.fromIterable(delegates)
        .concatMapSingle(delegate -> classifyOverChunks(delegate, chunks))
        .toList()
        .map(CompositeClassifierEngine::merge);
    });
  }

  /**
   * Invoked when an enclosing composite has already split the input for us: skip
   * our own split and fan the text to each delegate in presplit mode too, so the
   * character-budget split happens exactly once at the outermost composite.
   */
  @Override
  public Single<ClassifyResponse> rxClassifyPresplit(ClassifyRequest request) {
    return Observable.fromIterable(delegates)
      .concatMapSingle(delegate -> delegate.rxClassifyPresplit(request))
      .toList()
      .map(CompositeClassifierEngine::merge);
  }

  /**
   * Runs a single delegate over the shared chunks and combines its per-chunk
   * responses into one response as if the delegate had seen the whole text:
   * spans are shifted back to the original offsets, scores rolled up per label,
   * and the delegate's top label taken from the first chunk that produced one
   * (chunks are visited in document order). Reuses {@link #merge} since combining
   * a delegate's chunks and combining delegates follow the same rules.
   */
  private static Single<ClassifyResponse> classifyOverChunks(
    ClassifierEngine delegate,
    List<Chunk> chunks
  ) {
    return Observable.fromIterable(chunks)
      .concatMapSingle(chunk ->
        // presplit: the chunk already fits the budget, so a delegate that does its
        // own character-budget splitting must not split it again.
        delegate
          .rxClassifyPresplit(new ClassifyRequest(chunk.text()))
          .map(resp -> shiftSpans(resp, chunk.start()))
      )
      .toList()
      .map(CompositeClassifierEngine::merge);
  }

  /**
   * Returns a copy of {@code resp} with every result's character span shifted by
   * {@code offset} (the chunk's start in the original text). Sequence-level
   * results carry {@code null} spans and are left untouched. A zero offset (single
   * chunk) is a no-op-equivalent identity shift.
   */
  private static ClassifyResponse shiftSpans(ClassifyResponse resp, int offset) {
    if (resp == null || resp.results() == null || resp.results().isEmpty() || offset == 0) {
      return resp;
    }
    List<ClassifyResult> shifted = new ArrayList<>(resp.results().size());
    for (ClassifyResult r : resp.results()) {
      if (r.start() == null || r.end() == null) {
        shifted.add(r);
      } else {
        shifted.add(
          new ClassifyResult(r.label(), r.score(), r.token(), r.start() + offset, r.end() + offset)
        );
      }
    }
    return new ClassifyResponse(resp.topLabel(), resp.topScore(), resp.allScores(), shifted);
  }

  @Override
  public void close() {
    // Delegates are owned by the ModelRegistry, not by the composite — do not close them here.
  }

  private static ClassifyResponse merge(List<ClassifyResponse> responses) {
    List<ClassifyResult> mergedResults = new ArrayList<>();
    Map<String, Float> mergedScores = new HashMap<>();
    String topLabel = null;
    float topScore = 0f;

    for (ClassifyResponse r : responses) {
      if (r == null) continue;

      if (r.results() != null) {
        mergedResults.addAll(r.results());
      }

      if (r.allScores() != null) {
        for (Map.Entry<String, Float> e : r.allScores().entrySet()) {
          mergedScores.merge(e.getKey(), e.getValue(), Float::max);
        }
      }

      // First non-null top label wins for the composite top label.
      if (topLabel == null && r.topLabel() != null) {
        topLabel = r.topLabel();
      }
      if (r.topScore() > topScore) {
        topScore = r.topScore();
      }
    }

    if (mergedResults.isEmpty() && mergedScores.isEmpty() && topLabel == null) {
      return EMPTY_RESPONSE;
    }

    return new ClassifyResponse(
      topLabel,
      topScore,
      Collections.unmodifiableMap(mergedScores),
      List.copyOf(mergedResults)
    );
  }
}
