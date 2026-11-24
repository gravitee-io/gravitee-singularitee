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
package io.gravitee.singularitee.inference.onnx.bert;

/**
 * Converts the offsets a HuggingFace tokenizer reports into Java {@code String} indices.
 *
 * <p>The tokenizer counts Unicode code points; a {@code String} counts UTF-16 code units, the
 * unit every span and chunk offset of this server uses. The two agree until a character outside
 * the Basic Multilingual Plane (an emoji, some ideographs) comes first: it is one code point
 * and two code units.
 *
 * @author GraviteeSource Team
 */
public final class CodePointOffsets {

  /** String index of each code point, plus the text length; {@code null} when they all agree. */
  private final int[] indexOf;

  private CodePointOffsets(int[] indexOf) {
    this.indexOf = indexOf;
  }

  /** The offsets of {@code text}. */
  public static CodePointOffsets of(String text) {
    int codePoints = text.codePointCount(0, text.length());
    if (codePoints == text.length()) {
      return new CodePointOffsets(null);
    }
    int[] indexOf = new int[codePoints + 1];
    int index = 0;
    for (int i = 0; i < codePoints; i++) {
      indexOf[i] = index;
      index += Character.charCount(text.codePointAt(index));
    }
    indexOf[codePoints] = index;
    return new CodePointOffsets(indexOf);
  }

  /** The {@code String} index of a code-point offset; an offset past the end maps to the end. */
  public int toStringIndex(int codePointOffset) {
    if (indexOf == null) {
      return codePointOffset;
    }
    return indexOf[Math.clamp(codePointOffset, 0, indexOf.length - 1)];
  }
}
