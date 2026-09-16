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
 * The completion marker for multi-file cache entries: only a finished download counts, so an
 * interrupted one is repaired on the next start instead of being loaded half-empty.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class CacheMarkerTest {

  @Test
  void a_directory_holding_files_is_not_complete_on_its_own(@TempDir Path dir) throws Exception {
    // a sharded checkpoint missing a shard, or a bundle missing its scorer, looks like this
    touch(dir, "config.json");
    touch(dir, "model-00001-of-00003.safetensors");
    assertThat(CacheMarker.isComplete(dir, null)).isFalse();
  }

  @Test
  void the_marker_makes_it_complete(@TempDir Path dir) throws Exception {
    touch(dir, "config.json");
    CacheMarker.mark(dir, null, List.of(dir.resolve("config.json")));
    assertThat(CacheMarker.isComplete(dir, null)).isTrue();
  }

  @Test
  void keys_are_marked_independently(@TempDir Path dir) throws Exception {
    CacheMarker.mark(dir, "onnx", List.of());
    assertThat(CacheMarker.isComplete(dir, "onnx")).isTrue();
    assertThat(CacheMarker.isComplete(dir, "q8_0")).isFalse();
    assertThat(CacheMarker.isComplete(dir, null)).isFalse();

    CacheMarker.mark(dir, "q8_0", List.of());
    assertThat(CacheMarker.isComplete(dir, "q8_0")).isTrue();
    assertThat(CacheMarker.isComplete(dir, "onnx")).isTrue();
  }

  @Test
  void the_marker_lists_what_was_downloaded(@TempDir Path dir) throws Exception {
    CacheMarker.mark(dir, null, List.of(dir.resolve("b.bin"), dir.resolve("a.bin")));
    assertThat(Files.readString(dir.resolve(".complete"))).isEqualTo(
      dir.resolve("a.bin") + "\n" + dir.resolve("b.bin")
    );
  }

  @Test
  void a_key_cannot_escape_the_cache_directory(@TempDir Path dir) throws Exception {
    CacheMarker.mark(dir, "../evil", List.of());
    assertThat(CacheMarker.isComplete(dir, "../evil")).isTrue();
    assertThat(
      Files.list(dir)
        .map(p -> p.getFileName().toString())
        .toList()
    ).containsExactly(".complete-.._evil");
  }

  @Test
  void an_unwritable_directory_is_reported_as_incomplete(@TempDir Path dir) {
    Path missing = dir.resolve("never-created");
    CacheMarker.mark(missing, null, List.of());
    assertThat(CacheMarker.isComplete(missing, null)).isFalse();
  }

  private static void touch(Path dir, String file) throws Exception {
    Files.writeString(dir.resolve(file), "");
  }
}
