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

/**
 * Rough, tokenizer-free token estimation for engines that have no tokenizer of
 * their own (regex, composite) or none exposed (gliner4j).
 *
 * <p>Estimates ~one token per {@value #CHARS_PER_TOKEN} characters. It is a
 * budgeting heuristic, not an exact count — its purpose is to let every engine
 * express and enforce chunking budgets in the <em>same unit (tokens)</em>, so a
 * composite budget and its delegates' budgets are comparable even when only the
 * model-backed engines have a real tokenizer.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class EstimatedTokens {

  /** Rough subword estimate: characters per token. */
  public static final double CHARS_PER_TOKEN = 3.5;

  private EstimatedTokens() {}

  /** Estimated token count of a piece (0 for {@code null}/empty, otherwise at least 1). */
  public static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
  }

  /**
   * Estimated token cut points: {@link #estimateTokens} synthetic boundaries spread
   * evenly across the piece (≈ every {@value #CHARS_PER_TOKEN} characters). The array
   * length equals {@link #estimateTokens} (so it doubles as the token counter used to
   * measure a piece against a budget), the offsets are strictly increasing, and the
   * last one is the piece length — they are the last-resort cut points the splitter
   * only reaches for a long boundary-free run.
   *
   * <p>Suitable as a {@link RecursiveTextSplitter.TokenBoundaries}.
   */
  public static int[] endOffsets(String text) {
    if (text == null || text.isEmpty()) {
      return new int[0];
    }
    int n = text.length();
    int count = estimateTokens(text);
    int[] ends = new int[count];
    for (int i = 0; i < count; i++) {
      // Evenly spaced so the boundaries stay consistent with the (fractional)
      // chars-per-token estimate; the final boundary always covers the whole piece.
      ends[i] = (int) Math.ceil(((double) (i + 1) * n) / count);
    }
    return ends;
  }
}
