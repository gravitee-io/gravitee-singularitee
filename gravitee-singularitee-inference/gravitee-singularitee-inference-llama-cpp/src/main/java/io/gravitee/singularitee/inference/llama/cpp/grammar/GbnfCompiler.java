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

import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput.Choice;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput.Grammar;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput.JsonObject;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput.JsonSchema;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput.Regex;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates a {@link StructuredOutput} into the GBNF grammar llama.cpp enforces. Results are
 * cached, since clients resend the same schema on every request.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GbnfCompiler {

  private static final int CACHE_SIZE = 256;

  private static final Map<StructuredOutput, Gbnf> CACHE = Collections.synchronizedMap(
    new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<StructuredOutput, Gbnf> eldest) {
        return size() > CACHE_SIZE;
      }
    }
  );

  private GbnfCompiler() {}

  /**
   * @throws UnsupportedStructuredOutputException when llama.cpp cannot enforce the format
   */
  public static Gbnf compile(StructuredOutput format) {
    Gbnf cached = CACHE.get(format);
    if (cached != null) {
      return cached;
    }
    Gbnf compiled = switch (format) {
      case JsonSchema(String schema) -> JsonSchemaToGbnf.compile(schema);
      case JsonObject() -> jsonObject();
      case Choice choice -> choice(choice);
      case Grammar(String text, String root) -> GbnfValidator.validated(new Gbnf(text, root));
      case Regex _ -> throw new UnsupportedStructuredOutputException(
        "regex structured output is not supported by the llama.cpp engine; use a grammar"
      );
    };
    CACHE.put(format, compiled);
    return compiled;
  }

  private static Gbnf jsonObject() {
    var rules = new LinkedHashMap<String, String>();
    JsonGbnfRules.include(JsonGbnfRules.OBJECT, rules);
    rules.put(StructuredOutput.DEFAULT_ROOT, JsonGbnfRules.OBJECT);
    String text = rules
      .entrySet()
      .stream()
      .map(e -> e.getKey() + " ::= " + e.getValue())
      .collect(Collectors.joining("\n", "", "\n"));
    return new Gbnf(text, StructuredOutput.DEFAULT_ROOT);
  }

  private static Gbnf choice(Choice choice) {
    String body = choice.values().stream().map(Gbnf::literal).collect(Collectors.joining(" | "));
    return new Gbnf(
      StructuredOutput.DEFAULT_ROOT + " ::= " + body + "\n",
      StructuredOutput.DEFAULT_ROOT
    );
  }
}
