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
package io.gravitee.singularitee.inference.api.textgen;

import java.util.List;

/**
 * A decoding constraint: the generated text always matches the format. Backend neutral; each
 * engine translates it to its own mechanism or rejects it with
 * {@link UnsupportedStructuredOutputException}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public sealed interface StructuredOutput {
  String DEFAULT_ROOT = "root";

  /** Output validates against a JSON Schema document, given as JSON text. */
  record JsonSchema(String schema) implements StructuredOutput {
    public JsonSchema {
      if (schema == null || schema.isBlank()) {
        throw new IllegalArgumentException("structured output: json_schema is empty");
      }
    }
  }

  /** Output is any syntactically valid JSON object. */
  record JsonObject() implements StructuredOutput {}

  /** Output is exactly one of the values. */
  record Choice(List<String> values) implements StructuredOutput {
    public Choice {
      if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException("structured output: choice needs at least one value");
      }
      values = List.copyOf(values);
    }
  }

  /** The whole output matches a regular expression. */
  record Regex(String pattern) implements StructuredOutput {
    public Regex {
      if (pattern == null || pattern.isEmpty()) {
        throw new IllegalArgumentException("structured output: regex is empty");
      }
    }
  }

  /** Output is derived from a GBNF grammar, starting at {@code root}. */
  record Grammar(String text, String root) implements StructuredOutput {
    public Grammar {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("structured output: grammar is empty");
      }
      root = root == null || root.isBlank() ? DEFAULT_ROOT : root;
    }
  }
}
