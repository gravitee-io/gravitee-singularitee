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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GBNF rules for plain JSON values, shared by the any-JSON grammar and the schema compiler.
 * Whitespace is bounded and only ever sits between tokens, so a model cannot stall a constrained
 * generation on padding and a finished value can only be followed by the end of the text.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class JsonGbnfRules {

  static final String SPACE = "space";
  static final String CHAR = "char";
  static final String STRING = "string";
  static final String NUMBER = "number";
  static final String INTEGER = "integer";
  static final String BOOLEAN = "boolean";
  static final String NULL = "null";
  static final String VALUE = "value";
  static final String OBJECT = "object";
  static final String ARRAY = "array";

  record Rule(String body, List<String> dependsOn) {}

  private static final Map<String, Rule> RULES = new LinkedHashMap<>();

  static {
    RULES.put(SPACE, new Rule("| \" \" | \"\\n\"{1,2} [ \\t]{0,20}", List.of()));
    RULES.put(
      CHAR,
      new Rule(
        "[^\"\\\\\\x7F\\x00-\\x1F] | [\\\\] ([\"\\\\bfnrt] | \"u\" [0-9a-fA-F]{4})",
        List.of()
      )
    );
    RULES.put(STRING, new Rule("\"\\\"\" char* \"\\\"\"", List.of(CHAR)));
    RULES.put("integral-part", new Rule("[0] | [1-9] [0-9]{0,15}", List.of()));
    RULES.put("decimal-part", new Rule("[0-9]{1,16}", List.of()));
    RULES.put(
      NUMBER,
      new Rule(
        "(\"-\"? integral-part) (\".\" decimal-part)? ([eE] [-+]? integral-part)?",
        List.of("integral-part", "decimal-part")
      )
    );
    RULES.put(INTEGER, new Rule("\"-\"? integral-part", List.of("integral-part")));
    RULES.put(BOOLEAN, new Rule("\"true\" | \"false\"", List.of()));
    RULES.put(NULL, new Rule("\"null\"", List.of()));
    RULES.put(
      VALUE,
      new Rule(
        "object | array | string | number | boolean | null",
        List.of(OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL)
      )
    );
    RULES.put(
      OBJECT,
      new Rule(
        "\"{\" space ( string space \":\" space value (space \",\" space string space \":\" space value)* )? space \"}\"",
        List.of(SPACE, STRING, VALUE)
      )
    );
    RULES.put(
      ARRAY,
      new Rule(
        "\"[\" space ( value (space \",\" space value)* )? space \"]\"",
        List.of(SPACE, VALUE)
      )
    );
  }

  private JsonGbnfRules() {}

  static boolean isReserved(String name) {
    return RULES.containsKey(name);
  }

  /** Adds {@code name} and everything it depends on to {@code target}. */
  static void include(String name, Map<String, String> target) {
    if (target.containsKey(name)) {
      return;
    }
    Rule rule = RULES.get(name);
    target.put(name, rule.body());
    rule.dependsOn().forEach(dep -> include(dep, target));
  }
}
