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

import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Structural check of caller-supplied GBNF before it reaches the native parser, which reports a
 * rejected grammar as a null sampler rather than an error. Catches unterminated literals and
 * classes, unbalanced groups, a missing root and references to undefined rules. It does not
 * replace the native parser: semantics such as left recursion are still decided there.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class GbnfValidator {

  private GbnfValidator() {}

  static Gbnf validated(Gbnf gbnf) {
    String text = gbnf.text();
    Set<String> defined = new LinkedHashSet<>();
    Set<String> referenced = new LinkedHashSet<>();
    int depth = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '#') {
        i = lineEnd(text, i);
      } else if (c == '"') {
        i = closing(text, i, '"', "string literal");
      } else if (c == '[') {
        i = closing(text, i, ']', "character class");
      } else if (c == '<') {
        i = closing(text, i, '>', "token reference");
      } else if (c == '{') {
        i = closing(text, i, '}', "repetition");
      } else if (c == '(') {
        depth++;
        i++;
      } else if (c == ')') {
        if (--depth < 0) {
          throw invalid("unbalanced `)`");
        }
        i++;
      } else if (isNameChar(c)) {
        int end = i;
        while (end < text.length() && isNameChar(text.charAt(end))) {
          end++;
        }
        String name = text.substring(i, end);
        int next = skipBlanks(text, end);
        if (text.startsWith("::=", next)) {
          if (depth != 0) {
            throw invalid("unbalanced `(` before rule `" + name + "`");
          }
          if (!defined.add(name)) {
            throw invalid("rule `" + name + "` is defined twice");
          }
          i = next + 3;
        } else {
          referenced.add(name);
          i = end;
        }
      } else {
        i++;
      }
    }
    if (depth != 0) {
      throw invalid("unbalanced `(`");
    }
    if (!defined.contains(gbnf.root())) {
      throw invalid("root rule `" + gbnf.root() + "` is not defined");
    }
    referenced.removeAll(defined);
    if (!referenced.isEmpty()) {
      throw invalid("undefined rules " + referenced);
    }
    return gbnf;
  }

  private static boolean isNameChar(char c) {
    return (
      (c >= 'a' && c <= 'z') ||
      (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') ||
      c == '-' ||
      c == '_'
    );
  }

  private static int lineEnd(String text, int from) {
    int end = text.indexOf('\n', from);
    return end < 0 ? text.length() : end + 1;
  }

  private static int skipBlanks(String text, int from) {
    int i = from;
    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }

  /** Index just past the unescaped {@code close} matching the opener at {@code from}. */
  private static int closing(String text, int from, char close, String what) {
    for (int i = from + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == close) {
        return i + 1;
      } else if (c == '\n') {
        break;
      }
    }
    throw invalid("unterminated " + what);
  }

  private static UnsupportedStructuredOutputException invalid(String reason) {
    return new UnsupportedStructuredOutputException("grammar: " + reason);
  }
}
