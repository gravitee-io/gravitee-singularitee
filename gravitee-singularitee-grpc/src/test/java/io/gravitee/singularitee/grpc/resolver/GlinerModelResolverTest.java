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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File selection for the two GLiNER4j bundle layouts: ONNX repositories keep one folder per
 * variant, llama.cpp/ggml repositories keep everything under {@code gguf/} and encode the
 * quantisation in the file name. Downloading the wrong set either fetches a gigabyte of unused
 * weights or leaves the bundle unloadable.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class GlinerModelResolverTest {

  private static final List<String> ONNX_REPO = List.of(
    "gliner4j_config.json",
    "tokenizer.json",
    "onnx/ner_full.onnx",
    "onnx_fp16/ner_full.onnx",
    "onnx_quantized/ner_full.onnx"
  );

  private static final List<String> GGML_REPO = List.of(
    "gliner4j_config.json",
    "tokenizer.json",
    "README.md",
    "gguf/model.gguf",
    "gguf/model-q8_0.gguf",
    "gguf/model-q4_0.gguf"
  );

  @Test
  void onnx_repository_keeps_root_files_and_the_variant_folder_only() {
    var files = GlinerModelResolver.selectFiles(ONNX_REPO, "onnx_fp16", f -> false);
    assertThat(files).containsExactlyInAnyOrder(
      "gliner4j_config.json",
      "tokenizer.json",
      "onnx_fp16/ner_full.onnx"
    );
  }

  @Test
  void ggml_repository_default_variant_takes_the_f16_gguf_only() {
    // the factories pass "onnx" when the variant is blank: on a ggml bundle that means the default weights
    var files = GlinerModelResolver.selectFiles(GGML_REPO, "onnx", f -> false);
    assertThat(files).containsExactlyInAnyOrder(
      "gliner4j_config.json",
      "tokenizer.json",
      "README.md",
      "gguf/model.gguf"
    );
  }

  @Test
  void ggml_repository_quantised_variant_takes_that_file_and_skips_the_f16_one() {
    var files = GlinerModelResolver.selectFiles(GGML_REPO, "q8_0", f -> false);
    assertThat(files).contains("gguf/model-q8_0.gguf");
    assertThat(files).doesNotContain("gguf/model.gguf", "gguf/model-q4_0.gguf");
  }

  @Test
  void ggml_repository_keeps_the_other_gguf_files() {
    var repo = List.of("gliner4j_config.json", "gguf/backbone-q8_0.gguf", "gguf/scorer.gguf");
    var files = GlinerModelResolver.selectFiles(repo, "onnx", f -> false);
    assertThat(files).containsExactlyInAnyOrder(
      "gliner4j_config.json",
      "gguf/backbone-q8_0.gguf",
      "gguf/scorer.gguf"
    );
  }

  @Test
  void download_exclude_still_applies() {
    var files = GlinerModelResolver.selectFiles(GGML_REPO, "onnx", f -> f.endsWith(".md"));
    assertThat(files).doesNotContain("README.md").contains("gguf/model.gguf");
  }

  @Test
  void an_empty_gguf_folder_is_not_a_local_bundle(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("gguf"));
    assertThat(GlinerModelResolver.hasBundle(dir, "onnx")).isFalse();
  }

  @Test
  void a_local_gguf_bundle_is_accepted_whatever_the_variant(@TempDir Path dir) throws Exception {
    touch(dir, "gguf/model.gguf");
    assertThat(GlinerModelResolver.hasBundle(dir, "onnx")).isTrue();
    assertThat(GlinerModelResolver.hasBundle(dir, "q8_0")).isTrue();
  }

  @Test
  void only_a_completed_download_counts_as_cached(@TempDir Path dir) throws Exception {
    touch(dir, "gguf/model.gguf");
    assertThat(GlinerModelResolver.isCached(dir, "onnx")).isFalse();
    GlinerModelResolver.markComplete(dir, "onnx", List.of(dir.resolve("gguf/model.gguf")));
    assertThat(GlinerModelResolver.isCached(dir, "onnx")).isTrue();
  }

  @Test
  void each_variant_is_marked_separately(@TempDir Path dir) throws Exception {
    touch(dir, "gguf/model.gguf");
    GlinerModelResolver.markComplete(dir, "onnx", List.of(dir.resolve("gguf/model.gguf")));
    // the repository may carry model-q8_0.gguf: only the listing can tell, so no cache shortcut
    assertThat(GlinerModelResolver.isCached(dir, "q8_0")).isFalse();
    touch(dir, "gguf/model-q8_0.gguf");
    GlinerModelResolver.markComplete(dir, "q8_0", List.of(dir.resolve("gguf/model-q8_0.gguf")));
    assertThat(GlinerModelResolver.isCached(dir, "q8_0")).isTrue();
    assertThat(GlinerModelResolver.isCached(dir, "onnx")).isTrue();
  }

  @Test
  void a_partial_multi_file_bundle_is_not_cached(@TempDir Path dir) throws Exception {
    // decoder-kv and streaming-span bundles ship several unrelated gguf files: a directory
    // holding only some of them must not short-circuit the download
    touch(dir, "gguf/scorer.gguf");
    assertThat(GlinerModelResolver.isCached(dir, "onnx")).isFalse();
    assertThat(GlinerModelResolver.isCached(dir, "q8_0")).isFalse();
  }

  @Test
  void an_onnx_variant_folder_alone_is_not_cached(@TempDir Path dir) throws Exception {
    // the folder may hold a partial download; the marker is what makes it a hit
    Files.createDirectories(dir.resolve("onnx_fp16"));
    assertThat(GlinerModelResolver.isCached(dir, "onnx_fp16")).isFalse();
    GlinerModelResolver.markComplete(dir, "onnx_fp16", List.of(dir.resolve("onnx_fp16")));
    assertThat(GlinerModelResolver.isCached(dir, "onnx_fp16")).isTrue();
    assertThat(GlinerModelResolver.isCached(dir, "onnx_quantized")).isFalse();
  }

  @Test
  void a_variant_name_cannot_escape_the_cache_directory(@TempDir Path dir) throws Exception {
    GlinerModelResolver.markComplete(dir, "../evil", List.of());
    assertThat(GlinerModelResolver.isCached(dir, "../evil")).isTrue();
    assertThat(
      Files.list(dir)
        .map(p -> p.getFileName().toString())
        .toList()
    ).containsExactly(".complete-.._evil");
  }

  private static void touch(Path dir, String file) throws Exception {
    Path path = dir.resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "");
  }
}
