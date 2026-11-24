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
package io.gravitee.singularitee.e2e.spans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.gravitee.singularitee.e2e.E2e;
import io.gravitee.singularitee.e2e.SingulariteeServer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What {@code POST /v1/classify} promises about a span, whatever engine found it, checked
 * against a real server:
 *
 * <ul>
 *   <li>{@code start} and {@code end} index the input in UTF-16 code units, the unit of a Java
 *       or JavaScript string, so {@code input.substring(start, end)} is the entity;
 *   <li>{@code token}, when present, is that substring;
 *   <li>a guard redacting on those spans masks the entity and nothing else.
 * </ul>
 *
 * UTF-16 and code-point offsets agree until a character outside the Basic Multilingual Plane
 * (an emoji, some ideographs) comes before the span, so every check runs behind such a prefix,
 * with plain and accented prefixes as controls.
 *
 * <p>One subclass per backend names its workspace and models. A backend is skipped unless its
 * models are in the models directory or {@code SINGULARITEE_E2E} names it; see {@link E2e}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SpanOffsetsContract {

  static final String EMAIL = "john@acme.io";
  static final String BEFORE = "Please send the invoice to ";
  static final String AFTER = " today.";
  static final String SENTENCE = BEFORE + EMAIL + AFTER;

  private SingulariteeServer server;
  private String skip;

  /** The key naming this backend in {@code SINGULARITEE_E2E}. */
  abstract String backend();

  /** The workspace under {@code src/test/resources/workspaces}. */
  abstract String workspace();

  /** HuggingFace repositories the workspace downloads; empty when it needs none. */
  abstract List<String> repositories();

  /** Id of the token classifier under test. */
  String model() {
    return "pii";
  }

  /** Id of the pipeline redacting on that classifier, or {@code null} when there is none. */
  String redactPipeline() {
    return "redact";
  }

  /**
   * Filler sentences put before the entity in the long-input check: enough to cross the
   * backend's own split, so offsets shifted back from a chunk are covered too.
   */
  int longInputSentences() {
    return 800;
  }

  /** Why this backend does not run, or {@code null} when it does. */
  String skipReason() {
    return E2e.skipReason(backend(), repositories());
  }

  /** Starts the server this backend is tested on. */
  SingulariteeServer startServer() throws Exception {
    return SingulariteeServer.start(resource("workspaces/" + workspace()), List.of(model()));
  }

  @BeforeAll
  void start() throws Exception {
    skip = skipReason();
    if (skip == null) {
      server = startServer();
    }
  }

  /** Per test, so a backend that does not run is counted as skipped, with its reason. */
  @BeforeEach
  void runsOnlyWhenSelected() {
    assumeTrue(skip == null, skip);
  }

  @AfterAll
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  static Stream<Arguments> prefixes() {
    return Stream.of(
      Arguments.of("no prefix", ""),
      Arguments.of("accented prefix, all in the BMP", "Café ☺ "),
      Arguments.of("one emoji", "😀 "),
      Arguments.of("three emojis", "😀😀🎉 "),
      Arguments.of("ideograph outside the BMP", "𠜎 "),
      Arguments.of("emoji with a modifier and a joiner", "👩🏽‍💻 ")
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prefixes")
  void offsets_locate_the_entity_in_utf16_code_units(String name, String prefix) throws Exception {
    String input = prefix + SENTENCE;
    int from = input.indexOf(EMAIL);
    int to = from + EMAIL.length();

    List<Span> entities = entities(input);

    assertLocated(entities, from, to);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prefixes")
  void token_is_the_input_between_start_and_end(String name, String prefix) throws Exception {
    String input = prefix + SENTENCE;

    List<Span> withToken = spans(input)
      .stream()
      .filter(span -> span.token() != null)
      .toList();

    assertThat(withToken).isNotEmpty();
    assertThat(withToken).allSatisfy(span ->
      assertThat(span.token())
        .as("token of %s", span)
        .isEqualTo(input.substring(span.start(), span.end()))
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prefixes")
  void spans_carry_an_end_within_the_input(String name, String prefix) throws Exception {
    String input = prefix + SENTENCE;

    JsonNode spans = server.classify(model(), input).path("spans");

    assertThat(spans).isNotEmpty();
    assertThat(spans).allSatisfy(span -> {
      assertThat(span.has("end")).as("span has an end: %s", span).isTrue();
      assertThat(span.path("start").asInt()).isBetween(0, span.path("end").asInt() - 1);
      assertThat(span.path("end").asInt()).isLessThanOrEqualTo(input.length());
    });
  }

  @Test
  void offsets_survive_the_split_of_a_long_input() throws Exception {
    String input = "Nothing to report on this line 😀.\n".repeat(longInputSentences()) + SENTENCE;
    int from = input.indexOf(EMAIL);
    int to = from + EMAIL.length();

    List<Span> near = entities(input)
      .stream()
      .filter(span -> span.end() > from - 40)
      .toList();

    assertLocated(near, from, to);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("prefixes")
  void guard_redacts_the_entity_and_nothing_else(String name, String prefix) throws Exception {
    assumeTrue(redactPipeline() != null, "no redact pipeline for this backend");
    assumeTrue(
      server.publishes(redactPipeline()),
      "the server publishes no '" + redactPipeline() + "' pipeline"
    );

    String redacted = redact(prefix + SENTENCE);

    // The space before the entity may go with it, see offsets_locate_the_entity.
    assertThat(redacted).isIn(
      prefix + BEFORE + "************" + AFTER,
      prefix + BEFORE.stripTrailing() + "************" + AFTER
    );
  }

  /** A span of {@code /v1/classify}; {@code token} is {@code null} when the answer has none. */
  record Span(String label, String token, int start, int end) {}

  private List<Span> spans(String input) throws Exception {
    List<Span> spans = new ArrayList<>();
    for (JsonNode span : server.classify(model(), input).path("spans")) {
      spans.add(
        new Span(
          span.path("label").asText(),
          span.hasNonNull("token") ? span.get("token").asText() : null,
          span.path("start").asInt(),
          span.path("end").asInt()
        )
      );
    }
    return spans;
  }

  /** Spans naming an entity: a BIO tagger also reports the tokens outside any entity, as O. */
  private List<Span> entities(String input) throws Exception {
    return spans(input)
      .stream()
      .filter(span -> !span.label().equals("O") && !span.label().startsWith("O-"))
      .toList();
  }

  /**
   * The spans cover {@code [from, to)} whole, and none of those touching it reaches outside,
   * but for the space a tokenizer may attach before a word. Spans elsewhere are other
   * entities the model found, which is its business.
   */
  private static void assertLocated(List<Span> spans, int from, int to) {
    assertThat(uncovered(spans, from, to))
      .as("part of the entity no span covers; spans: %s", spans)
      .isEmpty();
    assertThat(spans)
      .filteredOn(span -> span.start() < to && span.end() > from - 1)
      .as("spans on the entity stay within it")
      .allSatisfy(span -> {
        assertThat(span.start()).isGreaterThanOrEqualTo(from - 1);
        assertThat(span.end()).isLessThanOrEqualTo(to);
      });
  }

  /** The characters of {@code [from, to)} that no span covers. */
  private static List<Integer> uncovered(List<Span> spans, int from, int to) {
    BitSet covered = new BitSet(to);
    spans.forEach(span -> covered.set(Math.max(0, span.start()), Math.max(0, span.end())));
    List<Integer> missing = new ArrayList<>();
    for (int i = from; i < to; i++) {
      if (!covered.get(i)) missing.add(i);
    }
    return missing;
  }

  /**
   * The text the redact pipeline's guard produced. The pipeline has no generation step: it ends
   * on a step that always rejects with the redacted text as its message, which the Responses
   * API reports in {@code error.message}.
   */
  private String redact(String input) throws Exception {
    JsonNode response = server.respond(redactPipeline(), input);
    assertThat(response.path("status").asText()).isEqualTo("failed");
    return response.path("error").path("message").asText();
  }

  private static Path resource(String name) throws URISyntaxException {
    var url = SpanOffsetsContract.class.getClassLoader().getResource(name);
    if (url == null) {
      throw new IllegalStateException("Missing test resource " + name);
    }
    return Path.of(url.toURI());
  }
}
