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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Turns a span-extracting LLM's answer into character spans over the input.
 *
 * <p>The model answers with a list of items, each a label and a value copied from the input,
 * in order of appearance and without offsets: by default
 * {@code {"spans":[{"label":"...","text":"..."}]}}, any other field names with a {@link Format}.
 * {@link #parse} reads that answer; {@link #locate} finds each item in the input; {@link #allOccurrences}
 * extends a value found once to its other whole-word occurrences.
 *
 * <p>Offsets are Java {@code String} indices, the unit every other classifier and the guard's
 * redaction use.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlmSpans {

  private static final JsonFactory JSON = new JsonFactory();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String THINK_CLOSE = "</think>";

  /**
   * One item of the model's answer: a label and the text it copied from the input.
   *
   * @param from   where the item's object opens in the answer, or {@code -1} when unknown
   * @param to     end of its {@code label} and {@code text} values in the answer
   * @param score     confidence carried over to the item's spans
   * @param metadata  the item's other scalar fields, such as {@code ref}, as strings
   */
  public record Item(
    String label,
    String text,
    int from,
    int to,
    float score,
    Map<String, String> metadata
  ) {
    public Item {
      metadata = ordered(metadata);
    }

    public Item(String label, String text) {
      this(label, text, -1, -1, 1.0f, Map.of());
    }

    public Item withScore(float score) {
      return new Item(label, text, from, to, score, metadata);
    }
  }

  /**
   * A located span: {@code [start, end)} in the input, with its label, covered text, score and
   * the metadata of the item it came from.
   */
  public record Span(
    String label,
    String text,
    int start,
    int end,
    float score,
    Map<String, String> metadata
  ) {
    public Span {
      metadata = ordered(metadata);
    }

    public Span(String label, String text, int start, int end) {
      this(label, text, start, end, 1.0f, Map.of());
    }

    public Span shifted(int offset) {
      return new Span(label, text, start + offset, end + offset, score, metadata);
    }
  }

  /** The located spans, sorted by start and never overlapping, and how many items were not found. */
  public record Located(List<Span> spans, int missing) {}

  /**
   * Where an answer keeps its items and, in each item, the label and the value copied from the
   * input. The default reads {@code {"spans":[{"label":"...","text":"..."}]}}.
   *
   * @param spans     key of the items array in the answer object; blank when the answer is the
   *                  array itself
   * @param label     field of an item holding its label
   * @param text      field of an item holding the value copied from the input
   * @param metadata  other string fields an item may carry, such as {@code ref}. The schema
   *                  declares them, since a constrained answer can only write declared fields;
   *                  parsing keeps every other scalar field either way
   */
  public record Format(String spans, String label, String text, List<String> metadata) {
    public static final Format DEFAULT = new Format("spans", "label", "text", List.of());

    public Format {
      spans = spans == null ? "" : spans.strip();
      label = field(label, "label");
      text = field(text, "text");
      if (label.equals(text)) {
        throw new IllegalArgumentException("label and text must be different fields");
      }
      metadata = metadata == null
        ? List.of()
        : metadata
          .stream()
          .filter(m -> m != null && !m.isBlank())
          .map(String::strip)
          .distinct()
          .toList();
      String l = label;
      String t = text;
      if (metadata.stream().anyMatch(m -> m.equals(l) || m.equals(t))) {
        throw new IllegalArgumentException("metadata repeats the label or the text field");
      }
    }

    /** Whether the answer is the items array itself rather than an object holding it. */
    public boolean bareArray() {
      return spans.isEmpty();
    }

    /**
     * The JSON Schema of an answer in this format: items with a string label, a non-empty
     * string value and the optional metadata strings, in that order, and nothing else. A
     * backend decoding against it can only write an answer {@link #parse} reads.
     */
    public String jsonSchema() {
      ObjectNode item = MAPPER.createObjectNode().put("type", "object");
      ObjectNode properties = item.putObject("properties");
      properties.putObject(label).put("type", "string");
      properties.putObject(text).put("type", "string").put("minLength", 1);
      metadata.forEach(m -> properties.putObject(m).put("type", "string"));
      item.putArray("required").add(label).add(text);
      item.put("additionalProperties", false);
      ObjectNode array = MAPPER.createObjectNode().put("type", "array");
      array.set("items", item);
      if (bareArray()) return array.toString();
      ObjectNode root = MAPPER.createObjectNode().put("type", "object");
      root.putObject("properties").set(spans, array);
      root.putArray("required").add(spans);
      root.put("additionalProperties", false);
      return root.toString();
    }

    private static String field(String name, String what) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException(what + " field is required");
      }
      return name.strip();
    }
  }

  /** An unmodifiable copy keeping the fields in the order the model wrote them. */
  private static Map<String, String> ordered(Map<String, String> metadata) {
    return metadata == null || metadata.isEmpty()
      ? Map.of()
      : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  private LlmSpans() {}

  /** Reads the items of an answer in the {@link Format#DEFAULT default format}. */
  public static Optional<List<Item>> parse(String answer) {
    return parse(answer, Format.DEFAULT);
  }

  /**
   * Reads the items of an answer. Anything up to a closing reasoning tag is dropped, then the
   * first JSON object is read and its {@code format.spans()} array kept, or, for a bare-array
   * format, the first JSON array. Items without a textual label and value are skipped. Each
   * item records where it sits in the answer, from its opening brace to the end of its label or
   * value, whichever is last, so the tokens that produced it can be found. Its other scalar
   * fields ({@code ref} and the like) become its metadata.
   *
   * @return the items, or empty when the answer holds no such object
   */
  public static Optional<List<Item>> parse(String answer, Format format) {
    if (answer == null) return Optional.empty();
    int think = answer.lastIndexOf(THINK_CLOSE);
    int after = think < 0 ? 0 : think + THINK_CLOSE.length();
    int open = format.bareArray() ? firstValue(answer, after) : answer.indexOf('{', after);
    if (open < 0) return Optional.empty();
    try (JsonParser p = JSON.createParser(answer.substring(open))) {
      if (format.bareArray()) {
        if (p.nextToken() != JsonToken.START_ARRAY) return Optional.empty();
        return Optional.of(items(p, open, format));
      }
      if (p.nextToken() != JsonToken.START_OBJECT) return Optional.empty();
      List<Item> items = null;
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String field = p.currentName();
        if (p.nextToken() == JsonToken.START_ARRAY && format.spans().equals(field)) {
          items = items(p, open, format);
        } else {
          p.skipChildren();
        }
      }
      return Optional.ofNullable(items);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Where the first JSON object or array opens from {@code from}, or {@code -1}. */
  private static int firstValue(String answer, int from) {
    int object = answer.indexOf('{', from);
    int array = answer.indexOf('[', from);
    if (object < 0) return array;
    if (array < 0) return object;
    return Math.min(object, array);
  }

  /** Reads an items array, the parser on its opening bracket. */
  private static List<Item> items(JsonParser p, int base, Format format) throws IOException {
    List<Item> items = new ArrayList<>();
    JsonToken t;
    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
      if (t != JsonToken.START_OBJECT) {
        p.skipChildren();
        continue;
      }
      int from = base + (int) p.currentTokenLocation().getCharOffset();
      int to = from;
      String label = null;
      String text = null;
      Map<String, String> metadata = new LinkedHashMap<>();
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String field = p.currentName();
        JsonToken value = p.nextToken();
        boolean wanted = format.label().equals(field) || format.text().equals(field);
        if (value == JsonToken.VALUE_STRING && wanted) {
          // getText() completes the token, so the location then sits after its closing quote.
          String v = p.getText();
          if (format.label().equals(field)) label = v;
          else text = v;
          to = Math.max(to, base + (int) p.currentLocation().getCharOffset());
        } else if (value.isScalarValue() && value != JsonToken.VALUE_NULL && !wanted) {
          metadata.put(field, p.getText());
        } else {
          p.skipChildren();
        }
      }
      if (label != null && text != null) {
        items.add(new Item(label, text, from, to, 1.0f, metadata));
      }
    }
    return items;
  }

  /**
   * Places each item in {@code text}. Items come in order of appearance, so each is searched
   * from the end of the previous match, then from the start; case-sensitively, then ignoring
   * case; preferring a whole-word occurrence; never overlapping a span already placed. An item
   * found nowhere counts as missing.
   */
  public static Located locate(String text, List<Item> items) {
    List<Span> placed = new ArrayList<>(items.size());
    int missing = 0;
    int cursor = 0;
    for (Item item : items) {
      String needle = item.text().strip();
      if (needle.isEmpty()) continue;
      int found = -1;
      search: for (boolean ignoreCase : new boolean[] { false, true }) {
        for (int from : new int[] { cursor, 0 }) {
          for (boolean wholeWord : new boolean[] { true, false }) {
            found = findFree(text, needle, from, ignoreCase, wholeWord, placed);
            if (found >= 0) break search;
          }
        }
      }
      if (found < 0) {
        missing++;
        continue;
      }
      int end = found + needle.length();
      placed.add(
        new Span(
          item.label(),
          text.substring(found, end),
          found,
          end,
          item.score(),
          item.metadata()
        )
      );
      cursor = end;
    }
    placed.sort(Comparator.comparingInt(Span::start));
    return new Located(List.copyOf(placed), missing);
  }

  /**
   * Adds the other whole-word occurrences of each span's value that no span covers yet, so a
   * name listed once is caught everywhere. Values shorter than {@code minLength} are left alone,
   * since a short name is often also a common word; {@code minLength <= 0} disables the pass.
   * An added occurrence takes the score and metadata of the span it repeats.
   */
  public static List<Span> allOccurrences(String text, List<Span> spans, int minLength) {
    if (minLength <= 0 || spans.isEmpty()) return spans;
    List<Span> out = new ArrayList<>(spans);
    for (Span span : spans) {
      String value = span.text();
      if (value.length() < minLength) continue;
      int k = text.indexOf(value);
      while (k >= 0) {
        int end = k + value.length();
        if (isWholeWord(text, value, k, end, false) && isFree(out, k, end)) {
          out.add(new Span(span.label(), value, k, end, span.score(), span.metadata()));
        }
        k = text.indexOf(value, k + 1);
      }
    }
    out.sort(Comparator.comparingInt(Span::start));
    return List.copyOf(out);
  }

  /** Keeps the spans whose label {@code keep} accepts. */
  public static List<Span> filter(List<Span> spans, Predicate<String> keep) {
    return spans
      .stream()
      .filter(s -> keep.test(s.label()))
      .toList();
  }

  private static int findFree(
    String hay,
    String needle,
    int from,
    boolean ignoreCase,
    boolean wholeWord,
    List<Span> placed
  ) {
    int n = needle.length();
    for (int k = Math.max(0, from); k + n <= hay.length(); k++) {
      if (!hay.regionMatches(ignoreCase, k, needle, 0, n)) continue;
      int end = k + n;
      if (isFree(placed, k, end) && (!wholeWord || isWholeWord(hay, needle, k, end, true))) {
        return k;
      }
    }
    return -1;
  }

  /**
   * Whether {@code [start, end)} is not glued to a letter or digit on either side. With
   * {@code edgesOnly}, a side is checked only where the needle itself starts or ends with a
   * letter or digit, so a value such as {@code "+47 912"} still matches after a word.
   */
  private static boolean isWholeWord(
    String hay,
    String needle,
    int start,
    int end,
    boolean edgesOnly
  ) {
    boolean checkLeft = !edgesOnly || Character.isLetterOrDigit(needle.charAt(0));
    boolean checkRight =
      !edgesOnly || Character.isLetterOrDigit(needle.charAt(needle.length() - 1));
    boolean left = !checkLeft || start == 0 || !Character.isLetterOrDigit(hay.charAt(start - 1));
    boolean right =
      !checkRight || end == hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
    return left && right;
  }

  private static boolean isFree(List<Span> placed, int start, int end) {
    for (Span p : placed) {
      if (start < p.end() && p.start() < end) return false;
    }
    return true;
  }
}
