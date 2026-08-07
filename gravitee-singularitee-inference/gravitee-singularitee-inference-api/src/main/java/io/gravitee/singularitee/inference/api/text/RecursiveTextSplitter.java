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
package io.gravitee.singularitee.inference.api.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-budget-aware recursive text splitter.
 *
 * <p>Splits a string into the smallest number of contiguous chunks that each fit within a token
 * budget, preferring the most semantically meaningful boundary available. Separators are tried in
 * priority order (paragraph → line → sentence → clause); a piece that still exceeds the budget is
 * recursively broken on the next separator, and adjacent pieces that fit are greedily merged back
 * together so we emit as few chunks as possible.
 *
 * <p>Crucially it does <strong>not</strong> split on single spaces or characters: when no semantic
 * boundary brings a piece under budget (e.g. a long punctuation-free run), the last resort is to
 * cut on whole <em>token</em> boundaries — never inside a word or a word-piece. Token cut points
 * are supplied by {@link TokenBoundaries}, which the same function uses to measure pieces, so the
 * splitter stays model-agnostic and unit-testable.
 *
 * <p>The returned {@link Chunk}s carry their character offsets into the original text. Chunks never
 * overlap and, concatenated in order (including any separators they retain), span the non-blank
 * content of the input; blank chunks are dropped.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RecursiveTextSplitter {

  /**
   * Supplies the token cut points of a string: the exclusive character end-offset of each token,
   * in ascending order. The array length is the token count, so the same function both measures a
   * piece against the budget and provides the boundaries used by the token-window fallback.
   */
  @FunctionalInterface
  public interface TokenBoundaries {
    int[] endOffsets(String text);
  }

  /**
   * A contiguous slice of the original text with its character offsets.
   *
   * @param text  the chunk text ({@code original.substring(start, end)})
   * @param start inclusive start offset into the original text
   * @param end   exclusive end offset into the original text
   */
  public record Chunk(String text, int start, int end) {}

  /** Semantic boundaries from coarsest (paragraph) to finest (clause). No word/character split. */
  private static final List<String> DEFAULT_SEPARATORS = List.of(
    "\n\n",
    "\n",
    ". ",
    "? ",
    "! ",
    "; ",
    ", "
  );

  private final TokenBoundaries tokens;
  private final int budget;
  private final List<String> separators;

  /**
   * @param tokens supplies token cut points / counts for candidate pieces (content tokens only)
   * @param budget maximum tokens allowed per chunk; must be strictly positive
   */
  public RecursiveTextSplitter(TokenBoundaries tokens, int budget) {
    this(tokens, budget, DEFAULT_SEPARATORS);
  }

  public RecursiveTextSplitter(TokenBoundaries tokens, int budget, List<String> separators) {
    if (budget <= 0) {
      throw new IllegalArgumentException("budget must be > 0, was " + budget);
    }
    this.tokens = tokens;
    this.budget = budget;
    this.separators = List.copyOf(separators);
  }

  /**
   * Splits {@code text} into token-budget-sized chunks. A {@code null}, empty or blank input
   * yields an empty list. An input that already fits yields a single chunk spanning the whole text.
   */
  public List<Chunk> split(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    List<int[]> ranges = splitRange(text, 0, text.length(), 0);
    List<Chunk> chunks = new ArrayList<>(ranges.size());
    for (int[] r : ranges) {
      String slice = text.substring(r[0], r[1]);
      if (!slice.isBlank()) {
        chunks.add(new Chunk(slice, r[0], r[1]));
      }
    }
    return chunks;
  }

  private List<int[]> splitRange(String text, int from, int to, int sepIdx) {
    if (fits(text, from, to)) {
      return List.of(new int[] { from, to });
    }
    if (sepIdx >= separators.size()) {
      // no semantic boundary left (e.g. a long punctuation-free run): cut on token boundaries
      return tokenWindows(text, from, to);
    }

    List<int[]> pieces = splitBySeparator(text, from, to, separators.get(sepIdx));
    if (pieces.size() == 1) {
      // this separator did not appear; try the next, finer one
      return splitRange(text, from, to, sepIdx + 1);
    }

    List<int[]> result = new ArrayList<>();
    List<int[]> pending = new ArrayList<>();
    for (int[] piece : pieces) {
      if (fits(text, piece[0], piece[1])) {
        pending.add(piece);
      } else {
        mergePending(text, pending, result);
        pending.clear();
        result.addAll(splitRange(text, piece[0], piece[1], sepIdx + 1));
      }
    }
    mergePending(text, pending, result);
    return result;
  }

  /**
   * Partitions {@code [from, to)} on {@code sep} with no gaps or overlaps, attaching each separator
   * occurrence to the piece it follows so the pieces concatenate back to the original substring.
   * Returns a single {@code [from, to)} range when the separator does not occur (or is empty).
   */
  private static List<int[]> splitBySeparator(String text, int from, int to, String sep) {
    if (sep.isEmpty()) {
      return List.of(new int[] { from, to });
    }
    List<int[]> ranges = new ArrayList<>();
    int pieceStart = from;
    int i = from;
    int sepLen = sep.length();
    while (i <= to - sepLen) {
      if (text.regionMatches(i, sep, 0, sepLen)) {
        int pieceEnd = i + sepLen;
        ranges.add(new int[] { pieceStart, pieceEnd });
        pieceStart = pieceEnd;
        i = pieceEnd;
      } else {
        i++;
      }
    }
    if (pieceStart < to) {
      ranges.add(new int[] { pieceStart, to });
    }
    if (ranges.isEmpty()) {
      ranges.add(new int[] { from, to });
    }
    return ranges;
  }

  /**
   * Last resort for a piece with no usable semantic boundary: break {@code [from, to)} on whole
   * token boundaries (one range per token, each carrying the whitespace that precedes it) and
   * greedily pack tokens up to the budget. Never cuts inside a token; each emitted range is
   * re-measured so it stays within budget.
   */
  private List<int[]> tokenWindows(String text, int from, int to) {
    int[] ends = tokens.endOffsets(text.substring(from, to));
    if (ends.length <= 1) {
      return List.of(new int[] { from, to });
    }

    List<int[]> perToken = new ArrayList<>(ends.length);
    int prevEnd = 0;
    for (int end : ends) {
      perToken.add(new int[] { from + prevEnd, from + end });
      prevEnd = end;
    }
    if (from + prevEnd < to) {
      // trailing characters after the last token (e.g. whitespace) ride along with the last token
      int[] last = perToken.getLast();
      perToken.set(perToken.size() - 1, new int[] { last[0], to });
    }

    List<int[]> result = new ArrayList<>();
    mergePending(text, perToken, result);
    return result;
  }

  /**
   * Greedily merges the contiguous {@code pending} ranges into as few ranges as possible such that
   * each merged range still fits the budget, appending the result to {@code out}.
   */
  private void mergePending(String text, List<int[]> pending, List<int[]> out) {
    if (pending.isEmpty()) {
      return;
    }
    int start = pending.getFirst()[0];
    int end = pending.getFirst()[1];
    for (int k = 1; k < pending.size(); k++) {
      int[] next = pending.get(k);
      if (fits(text, start, next[1])) {
        end = next[1];
      } else {
        out.add(new int[] { start, end });
        start = next[0];
        end = next[1];
      }
    }
    out.add(new int[] { start, end });
  }

  private boolean fits(String text, int from, int to) {
    return count(text.substring(from, to)) <= budget;
  }

  private int count(String text) {
    return tokens.endOffsets(text).length;
  }
}
