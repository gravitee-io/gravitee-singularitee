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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.singularitee.engine.classifier.LlmSpans.Item;
import io.gravitee.singularitee.engine.classifier.LlmSpans.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LlmSpansTest {

  @Nested
  class Parse {

    private static List<Item> items(String answer) {
      return LlmSpans.parse(answer).orElseThrow();
    }

    @Test
    void reads_label_and_text_of_each_span() {
      var items = items(
        "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari Nordmann\",\"ref\":\"p1\"},{\"label\":\"PHONE\",\"text\":\"912 34 567\",\"ref\":\"p1\"}]}"
      );
      assertThat(items)
        .extracting(Item::label, Item::text, Item::score)
        .containsExactly(
          tuple("PERSON", "Kari Nordmann", 1.0f),
          tuple("PHONE", "912 34 567", 1.0f)
        );
    }

    @Test
    void keeps_the_other_scalar_fields_as_metadata() {
      var item = items(
        "{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\",\"ref\":\"p1\",\"n\":2,\"x\":null,\"o\":{\"a\":1}}]}"
      ).getFirst();
      assertThat(item.metadata()).containsExactly(Map.entry("ref", "p1"), Map.entry("n", "2"));
    }

    @Test
    void records_each_item_from_its_brace_to_its_last_value() {
      String prefix = "<think>x</think>\n";
      String first = "{\"label\":\"PERSON\",\"text\":\"Kj\\u00f8\\\"lle\"";
      String answer = prefix + "{\"spans\":[" + first + ",\"ref\":\"p1\"}]}";
      var item = items(answer).getFirst();

      assertThat(item.text()).isEqualTo("Kj\u00f8\"lle");
      int from = answer.indexOf("{\"label\"");
      assertThat(item.from()).isEqualTo(from);
      assertThat(item.to()).isEqualTo(from + first.length());
    }

    @Test
    void the_range_ends_at_the_label_when_it_comes_last() {
      String answer = "{\"spans\":[{\"text\":\"Kari\",\"label\":\"PERSON\"}]}";
      var item = items(answer).getFirst();
      assertThat(answer.substring(item.from(), item.to())).isEqualTo(
        "{\"text\":\"Kari\",\"label\":\"PERSON\""
      );
    }

    @Test
    void empty_spans_is_a_valid_answer() {
      assertThat(LlmSpans.parse("{\"spans\":[]}")).contains(List.of());
    }

    @Test
    void drops_reasoning_and_surrounding_prose() {
      var items = items(
        "<think>\nthe user {maybe}\n</think>\n\nHere: {\"spans\":[{\"label\":\"EMAIL\",\"text\":\"a@b.no\"}]} done"
      );
      assertThat(items).extracting(Item::text).containsExactly("a@b.no");
    }

    @Test
    void skips_malformed_items() {
      var items = items(
        "{\"spans\":[{\"label\":\"PERSON\"},{\"text\":\"x\"},{\"label\":1,\"text\":\"y\"},{\"label\":\"AGE\",\"text\":\"42\"}]}"
      );
      assertThat(items).extracting(Item::label, Item::text).containsExactly(tuple("AGE", "42"));
    }

    @Test
    void not_span_json_is_empty() {
      assertThat(LlmSpans.parse(null)).isEmpty();
      assertThat(LlmSpans.parse("no personal data")).isEmpty();
      assertThat(LlmSpans.parse("{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Ka")).isEmpty();
      assertThat(LlmSpans.parse("{\"entities\":[]}")).isEmpty();
    }
  }

  @Nested
  class Formats {

    private static final LlmSpans.Format ENTITIES = new LlmSpans.Format(
      "entities",
      "type",
      "value",
      List.of("person")
    );

    @Test
    void reads_the_fields_the_format_names() {
      var items = LlmSpans.parse(
        "{\"entities\":[{\"type\":\"PERSON\",\"value\":\"Jean Dupont\",\"person\":\"1\"}]}",
        ENTITIES
      ).orElseThrow();
      assertThat(items)
        .extracting(Item::label, Item::text, Item::metadata)
        .containsExactly(tuple("PERSON", "Jean Dupont", Map.of("person", "1")));
    }

    @Test
    void another_formats_keys_are_not_items() {
      assertThat(
        LlmSpans.parse("{\"spans\":[{\"label\":\"PERSON\",\"text\":\"Kari\"}]}", ENTITIES)
      ).isEmpty();
      assertThat(
        LlmSpans.parse(
          "{\"entities\":[{\"label\":\"PERSON\",\"text\":\"Kari\"}]}",
          ENTITIES
        ).orElseThrow()
      ).isEmpty();
    }

    @Test
    void a_bare_array_answer() {
      var bare = new LlmSpans.Format("", "label", "text", null);
      String answer = "<think>\n</think>\n[{\"label\":\"EMAIL\",\"text\":\"a@b.no\"}]";
      var items = LlmSpans.parse(answer, bare).orElseThrow();
      assertThat(items)
        .extracting(Item::label, Item::text)
        .containsExactly(tuple("EMAIL", "a@b.no"));
      assertThat(answer.charAt(items.getFirst().from())).isEqualTo('{');
      assertThat(LlmSpans.parse("{\"spans\":[]}", bare)).isEmpty();
    }

    @Test
    void the_schema_declares_the_items_and_their_fields_in_order() throws Exception {
      var schema = new ObjectMapper().readTree(ENTITIES.jsonSchema());
      assertThat(schema.at("/required/0").asText()).isEqualTo("entities");
      assertThat(schema.at("/additionalProperties").asBoolean()).isFalse();
      var item = schema.at("/properties/entities/items");
      assertThat(item.get("properties").fieldNames())
        .toIterable()
        .containsExactly("type", "value", "person");
      assertThat(item.get("required"))
        .extracting(JsonNode::asText)
        .containsExactly("type", "value");
      assertThat(item.at("/properties/value/minLength").asInt()).isEqualTo(1);
      assertThat(item.at("/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void a_bare_array_schema_is_the_array() throws Exception {
      var schema = new ObjectMapper().readTree(
        new LlmSpans.Format("", "label", "text", null).jsonSchema()
      );
      assertThat(schema.get("type").asText()).isEqualTo("array");
      assertThat(schema.at("/items/required/0").asText()).isEqualTo("label");
    }

    @Test
    void refuses_a_format_that_cannot_tell_label_from_value() {
      assertThatThrownBy(() -> new LlmSpans.Format("spans", "text", "text", null)).isInstanceOf(
        IllegalArgumentException.class
      );
      assertThatThrownBy(() -> new LlmSpans.Format("spans", " ", "text", null)).isInstanceOf(
        IllegalArgumentException.class
      );
      assertThatThrownBy(() ->
        new LlmSpans.Format("spans", "label", "text", List.of("label"))
      ).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Locate {

    @Test
    void places_items_in_order_of_appearance() {
      String text = "Ring Kari Nordmann på 912 34 567.";
      var located = LlmSpans.locate(
        text,
        List.of(new Item("PERSON", "Kari Nordmann"), new Item("PHONE", "912 34 567"))
      );
      assertThat(located.missing()).isZero();
      assertThat(located.spans()).containsExactly(
        new Span("PERSON", "Kari Nordmann", 5, 18),
        new Span("PHONE", "912 34 567", 22, 32)
      );
    }

    @Test
    void repeated_mentions_take_successive_occurrences() {
      String text = "Per ringte. Per kom.";
      var located = LlmSpans.locate(
        text,
        List.of(new Item("PERSON", "Per"), new Item("PERSON", "Per"))
      );
      assertThat(located.spans()).extracting(Span::start).containsExactly(0, 12);
    }

    @Test
    void prefers_a_whole_word_occurrence() {
      String text = "Kjønn: K";
      var located = LlmSpans.locate(text, List.of(new Item("GENDER", "K")));
      assertThat(located.spans()).containsExactly(new Span("GENDER", "K", 7, 8));
    }

    @Test
    void falls_back_to_case_insensitive_and_keeps_the_input_text() {
      String text = "Mail ANNA@EXAMPLE.NO now";
      var located = LlmSpans.locate(text, List.of(new Item("EMAIL", "anna@example.no")));
      assertThat(located.spans()).containsExactly(new Span("EMAIL", "ANNA@EXAMPLE.NO", 5, 20));
    }

    @Test
    void searches_from_the_start_when_order_is_off() {
      String text = "912 34 567 tilhører Kari";
      var located = LlmSpans.locate(
        text,
        List.of(new Item("PERSON", "Kari"), new Item("PHONE", "912 34 567"))
      );
      assertThat(located.spans()).extracting(Span::label).containsExactly("PHONE", "PERSON");
    }

    @Test
    void counts_text_absent_from_the_input_as_missing() {
      var located = LlmSpans.locate(
        "Ring Kari",
        List.of(new Item("PERSON", "Ola"), new Item("PERSON", "  "), new Item("PERSON", "Kari"))
      );
      assertThat(located.missing()).isEqualTo(1);
      assertThat(located.spans()).containsExactly(new Span("PERSON", "Kari", 5, 9));
    }

    @Test
    void offsets_are_string_indices_past_supplementary_characters() {
      String text = "😀 Kari";
      var located = LlmSpans.locate(text, List.of(new Item("PERSON", "Kari")));
      assertThat(located.spans()).containsExactly(new Span("PERSON", "Kari", 3, 7));
      assertThat(text.substring(3, 7)).isEqualTo("Kari");
    }
  }

  @Nested
  class AllOccurrences {

    @Test
    void marks_other_whole_word_occurrences() {
      String text = "Nordmann skrev. Nordmannsen svarte. Hilsen Nordmann";
      var spans = List.of(new Span("PERSON", "Nordmann", 0, 8));
      assertThat(LlmSpans.allOccurrences(text, spans, 4)).containsExactly(
        new Span("PERSON", "Nordmann", 0, 8),
        new Span("PERSON", "Nordmann", 43, 51)
      );
    }

    @Test
    void scores_carry_from_item_to_span_to_repeat() {
      String text = "Nordmann ringte. Hilsen Nordmann";
      var located = LlmSpans.locate(text, List.of(new Item("PERSON", "Nordmann").withScore(0.6f)));
      assertThat(LlmSpans.allOccurrences(text, located.spans(), 4))
        .extracting(Span::start, Span::score)
        .containsExactly(tuple(0, 0.6f), tuple(24, 0.6f));
    }

    @Test
    void metadata_carries_from_item_to_span_to_repeat() {
      String text = "Nordmann ringte. Hilsen Nordmann";
      var item = new Item("PERSON", "Nordmann", -1, -1, 1.0f, Map.of("ref", "p1"));
      var located = LlmSpans.locate(text, List.of(item));
      assertThat(LlmSpans.allOccurrences(text, located.spans(), 4))
        .extracting(Span::metadata)
        .containsOnly(Map.of("ref", "p1"));
    }

    @Test
    void leaves_short_values_and_disabled_pass_alone() {
      String text = "Per og per";
      var spans = List.of(new Span("PERSON", "Per", 0, 3));
      assertThat(LlmSpans.allOccurrences(text, spans, 4)).isEqualTo(spans);
      assertThat(
        LlmSpans.allOccurrences("Kari Kari", List.of(new Span("PERSON", "Kari", 0, 4)), 0)
      ).hasSize(1);
    }
  }
}
