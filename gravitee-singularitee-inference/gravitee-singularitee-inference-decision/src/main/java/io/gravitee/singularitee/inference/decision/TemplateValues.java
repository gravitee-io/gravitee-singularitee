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
package io.gravitee.singularitee.inference.decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Transformations of the JSON-like values ({@code String}, {@code Number}, {@code Boolean},
 * {@code Map}, {@code List}, {@code null}) a decision template receives, as the families were
 * trained with them.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class TemplateValues {

  private static final Pattern SPECIAL_TOKEN = Pattern.compile("<\\|([A-Za-z0-9_]+)\\|>");

  private TemplateValues() {}

  /** Sorts the keys of every object, recursively. */
  static Object sortKeys(Object value) {
    return switch (value) {
      case Map<?, ?> map -> {
        var out = new TreeMap<String, Object>();
        map.forEach((k, v) -> out.put(String.valueOf(k), sortKeys(v)));
        yield out;
      }
      case List<?> list -> list.stream().map(TemplateValues::sortKeys).toList();
      case null, default -> value;
    };
  }

  /** Replaces {@code search} in every string, recursively; keys are kept. */
  static Object replaceText(Object value, String search, String replacement) {
    return switch (value) {
      case String s -> s.replace(search, replacement);
      case Map<?, ?> map -> {
        var out = map instanceof TreeMap<?, ?>
          ? new TreeMap<String, Object>()
          : new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> out.put(String.valueOf(k), replaceText(v, search, replacement)));
        yield out;
      }
      case List<?> list -> {
        var out = new ArrayList<>(list.size());
        for (Object item : list) out.add(replaceText(item, search, replacement));
        yield out;
      }
      case null, default -> value;
    };
  }

  /**
   * kev's text form of a value: objects become {@code key: value} lines, arrays {@code - item}
   * lines, and special tokens written in the text are defused so they tokenize as text.
   */
  static String kevText(Object value) {
    return SPECIAL_TOKEN.matcher(kevRender(value, 0)).replaceAll("<¦$1¦>");
  }

  static String kevRender(Object value, int indent) {
    String pad = "  ".repeat(indent);
    return switch (value) {
      case null -> "";
      case String s -> s;
      case Boolean b -> b ? "True" : "False";
      case List<?> list -> {
        var out = new StringBuilder();
        for (Object item : list) {
          String text = kevRender(item, indent + 1).stripLeading();
          if (!out.isEmpty()) out.append('\n');
          out.append(pad).append("- ").append(text);
        }
        yield out.toString();
      }
      case Map<?, ?> map -> {
        var out = new StringBuilder();
        map.forEach((k, v) -> {
          boolean nested = v instanceof Map<?, ?> || v instanceof List<?>;
          if (!out.isEmpty()) out.append('\n');
          out
            .append(pad)
            .append(k)
            .append(nested ? ":\n" : ": ")
            .append(kevRender(v, nested ? indent + 1 : 0));
        });
        yield out.toString();
      }
      case Double d when d == Math.rint(d) && Math.abs(d) < 1e15 -> (long) d.doubleValue() + ".0";
      default -> String.valueOf(value);
    };
  }
}
