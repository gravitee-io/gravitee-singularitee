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
package io.gravitee.singularitee.grpc.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Glob semantics for {@code download.exclude:}.
 *
 * <p>These patterns are written by hand in YAML against file names, so the
 * failure that matters is a pattern quietly matching more than its author meant:
 * excluding the weights instead of a duplicate turns a working model definition
 * into a load error at startup.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class ExcludePatternsTest {

  @Test
  void no_patterns_excludes_nothing() {
    assertThat(ExcludePatterns.excluder(List.of()).test("model.safetensors")).isFalse();
    assertThat(ExcludePatterns.excluder(null).test("model.safetensors")).isFalse();
  }

  @Test
  void star_stays_inside_one_path_segment() {
    var excluded = ExcludePatterns.excluder(List.of("onnx/*.onnx"));

    assertThat(excluded.test("onnx/model.onnx")).isTrue();
    // A '*' that crossed '/' would take the nested file too.
    assertThat(excluded.test("onnx/layer-22/model.onnx")).isFalse();
  }

  @Test
  void double_star_crosses_path_segments() {
    var excluded = ExcludePatterns.excluder(List.of("onnx/**/*.onnx"));

    assertThat(excluded.test("onnx/layer-22/model.onnx")).isTrue();
    // "**/" spans zero directories as well, so the direct child matches too.
    assertThat(excluded.test("onnx/model.onnx")).isTrue();
  }

  @Test
  void a_bare_pattern_also_matches_the_file_name_alone() {
    var excluded = ExcludePatterns.excluder(List.of("*.pth"));

    assertThat(excluded.test("consolidated.00.pth")).isTrue();
    assertThat(excluded.test("original/consolidated.00.pth")).isTrue();
  }

  @Test
  void a_pattern_with_a_slash_is_anchored_at_the_root() {
    var excluded = ExcludePatterns.excluder(List.of("original/*"));

    assertThat(excluded.test("original/params.json")).isTrue();
    assertThat(excluded.test("nested/original/params.json")).isFalse();
  }

  @Test
  void a_dot_is_literal_not_any_character() {
    // The regex trap: unescaped, "model.bin" would also match "modelXbin".
    var excluded = ExcludePatterns.excluder(List.of("model.bin"));

    assertThat(excluded.test("model.bin")).isTrue();
    assertThat(excluded.test("modelXbin")).isFalse();
  }

  @Test
  void question_mark_matches_exactly_one_character() {
    var excluded = ExcludePatterns.excluder(List.of("model-0000?-of-00003.safetensors"));

    assertThat(excluded.test("model-00002-of-00003.safetensors")).isTrue();
    assertThat(excluded.test("model-00012-of-00003.safetensors")).isFalse();
  }

  @Test
  void matching_ignores_case() {
    assertThat(ExcludePatterns.excluder(List.of("*.PTH")).test("weights.pth")).isTrue();
  }

  @Test
  void a_pattern_must_match_the_whole_path_not_a_fragment() {
    // Substring matching would make "model" exclude every shard in the repo.
    var excluded = ExcludePatterns.excluder(List.of("model"));

    assertThat(excluded.test("model")).isTrue();
    assertThat(excluded.test("model.safetensors")).isFalse();
  }

  @Test
  void blank_and_null_patterns_are_ignored() {
    var excluded = ExcludePatterns.excluder(Arrays.asList("  ", null, "*.gguf"));

    assertThat(excluded.test("model.safetensors")).isFalse();
    assertThat(excluded.test("model.gguf")).isTrue();
  }
}
