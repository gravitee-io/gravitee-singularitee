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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File names come from the HuggingFace API response — i.e. from whoever controls the
 * repository a workspace names. They must never escape the model cache directory.
 */
class HuggingFaceModelDownloaderPathTest {

  @Test
  void ordinary_names_resolve_under_the_target(@TempDir Path tmp) {
    assertThat(HuggingFaceModelDownloader.resolveWithinTarget(tmp, "model.onnx")).isEqualTo(
      tmp.toAbsolutePath().normalize().resolve("model.onnx")
    );
  }

  @Test
  void nested_names_are_allowed(@TempDir Path tmp) {
    // Real repositories ship subdirectories, e.g. onnx/model.onnx.
    Path resolved = HuggingFaceModelDownloader.resolveWithinTarget(tmp, "onnx/model.onnx");
    assertThat(resolved.startsWith(tmp.toAbsolutePath().normalize())).isTrue();
    assertThat(resolved.getFileName().toString()).isEqualTo("model.onnx");
  }

  @Test
  void traversal_out_of_the_target_is_refused(@TempDir Path tmp) {
    assertThatThrownBy(() ->
      HuggingFaceModelDownloader.resolveWithinTarget(tmp, "../../.ssh/authorized_keys")
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("outside the model directory");
  }

  @Test
  void traversal_hidden_mid_path_is_refused(@TempDir Path tmp) {
    assertThatThrownBy(() ->
      HuggingFaceModelDownloader.resolveWithinTarget(tmp, "onnx/../../../etc/passwd")
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void absolute_names_are_refused(@TempDir Path tmp) {
    // resolve() against an absolute path discards the base entirely.
    assertThatThrownBy(() ->
      HuggingFaceModelDownloader.resolveWithinTarget(tmp, "/etc/passwd")
    ).isInstanceOf(IllegalArgumentException.class);
  }
}
