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

import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ClassifyResult;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.inference.api.text.EstimatedTokens;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
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
 * across every delegate in one pass, including the character spans needed
 * for the REDACT action.
 *
 * <p>Delegates run concurrently, each receiving every piece of every request in one
 * batch call so a batching delegate scores them together; responses are merged in
 * declaration and document order, so results stay deterministic. If any delegate
 * errors, the composite propagates the error.
 *
 * <p><strong>Single split for the whole composite.</strong> Rather than letting
 * each delegate split a huge input on its own (which would re-split the same text
 * once per delegate), the composite splits the input <em>once</em>, on semantic
 * boundaries, up to {@link #DEFAULT_TOKEN_BUDGET} estimated tokens per chunk, and runs
 * every delegate over the shared chunks, shifting each delegate's per-chunk match
 * offsets back to the original text before merging. Inputs that fit the budget
 * produce a single chunk and each delegate sees the full text. A delegate that does
 * its own finer splitting (e.g. an ONNX classifier with a token budget) still splits
 * its chunk as needed.
 *
 * <p>Result merging:
 * <ul>
 *   <li>{@code results}: union of all delegate {@link ClassifyResult} entries,
 *       preserving the original character spans.</li>
 *   <li>{@code allScores}: merged flat map. If two delegates emit the same
 *       label, the higher score wins.</li>
 *   <li>{@code topLabel}: the first non-{@code null} {@code topLabel} from
 *       any delegate (in delegate order). When no delegate matches, the
 *       composite returns an empty response ({@code topLabel = null},
 *       {@code topScore = 0.0f}, empty scores and results).</li>
 *   <li>{@code topScore}: the maximum score across all delegate top scores.</li>
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
   * Default per-chunk token budget (estimated, see {@link EstimatedTokens}) used
   * when splitting a huge input once for the whole composite. Only large documents
   * are chunked; smaller inputs stay a single chunk.
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
    return rxClassify(request, List.of());
  }

  /** One request is a batch of one, so it takes the same split, fan-out and merge. */
  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request, List<ClassifyLabel> labels) {
    return rxClassifyBatch(List.of(request), labels).map(List::getFirst);
  }

  /**
   * Splits every request once, here, and sends the pieces of all of them to each delegate in one
   * batch call, so a batching delegate (a micro-batcher, a remote endpoint) scores them together.
   * Each piece's spans are shifted back by the piece's start in its request, then the pieces are
   * merged per request and per delegate, and the delegates per request. A request that fits stays
   * one piece with its original text. Request labels reach every delegate; one that ignores them
   * (regex, ONNX) answers as without them.
   */
  @Override
  public Single<List<ClassifyResponse>> rxClassifyBatch(
    List<ClassifyRequest> requests,
    List<ClassifyLabel> labels
  ) {
    return Single.defer(() -> {
      List<List<Chunk>> piecesPerRequest = new ArrayList<>(requests.size());
      List<ClassifyRequest> pieces = new ArrayList<>();
      for (ClassifyRequest request : requests) {
        List<Chunk> chunks = splitter.split(request.text());
        if (chunks.size() <= 1) {
          chunks = List.of(new Chunk(request.text(), 0, request.text().length()));
        }
        piecesPerRequest.add(chunks);
        chunks.forEach(chunk -> pieces.add(new ClassifyRequest(chunk.text())));
      }
      return Observable.fromIterable(delegates)
        .concatMapEager(delegate -> delegate.rxClassifyBatch(pieces, labels).toObservable())
        .toList()
        .map(perDelegate -> regroup(piecesPerRequest, perDelegate));
    });
  }

  /**
   * Maps each delegate's per-piece responses back to their request: shift every piece's spans by
   * its start, merge a request's pieces per delegate, then merge the delegates in declaration
   * order. Pieces are in request then document order in every delegate's list.
   */
  private static List<ClassifyResponse> regroup(
    List<List<Chunk>> piecesPerRequest,
    List<List<ClassifyResponse>> perDelegate
  ) {
    List<ClassifyResponse> merged = new ArrayList<>(piecesPerRequest.size());
    int next = 0;
    for (List<Chunk> chunks : piecesPerRequest) {
      List<ClassifyResponse> byDelegate = new ArrayList<>(perDelegate.size());
      for (List<ClassifyResponse> responses : perDelegate) {
        List<ClassifyResponse> own = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
          own.add(shiftSpans(responses.get(next + i), chunks.get(i).start()));
        }
        byDelegate.add(merge(own));
      }
      merged.add(merge(byDelegate));
      next += chunks.size();
    }
    return merged;
  }

  /**
   * Invoked when an enclosing composite has already split the input: skips the
   * composite's own split and fans the text to each delegate in presplit mode, so the
   * character-budget split happens exactly once at the outermost composite.
   */
  @Override
  public Single<ClassifyResponse> rxClassifyPresplit(ClassifyRequest request) {
    return Observable.fromIterable(delegates)
      .concatMapEager(delegate -> delegate.rxClassifyPresplit(request).toObservable())
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
    // Delegates are owned by the ModelRegistry, not by the composite; do not close them here.
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
      Map.copyOf(mergedScores),
      List.copyOf(mergedResults)
    );
  }
}
