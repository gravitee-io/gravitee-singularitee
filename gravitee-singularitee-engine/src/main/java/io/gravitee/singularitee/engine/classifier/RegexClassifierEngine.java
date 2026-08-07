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
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A pattern-based {@link ClassifierEngine} that matches input text against a
 * list of regular expressions, tagging each match with its entity type
 * (e.g. {@code "SSN"}, {@code "EMAIL"}, {@code "PHONE"}).
 *
 * <p>Runs fully in-process — no native library, no GPU, no remote call. Safe
 * to instantiate on both server and client sides. Deterministic: every match
 * returns a {@link ClassifyResult} with score {@code 1.0f} and the character
 * span offsets of the match.
 *
 * <p>Patterns are compiled into a single combined alternation regex with
 * positional named groups ({@code (?<P0>…)|(?<P1>…)|…}) at construction time.
 * The group that fired is used to look up the corresponding entity type.
 *
 * <p>Very large inputs are split on semantic boundaries into contiguous,
 * non-overlapping chunks that each fit an (estimated) token budget (see
 * {@link #DEFAULT_TOKEN_BUDGET}) and matched chunk-by-chunk, with each match's
 * offsets shifted back to the original text. This bounds the work a single
 * {@link java.util.regex.Matcher} pass can do on a huge document. Inputs within
 * budget are matched in a single pass, exactly as before.
 *
 * <p>Response shape:
 * <ul>
 *   <li>On match — {@code topLabel =} first matched entity type,
 *       {@code topScore = 1.0f}, {@code allScores} contains one {@code 1.0}
 *       entry per distinct matched entity type, {@code results} contains one
 *       {@link ClassifyResult} per match with {@code label =} entity type,
 *       {@code token =} matched text, and {@code start/end} character offsets.</li>
 *   <li>No match — empty response: {@code topLabel = null},
 *       {@code topScore = 0.0f}, {@code allScores} empty, {@code results} empty.
 *       The guard step treats this as "not triggered" and continues.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RegexClassifierEngine implements ClassifierEngine {

  // No-match response. topLabel is null (consistent with CompositeClassifierEngine
  // and the ClassifyResponse "not triggered" convention); the proto mapping layer
  // coalesces a null top label to the empty string.
  private static final ClassifyResponse EMPTY_RESPONSE = new ClassifyResponse(
    null,
    0.0f,
    Map.of(),
    List.of()
  );

  /** A single regex pattern tagged with the entity type it identifies. */
  public record PatternEntry(String pattern, String entityType) {
    public PatternEntry {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(entityType, "entityType");
    }
  }

  /**
   * Default per-chunk token budget (estimated — see {@link EstimatedTokens}).
   * Inputs longer than this are split on semantic boundaries before matching so a
   * single pathological pattern can never backtrack over an unbounded run of text
   * (or hold the whole input in one matcher). Only genuinely large documents are
   * chunked. 4096 tokens ≈ 14k characters at {@value EstimatedTokens#CHARS_PER_TOKEN}
   * chars/token.
   */
  public static final int DEFAULT_TOKEN_BUDGET = 4096;

  private final Pattern combinedPattern;

  /**
   * Indexed by group position (P0 → entityTypes[0], …). Empty when no valid
   * patterns were supplied.
   */
  private final String[] entityTypes;

  /**
   * Bounds the work done per {@link Matcher} pass: a huge input is broken into
   * contiguous, non-overlapping chunks that each fit {@link #DEFAULT_TOKEN_BUDGET}
   * estimated tokens (or the budget passed to the constructor). Each chunk is
   * matched independently and its match offsets are shifted back to the original text.
   */
  private final RecursiveTextSplitter splitter;

  /**
   * Creates a new regex engine from the given pattern list, using the
   * {@link #DEFAULT_TOKEN_BUDGET default token budget}.
   *
   * @param patterns the regex patterns with their entity types (must not be {@code null})
   */
  public RegexClassifierEngine(List<PatternEntry> patterns) {
    this(patterns, DEFAULT_TOKEN_BUDGET);
  }

  /**
   * Creates a new regex engine from the given pattern list.
   *
   * <p>Each entry is wrapped in a positional named group and joined with
   * alternation. An empty or {@code null} list produces an engine that
   * always returns the empty (no-match) response.
   *
   * <p>Inputs longer than {@code tokenBudget} estimated tokens (see
   * {@link EstimatedTokens}) are split on semantic boundaries
   * (paragraph → line → sentence → clause) before matching to bound per-pass work
   * on very large documents. Chunks never overlap, so a match that straddles a
   * chunk boundary can be missed; the splitter prefers coarse semantic boundaries
   * precisely to make that vanishingly rare for realistic text, and no chunking
   * happens at all for inputs within budget.
   *
   * @param patterns    the regex patterns with their entity types (must not be {@code null})
   * @param tokenBudget maximum estimated tokens matched in a single pass; must be {@code > 0}
   */
  public RegexClassifierEngine(List<PatternEntry> patterns, int tokenBudget) {
    Objects.requireNonNull(patterns, "patterns");
    this.splitter = new RecursiveTextSplitter(EstimatedTokens::endOffsets, tokenBudget);
    List<PatternEntry> valid = patterns
      .stream()
      .filter(p -> p != null && !p.pattern().isBlank())
      .toList();
    if (valid.isEmpty()) {
      this.combinedPattern = null;
      this.entityTypes = new String[0];
      return;
    }
    String alternation = IntStream.range(0, valid.size())
      .mapToObj(i -> "(?<P" + i + ">" + valid.get(i).pattern() + ")")
      .collect(Collectors.joining("|"));
    this.combinedPattern = Pattern.compile(alternation);
    this.entityTypes = valid.stream().map(PatternEntry::entityType).toArray(String[]::new);
  }

  @Override
  public String task() {
    return ModelTasks.TOKEN_CLASSIFICATION;
  }

  @Override
  public Single<ClassifyResponse> rxClassify(ClassifyRequest request) {
    return Single.fromCallable(() -> classify(request.text()));
  }

  /**
   * The input is already within a caller's character budget (e.g. a composite
   * has split once for the whole model), so we skip our own splitter entirely
   * and match the text in a single pass.
   */
  @Override
  public Single<ClassifyResponse> rxClassifyPresplit(ClassifyRequest request) {
    return Single.fromCallable(() -> classifyWithoutSplit(request.text()));
  }

  @Override
  public void close() {
    // no resources to release
  }

  private ClassifyResponse classify(String text) {
    if (text == null || text.isEmpty() || combinedPattern == null) {
      return EMPTY_RESPONSE;
    }
    // Split only bounds huge inputs; a within-budget input yields a single
    // chunk spanning the whole text, so the common path stays a single pass.
    List<Chunk> chunks = splitter.split(text);
    if (chunks.isEmpty()) {
      return EMPTY_RESPONSE;
    }
    Accumulator acc = new Accumulator();
    for (Chunk chunk : chunks) {
      // Match each chunk in isolation, then shift offsets back to the original
      // text by the chunk's start so spans stay valid against the full input.
      // Chunks are visited in document order, so the first match found is still
      // the first match in the original text (topLabel semantics preserved).
      matchInto(chunk.text(), chunk.start(), acc);
    }
    return acc.toResponse();
  }

  private ClassifyResponse classifyWithoutSplit(String text) {
    if (text == null || text.isEmpty() || combinedPattern == null) {
      return EMPTY_RESPONSE;
    }
    Accumulator acc = new Accumulator();
    matchInto(text, 0, acc);
    return acc.toResponse();
  }

  /** Runs the combined pattern over {@code text} and appends every match, shifted by {@code offset}. */
  private void matchInto(String text, int offset, Accumulator acc) {
    Matcher matcher = combinedPattern.matcher(text);
    while (matcher.find()) {
      int groupIndex = findFiringGroup(matcher);
      if (groupIndex < 0) continue; // should not happen, but be defensive
      String label = entityTypes[groupIndex];
      acc.add(
        new ClassifyResult(
          label,
          1.0f,
          matcher.group(),
          offset + matcher.start(),
          offset + matcher.end()
        )
      );
    }
  }

  /** Collects matches across one or more pieces into a single {@link ClassifyResponse}. */
  private static final class Accumulator {

    private final List<ClassifyResult> results = new ArrayList<>();
    private final Map<String, Float> allScores = new HashMap<>();
    private String firstLabel = null;

    void add(ClassifyResult r) {
      results.add(r);
      allScores.put(r.label(), 1.0f);
      if (firstLabel == null) firstLabel = r.label();
    }

    ClassifyResponse toResponse() {
      if (results.isEmpty()) {
        return EMPTY_RESPONSE;
      }
      return new ClassifyResponse(
        firstLabel,
        1.0f,
        Collections.unmodifiableMap(allScores),
        List.copyOf(results)
      );
    }
  }

  /** Returns the positional index of the named group that fired, or {@code -1}. */
  private int findFiringGroup(Matcher matcher) {
    for (int i = 0; i < entityTypes.length; i++) {
      if (matcher.group("P" + i) != null) return i;
    }
    return -1;
  }
}
