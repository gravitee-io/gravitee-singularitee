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
package io.gravitee.singularitee.adapter.textgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.adapter.textgen.VllmTextGenEngine.DefaultLora;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.workspace.config.VllmConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model-level default LoRA adapter of a vLLM model ({@code vllm.lora_path}).
 *
 * <p>The failure this guards against is silent: an adapter that is configured but never applied leaves every
 * answer coming from the base model, and nothing in the output says so. So the adapter is refused before vLLM
 * starts unless it can be served, and every request that names no adapter of its own gets it.
 *
 * <p>Only the resolution and the checks are exercised here; constructing a real engine would start CPython.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class VllmDefaultLoraTest {

  private static VllmConfig.Builder lora(String path) {
    return VllmConfig.newBuilder().setEnableLora(true).setLoraPath(path);
  }

  private static Path adapter(Path dir, int rank) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(
      dir.resolve("adapter_config.json"),
      "{\"r\": " + rank + ", \"lora_alpha\": 32}"
    );
    return dir;
  }

  private static TextGenRequest request(String loraName, String loraPath) {
    return new TextGenRequest(
      "p",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      loraName,
      loraPath
    );
  }

  @Test
  void no_lora_path_means_no_default_adapter() {
    assertThat(VllmEngineFactory.defaultLora("m", VllmConfig.getDefaultInstance(), null)).isNull();
  }

  @Test
  void a_served_adapter_is_named_and_absolute(@TempDir Path tmp) throws IOException {
    Path dir = adapter(tmp.resolve("adapter"), 16);
    var lora = VllmEngineFactory.defaultLora(
      "m",
      lora("adapter").setLoraName("sft16").build(),
      dir
    );

    assertThat(lora.name()).isEqualTo("sft16");
    assertThat(Path.of(lora.path())).isAbsolute().isEqualTo(dir.toAbsolutePath());
    assertThat(VllmEngineFactory.defaultLora("m", lora("adapter").build(), dir).name()).isEqualTo(
      "default"
    );
  }

  @Test
  void enable_lora_is_required(@TempDir Path tmp) throws IOException {
    var cfg = VllmConfig.newBuilder().setLoraPath("adapter").build();

    assertThatThrownBy(() ->
      VllmEngineFactory.defaultLora("m", cfg, adapter(tmp.resolve("adapter"), 16))
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("enable_lora");
  }

  @Test
  void a_directory_without_a_peft_adapter_is_refused(@TempDir Path tmp) throws IOException {
    // A GGUF LoRA (llama.cpp's format) next to nothing vLLM can read.
    Files.writeString(tmp.resolve("adapter.gguf"), "gguf");

    assertThatThrownBy(() -> VllmEngineFactory.defaultLora("m", lora(".").build(), tmp))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("adapter_config.json");
  }

  @Test
  void a_rank_above_max_lora_rank_is_refused(@TempDir Path tmp) throws IOException {
    Path dir = adapter(tmp.resolve("adapter"), 64);

    // vLLM's own default is 16, so an unset max_lora_rank refuses a rank-64 adapter too.
    assertThatThrownBy(() ->
      VllmEngineFactory.defaultLora("m", lora("adapter").build(), dir)
    ).hasMessageContaining("max_lora_rank 16");
    assertThat(
      VllmEngineFactory.defaultLora("m", lora("adapter").setMaxLoraRank(64).build(), dir)
    ).isNotNull();
  }

  @Test
  void lora_path_resolves_inside_the_downloaded_repository_unless_absolute(@TempDir Path tmp) {
    assertThat(VllmEngineFactory.loraDirectory(lora("adapter").build(), tmp)).isEqualTo(
      tmp.resolve("adapter")
    );
    Path elsewhere = tmp.resolve("elsewhere").toAbsolutePath();
    assertThat(VllmEngineFactory.loraDirectory(lora(elsewhere.toString()).build(), tmp)).isEqualTo(
      elsewhere
    );
  }

  @Test
  void a_request_without_an_adapter_gets_the_default_and_its_own_one_wins() {
    var lora = new DefaultLora("sft16", "/models/adapter");

    assertThat(VllmTextGenEngine.loraName(request(null, null), lora)).isEqualTo("sft16");
    assertThat(VllmTextGenEngine.loraPath(request(null, null), lora)).isEqualTo("/models/adapter");
    assertThat(VllmTextGenEngine.loraPath(request("other", "/other"), lora)).isEqualTo("/other");
    assertThat(VllmTextGenEngine.loraName(request("other", "/other"), lora)).isEqualTo("other");
    assertThat(VllmTextGenEngine.loraPath(request(null, null), null)).isNull();
  }
}
