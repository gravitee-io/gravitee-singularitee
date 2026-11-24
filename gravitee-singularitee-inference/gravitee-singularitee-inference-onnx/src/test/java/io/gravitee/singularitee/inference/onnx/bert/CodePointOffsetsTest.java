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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CodePointOffsetsTest {

  @Test
  void should_keep_offsets_of_a_text_within_the_bmp() {
    var offsets = CodePointOffsets.of("Café ☺ john");

    assertEquals(0, offsets.toStringIndex(0));
    assertEquals(7, offsets.toStringIndex(7));
    assertEquals(11, offsets.toStringIndex(11));
  }

  @Test
  void should_count_two_units_per_character_outside_the_bmp() {
    String text = "😀😀🎉 john";
    var offsets = CodePointOffsets.of(text);

    assertEquals(0, offsets.toStringIndex(0));
    assertEquals(2, offsets.toStringIndex(1));
    assertEquals("john", text.substring(offsets.toStringIndex(4), offsets.toStringIndex(8)));
  }

  @Test
  void should_map_an_offset_past_the_end_to_the_end() {
    String text = "😀 a";

    assertEquals(text.length(), CodePointOffsets.of(text).toStringIndex(99));
  }
}
