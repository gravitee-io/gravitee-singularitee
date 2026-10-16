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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workspace model names and file paths join the cache without escaping it. */
class CachePathsTest {

  @Test
  void a_repository_id_lands_under_the_cache(@TempDir Path cache) {
    assertThat(CachePaths.under(cache, "Qwen/Qwen3-0.6B-GGUF")).isEqualTo(
      cache.toAbsolutePath().normalize().resolve("Qwen/Qwen3-0.6B-GGUF")
    );
  }

  @Test
  void an_absolute_path_is_kept(@TempDir Path cache) {
    Path elsewhere = Path.of("/opt/models/llm");
    assertThat(CachePaths.under(cache, elsewhere.toString())).isEqualTo(elsewhere);
  }

  @Test
  void a_relative_escape_is_refused(@TempDir Path cache) {
    assertThatThrownBy(() -> CachePaths.under(cache, "../../.ssh")).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> CachePaths.under(cache, "acme/../../outside")).isInstanceOf(
      IllegalArgumentException.class
    );
  }

  @Test
  void the_gguf_resolver_refuses_a_file_path_that_escapes_the_repository(@TempDir Path cache) {
    var resolver = new GgufModelResolver(mock(HuggingFaceModelDownloader.class), cache);

    assertThatThrownBy(() -> resolver.resolve("acme/model", "../../elsewhere/x.gguf")).isInstanceOf(
      IllegalArgumentException.class
    );
  }

  @Test
  void the_vllm_resolver_refuses_a_name_that_escapes_the_cache(@TempDir Path cache) {
    new VllmModelResolver(mock(HuggingFaceModelDownloader.class), cache)
      .resolve("../outside/model")
      .test()
      .assertError(IllegalArgumentException.class);
  }
}
