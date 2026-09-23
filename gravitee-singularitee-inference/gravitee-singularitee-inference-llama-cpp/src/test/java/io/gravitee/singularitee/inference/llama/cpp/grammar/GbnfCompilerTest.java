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
package io.gravitee.singularitee.inference.llama.cpp.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structured output to GBNF. Every emitted grammar is run back through {@link GbnfValidator}, so a
 * rule that is referenced but never defined fails here rather than in the native parser.
 */
class GbnfCompilerTest {

  private static Gbnf schema(String json) {
    Gbnf gbnf = GbnfCompiler.compile(new StructuredOutput.JsonSchema(json));
    assertThatCode(() -> GbnfValidator.validated(gbnf)).doesNotThrowAnyException();
    return gbnf;
  }

  private static String rule(Gbnf gbnf, String name) {
    return gbnf
      .text()
      .lines()
      .filter(line -> line.startsWith(name + " ::= "))
      .map(line -> line.substring(name.length() + 5))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no rule " + name + " in\n" + gbnf.text()));
  }

  @Test
  void requiredPropertiesAreEmittedInDeclaredOrder() {
    Gbnf gbnf = schema(
      """
      {"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},
       "required":["name","age"],"additionalProperties":false}"""
    );

    assertThat(gbnf.root()).isEqualTo("root");
    assertThat(rule(gbnf, "root")).isEqualTo("schema");
    assertThat(rule(gbnf, "schema")).isEqualTo(
      "\"{\" space schema-name-kv space \",\" space schema-age-kv space \"}\""
    );
    assertThat(rule(gbnf, "schema-name-kv")).isEqualTo("\"\\\"name\\\"\" space \":\" space string");
    assertThat(rule(gbnf, "schema-age-kv")).endsWith("integer");
  }

  @Test
  void optionalPropertiesFormAnOrderedSubsetAfterTheRequiredOnes() {
    Gbnf gbnf = schema(
      """
      {"type":"object","properties":{"a":{"type":"boolean"},"b":{"type":"null"},"c":{"type":"number"}},
       "required":["b"]}"""
    );

    assertThat(rule(gbnf, "schema")).isEqualTo(
      "\"{\" space schema-b-kv (space \",\" space schema-rest-0)? space \"}\""
    );
    assertThat(rule(gbnf, "schema-rest-0")).isEqualTo(
      "schema-a-kv (space \",\" space schema-rest-1)? | schema-rest-1"
    );
    assertThat(rule(gbnf, "schema-rest-1")).isEqualTo("schema-c-kv");
  }

  @Test
  void objectWithOnlyOptionalPropertiesMayBeEmpty() {
    Gbnf gbnf = schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");

    assertThat(rule(gbnf, "schema")).isEqualTo("\"{\" space (schema-rest-0)? space \"}\"");
  }

  @Test
  void arrayBoundsBecomeRepetitions() {
    Gbnf gbnf = schema(
      "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"maxItems\":3}"
    );

    assertThat(rule(gbnf, "schema")).isEqualTo(
      "\"[\" space string (space \",\" space string){0,2} space \"]\""
    );
  }

  @Test
  void unboundedArrayMayBeEmpty() {
    Gbnf gbnf = schema("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}");

    assertThat(rule(gbnf, "schema")).isEqualTo(
      "\"[\" space (integer (space \",\" space integer)*)? space \"]\""
    );
  }

  @Test
  void enumValuesAreJsonEncodedLiterals() {
    Gbnf gbnf = schema("{\"enum\":[\"a\\\"b\",1,null]}");

    assertThat(rule(gbnf, "schema")).isEqualTo("(\"\\\"a\\\\\\\"b\\\"\" | \"1\" | \"null\")");
  }

  @Test
  void stringLengthBoundsConstrainTheCharacterCount() {
    Gbnf gbnf = schema("{\"type\":\"string\",\"minLength\":2,\"maxLength\":5}");

    assertThat(rule(gbnf, "schema")).isEqualTo("\"\\\"\" char{2,5} \"\\\"\"");
  }

  @Test
  void anyOfAndTypeListsAlternate() {
    assertThat(
      rule(schema("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}"), "schema")
    ).isEqualTo("string | null");
    assertThat(rule(schema("{\"type\":[\"integer\",\"null\"]}"), "schema")).isEqualTo(
      "integer | null"
    );
  }

  @Test
  void recursiveRefResolvesToOneRule() {
    Gbnf gbnf = schema(
      """
      {"$ref":"#/$defs/node","$defs":{"node":{"type":"object",
        "properties":{"value":{"type":"integer"},"children":{"type":"array","items":{"$ref":"#/$defs/node"}}},
        "required":["value","children"]}}}"""
    );

    assertThat(rule(gbnf, "root")).isEqualTo("ref-node");
    assertThat(gbnf.text()).contains("(space \",\" space ref-node)*");
  }

