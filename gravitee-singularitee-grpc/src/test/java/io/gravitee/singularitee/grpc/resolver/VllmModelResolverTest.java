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

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * File selection for a vLLM model download.
 *
 * <p>What this guards is bandwidth and disk. HuggingFace repositories routinely
 * carry the same weights several times over — a GGUF for llama.cpp, an ONNX
 * export, a legacy PyTorch {@code .bin} beside the safetensors — and taking the
 * lot would multiply a 60GB download for no benefit. Equally, dropping a file
 * vLLM actually needs (a tokenizer, the safetensors index) fails much later,
 * inside the engine, with an error that does not point back here.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmModelResolverTest {

  @Test
  void takes_safetensors_and_every_piece_of_metadata() {
    var selected = VllmModelResolver.selectFiles(
      Set.of(
        "config.json",
        "generation_config.json",
        "model.safetensors",
        "model.safetensors.index.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "vocab.txt",
        "tokenizer.model",
        "chat_template.jinja"
      )
    );

    assertThat(selected).hasSize(10);
  }

  @Test
  void drops_other_runtimes_copies_of_the_same_weights() {
    // The case that matters: a repo shipping GGUF and ONNX alongside the
    // safetensors would otherwise triple the transfer.
    var selected = VllmModelResolver.selectFiles(
      Set.of(
        "config.json",
        "model.safetensors",
        "model-Q4_K_M.gguf",
        "onnx/model.onnx",
        "onnx/model.onnx_data",
        "model.pth",
        "flax_model.msgpack",
        "README.md"
      )
    );

    assertThat(selected).containsExactly("config.json", "model.safetensors");
  }

  @Test
  void prefers_safetensors_over_the_legacy_bin() {
    // Both formats hold the same tensors — taking both doubles the download.
    var selected = VllmModelResolver.selectFiles(
      Set.of("config.json", "model.safetensors", "pytorch_model.bin")
    );

    assertThat(selected).containsExactly("config.json", "model.safetensors");
  }

  @Test
  void falls_back_to_bin_when_there_are_no_safetensors() {
    // Older checkpoints predate safetensors entirely; refusing them would make
    // the model unloadable rather than merely slower.
    var selected = VllmModelResolver.selectFiles(
      Set.of("config.json", "pytorch_model.bin", "tokenizer.json")
    );

    assertThat(selected).contains("pytorch_model.bin");
  }

  @Test
  void keeps_every_shard_of_a_sharded_model() {
    var selected = VllmModelResolver.selectFiles(
      Set.of(
        "config.json",
        "model-00001-of-00003.safetensors",
        "model-00002-of-00003.safetensors",
        "model-00003-of-00003.safetensors",
        "model.safetensors.index.json"
      )
    );

    assertThat(selected).hasSize(5);
    assertThat(selected).allSatisfy(f -> assertThat(f).doesNotContain(".gguf"));
  }

  @Test
  void reports_nothing_to_download_for_a_weightless_repo() {
    // A repo of pure metadata is a configuration mistake; an empty selection is
    // what lets the resolver say so instead of "downloaded 0 files, all good".
    assertThat(VllmModelResolver.selectFiles(Set.of("README.md", ".gitattributes"))).isEmpty();
  }

  // ── download.exclude: ────────────────────────────────────────────────────

  @Test
  void excludes_a_duplicate_the_built_in_rules_cannot_see() {
    // Mistral-style repos ship one consolidated safetensors *and* the sharded
    // set. Same format, so the suffix rules keep both and the transfer doubles.
    var selected = VllmModelResolver.selectFiles(
      Set.of(
        "config.json",
        "model-00001-of-00002.safetensors",
        "model-00002-of-00002.safetensors",
        "model.safetensors.index.json",
        "consolidated.safetensors"
      ),
      List.of("consolidated*.safetensors")
    );

    assertThat(selected).doesNotContain("consolidated.safetensors");
    assertThat(selected).hasSize(4);
  }

  @Test
  void a_bare_pattern_matches_wherever_the_file_sits() {
    // "*.pth" is written without a directory, but the file it means to catch
    // lives under original/ — matching on the file name alone is the point.
    var selected = VllmModelResolver.selectFiles(
      Set.of("config.json", "model.safetensors", "original/consolidated.00.pth"),
      List.of("*.pth")
    );

    assertThat(selected).containsExactly("config.json", "model.safetensors");
  }

  @Test
  void a_pattern_with_a_slash_is_anchored_at_the_repo_root() {
    // "original/*" must not reach a same-named file elsewhere in the tree.
    var selected = VllmModelResolver.selectFiles(
      Set.of(
        "config.json",
        "model.safetensors",
        "original/params.json",
        "nested/original/keep.json"
      ),
      List.of("original/*")
    );

    assertThat(selected).contains("nested/original/keep.json");
    assertThat(selected).doesNotContain("original/params.json");
  }

  @Test
  void excluding_the_safetensors_falls_back_to_the_bin_weights() {
    // The format is chosen *after* exclusions, so this yields a loadable model
    // rather than metadata with no weights at all.
    var selected = VllmModelResolver.selectFiles(
      Set.of("config.json", "model.safetensors", "pytorch_model.bin"),
      List.of("*.safetensors")
    );

    assertThat(selected).containsExactly("config.json", "pytorch_model.bin");
  }

  @Test
  void excludes_never_re_admit_a_format_the_engine_cannot_read() {
    // Excludes only narrow: naming a .gguf here must not pull it back in.
    var selected = VllmModelResolver.selectFiles(
      Set.of("config.json", "model.safetensors", "model-Q4_K_M.gguf"),
      List.of("nothing-matches-this")
    );

    assertThat(selected).containsExactly("config.json", "model.safetensors");
  }

  @Test
  void trims_gpt_oss_20b_from_275_to_138_gb() {
    // The real openai/gpt-oss-20b listing. It carries the same weights three
    // times: the sharded safetensors vLLM loads, original/model.safetensors
    // (13.8 GB), and metal/model.bin for Apple's runtime (13.7 GB).
    var repo = Set.of(
      "config.json",
      "generation_config.json",
      "model-00000-of-00002.safetensors",
      "model-00001-of-00002.safetensors",
      "model-00002-of-00002.safetensors",
      "model.safetensors.index.json",
      "tokenizer.json",
      "tokenizer_config.json",
      "special_tokens_map.json",
      "chat_template.jinja",
      "original/model.safetensors",
      "original/config.json",
      "original/dtypes.json",
      "metal/model.bin",
      "README.md",
      "LICENSE",
      "USAGE_POLICY",
      ".gitattributes"
    );

    // metal/model.bin needs no exclude — .bin loses to safetensors already.
    assertThat(VllmModelResolver.selectFiles(repo)).doesNotContain("metal/model.bin");
    // original/ is safetensors too, so only an exclude can drop it.
    assertThat(VllmModelResolver.selectFiles(repo)).contains("original/model.safetensors");

    var selected = VllmModelResolver.selectFiles(repo, List.of("original/*"));

    // Exhaustive, not "contains": the risk an exclude carries is dropping
    // something vLLM needs — a tokenizer file or the safetensors index — and
    // that failure only surfaces later, inside the engine. Every file the
    // engine loads is listed here, and nothing else is downloaded.
    assertThat(selected).containsExactlyInAnyOrder(
      "config.json",
      "generation_config.json",
      "model-00000-of-00002.safetensors",
      "model-00001-of-00002.safetensors",
      "model-00002-of-00002.safetensors",
      "model.safetensors.index.json",
      "tokenizer.json",
      "tokenizer_config.json",
      "special_tokens_map.json",
      "chat_template.jinja"
    );
  }

  @Test
  void an_exclude_removes_only_what_it_matches() {
    // The general property behind the gpt-oss case: whatever the built-in rules
    // selected stays selected, minus exactly the files the pattern names. A
    // pattern that quietly took a neighbouring file with it would show up here.
    var repo = Set.of(
      "config.json",
      "model-00000-of-00002.safetensors",
      "model-00001-of-00002.safetensors",
      "model-00002-of-00002.safetensors",
      "model.safetensors.index.json",
      "tokenizer.json",
      "tokenizer_config.json",
      "special_tokens_map.json",
      "generation_config.json",
      "chat_template.jinja",
      "original/model.safetensors",
      "original/config.json",
      "original/dtypes.json",
      "metal/model.bin"
    );

    var baseline = VllmModelResolver.selectFiles(repo);
    var withExclude = VllmModelResolver.selectFiles(repo, List.of("original/*"));

    var expected = baseline
      .stream()
      .filter(f -> !f.startsWith("original/"))
      .toList();

    assertThat(withExclude).isEqualTo(expected);
  }

  @Test
  void an_empty_exclude_list_changes_nothing() {
    var files = Set.of("config.json", "model.safetensors", "tokenizer.json");

    assertThat(VllmModelResolver.selectFiles(files, List.of())).isEqualTo(
      VllmModelResolver.selectFiles(files)
    );
  }
}
