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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.Chunk;
import io.gravitee.singularitee.inference.api.text.RecursiveTextSplitter.TokenBoundaries;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecursiveTextSplitter}. A "token" is faked as a whitespace-delimited
 * word so budgets are exact and model-independent, and the token-boundary fallback is observable.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class RecursiveTextSplitterTest {

  private static final Pattern WORD = Pattern.compile("\\S+");

  /** End char-offset of each whitespace-delimited word (so cuts can only land on word boundaries). */
  private static final TokenBoundaries BY_WORD = text -> {
    List<Integer> ends = new ArrayList<>();
    Matcher matcher = WORD.matcher(text);
    while (matcher.find()) {
      ends.add(matcher.end());
    }
    return ends.stream().mapToInt(Integer::intValue).toArray();
  };

  private static RecursiveTextSplitter splitter(int budget) {
    return new RecursiveTextSplitter(BY_WORD, budget);
  }

  private static int wordCount(String s) {
    return BY_WORD.endOffsets(s).length;
  }

  private static void assertInvariants(String original, List<Chunk> chunks, int budget) {
    int previousEnd = 0;
    for (Chunk c : chunks) {
      assertTrue(
        wordCount(c.text()) <= budget,
        () -> "chunk over budget (" + wordCount(c.text()) + " > " + budget + "): " + c
      );
      assertEquals(c.text(), original.substring(c.start(), c.end()), "offset mismatch: " + c);
      assertTrue(c.start() >= previousEnd, "overlap/disorder at " + c);
      assertFalse(c.text().isBlank(), "blank chunk: " + c);
      previousEnd = c.end();
    }
    // chunks, concatenated, reproduce the original content (ignoring whitespace that may be dropped)
    String joined = chunks.stream().map(Chunk::text).reduce("", String::concat);
    assertEquals(original.replaceAll("\\s+", ""), joined.replaceAll("\\s+", ""));
  }

  @Test
  void passes_short_input_through_as_a_single_chunk() {
    var chunks = splitter(100).split("Hello world.");
    assertEquals(1, chunks.size());
    assertEquals(new Chunk("Hello world.", 0, 12), chunks.getFirst());
  }

  @Test
  void returns_no_chunks_for_null_empty_or_blank_input() {
    assertEquals(List.of(), splitter(10).split(null));
    assertEquals(List.of(), splitter(10).split(""));
    assertEquals(List.of(), splitter(10).split("   \n\t  "));
  }

  @Test
  void splits_on_paragraph_boundary_first() {
    String text = "alpha beta\n\ngamma delta";
    var chunks = splitter(2).split(text);
    assertEquals(
      List.of("alpha beta\n\n", "gamma delta"),
      chunks.stream().map(Chunk::text).toList()
    );
    assertInvariants(text, chunks, 2);
  }

  @Test
  void splits_on_sentence_boundary_when_paragraph_does_not_fit() {
    String text = "Sentence one. Sentence two. Sentence three.";
    var chunks = splitter(2).split(text);
    assertInvariants(text, chunks, 2);
    assertEquals(3, chunks.size());
    assertTrue(chunks.get(0).text().startsWith("Sentence one."));
    assertTrue(chunks.get(2).text().endsWith("three."));
  }

  @Test
  void falls_back_to_whole_token_boundaries_when_there_is_no_punctuation() {
    String text = "alpha beta gamma delta epsilon"; // 5 words, no punctuation at all
    var chunks = splitter(2).split(text);
    assertInvariants(text, chunks, 2);
    // packed up to 2 words per window, cut only on word (token) boundaries — never inside a word
    assertEquals(3, chunks.size());
  }

  @Test
  void never_splits_inside_a_token() {
    String longWord = "supercalifragilisticexpialidocious";
    String text = "tiny " + longWord + " tiny";
    var chunks = splitter(1).split(text);
    assertInvariants(text, chunks, 1);
    // even at budget 1 the long word stays a single intact chunk — it is one token
    assertTrue(chunks.stream().anyMatch(c -> c.text().contains(longWord)));
  }

  @Test
  void covers_all_content_with_correct_offsets_on_mixed_text() {
    String text =
      "Intro paragraph here.\n\n" +
      "Second paragraph, with a clause. And another sentence.\n" +
      "trailing words with no final punctuation and quite a few of them";
    var chunks = splitter(4).split(text);
    assertInvariants(text, chunks, 4);
    assertTrue(chunks.size() > 1);
  }

  @Test
  void rejects_a_non_positive_budget() {
    assertThrows(IllegalArgumentException.class, () -> splitter(0));
    assertThrows(IllegalArgumentException.class, () -> splitter(-5));
  }
}