  @Test
  void emptySchemaAcceptsAnyValue() {
    assertThat(rule(schema("{}"), "root")).isEqualTo("value");
  }

  @Test
  void whatCannotBeEnforcedIsRejected() {
    for (String json : List.of(
      "{\"type\":\"string\",\"pattern\":\"^a+$\"}",
      "{\"type\":\"integer\",\"minimum\":0}",
      "{\"type\":\"object\",\"patternProperties\":{}}",
      "{\"allOf\":[{\"type\":\"string\"},{\"minLength\":1}]}",
      "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
      "{\"$ref\":\"https://example.com/schema.json\"}",
      "{\"$ref\":\"#/$defs/missing\"}",
      "{\"type\":\"object\",\"properties\":{},\"required\":[\"x\"]}",
      "{\"$ref\":\"#\"}",
      "not json"
    )) {
      assertThatThrownBy(() -> GbnfCompiler.compile(new StructuredOutput.JsonSchema(json)))
        .isInstanceOf(UnsupportedStructuredOutputException.class)
        .hasMessageStartingWith("json_schema");
    }
  }

  @Test
  void jsonObjectUsesTheGenericObjectRule() {
    Gbnf gbnf = GbnfCompiler.compile(new StructuredOutput.JsonObject());

    assertThatCode(() -> GbnfValidator.validated(gbnf)).doesNotThrowAnyException();
    assertThat(rule(gbnf, "root")).isEqualTo("object");
  }

  @Test
  void booleanAdditionalPropertiesIsIgnoredNextToDeclaredProperties() {
    // Closed objects satisfy `false` and are a legal subset of what `true` allows.
    for (String additional : List.of("true", "false")) {
      Gbnf gbnf = schema(
        """
        {"type":"object","properties":{"a":{"type":"string"}},"required":["a"],
         "additionalProperties":%s}""".formatted(additional)
      );

      assertThat(rule(gbnf, "schema")).isEqualTo("\"{\" space schema-a-kv space \"}\"");
    }
  }

  @Test
  void additionalPropertiesCarryingASchemaIsRejected() {
    assertThatThrownBy(() ->
      GbnfCompiler.compile(
        new StructuredOutput.JsonSchema(
          """
          {"type":"object","additionalProperties":{"type":"integer"}}"""
        )
      )
    )
      .isInstanceOf(UnsupportedStructuredOutputException.class)
      .hasMessageContaining("`additionalProperties` with a schema value");
  }

  @Test
  void closedObjectWithoutPropertiesIsRejectedRatherThanCompiledToAnyObject() {
    assertThatThrownBy(() ->
      GbnfCompiler.compile(
        new StructuredOutput.JsonSchema(
          """
          {"type":"object","additionalProperties":false}"""
        )
      )
    )
      .isInstanceOf(UnsupportedStructuredOutputException.class)
      .hasMessageContaining("`additionalProperties: false` without `properties`");
  }

  @Test
  void openObjectWithoutPropertiesStillCompilesToAnyObject() {
    Gbnf gbnf = schema(
      """
      {"type":"object","additionalProperties":true}"""
    );

    assertThat(rule(gbnf, "root")).isEqualTo("object");
  }

  @Test
  void choiceIsAnAlternationOfEscapedLiterals() {
    Gbnf gbnf = GbnfCompiler.compile(new StructuredOutput.Choice(List.of("yes", "say \"no\"")));

    assertThat(gbnf.text()).isEqualTo("root ::= \"yes\" | \"say \\\"no\\\"\"\n");
  }

  @Test
  void regexIsNotSupported() {
    assertThatThrownBy(() -> GbnfCompiler.compile(new StructuredOutput.Regex("[0-9]+")))
      .isInstanceOf(UnsupportedStructuredOutputException.class)
      .hasMessageContaining("regex");
  }

  @Test
  void rawGrammarPassesThroughWithItsRoot() {
    var grammar = new StructuredOutput.Grammar(
      "# answer\nstart ::= \"a\" tail{1,3}\ntail ::= [b-d]\n",
      "start"
    );

    assertThat(GbnfCompiler.compile(grammar)).isEqualTo(new Gbnf(grammar.text(), "start"));
  }

  @Test
  void malformedRawGrammarIsRejectedBeforeTheNativeParser() {
    for (String text : List.of(
      "root ::= \"a\" missing",
      "other ::= \"a\"",
      "root ::= \"unterminated",
      "root ::= (\"a\" | \"b\"",
      "root ::= [a-z",
      "root ::= \"a\"\nroot ::= \"b\""
    )) {
      assertThatThrownBy(() -> GbnfCompiler.compile(new StructuredOutput.Grammar(text, null)))
        .isInstanceOf(UnsupportedStructuredOutputException.class)
        .hasMessageStartingWith("grammar:");
    }
  }
}
