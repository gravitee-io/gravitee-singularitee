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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a checkpoint's input modalities out of its {@code config.json}. */
class CheckpointModalitiesTest {

  @Test
  void a_plain_language_model_reads_text_only(@TempDir Path dir) throws IOException {
    writeConfig(
      dir,
      """
      {"architectures":["Qwen3ForCausalLM"],"num_hidden_layers":28}
      """
    );

    assertThat(CheckpointModalities.read(dir, "qwen3")).containsExactly("text");
  }

  @Test
  void a_vision_config_makes_it_a_vlm(@TempDir Path dir) throws IOException {
    writeConfig(
      dir,
      """
      {"architectures":["Qwen3VLForConditionalGeneration"],
       "text_config":{"num_hidden_layers":28},
       "vision_config":{"depth":24}}
      """
    );

    assertThat(CheckpointModalities.read(dir, "qwen3-vl")).containsExactly("text", "image");
  }

  @Test
  void an_audio_config_makes_it_an_alm(@TempDir Path dir) throws IOException {
    writeConfig(
      dir,
      """
      {"architectures":["VoxtralForConditionalGeneration"],
       "audio_config":{"num_mel_bins":128}}
      """
    );

    assertThat(CheckpointModalities.read(dir, "voxtral")).containsExactly("text", "audio");
  }

  @Test
  void both_blocks_are_reported_together(@TempDir Path dir) throws IOException {
    writeConfig(
      dir,
      """
      {"vision_config":{"depth":24},"audio_config":{"num_mel_bins":128}}
      """
    );

    assertThat(CheckpointModalities.read(dir, "omni")).containsExactly("text", "image", "audio");
  }

  @Test
  void an_empty_block_is_not_a_capability(@TempDir Path dir) throws IOException {
    // A key present but empty says nothing was configured — treating it as a
    // projector would advertise a capability the model does not have.
    writeConfig(
      dir,
      """
      {"vision_config":{},"audio_config":null}
      """
    );

    assertThat(CheckpointModalities.read(dir, "empty-blocks")).containsExactly("text");
  }

  @Test
  void a_missing_config_falls_back_to_text_only(@TempDir Path dir) {
    assertThat(CheckpointModalities.read(dir, "no-config")).containsExactly("text");
    assertThat(CheckpointModalities.read(null, "unresolved")).containsExactly("text");
  }

  @Test
  void malformed_json_falls_back_to_text_only(@TempDir Path dir) throws IOException {
    writeConfig(dir, "{ not json");

    assertThat(CheckpointModalities.read(dir, "broken")).containsExactly("text");
  }

  private static void writeConfig(Path dir, String json) throws IOException {
    Files.writeString(dir.resolve("config.json"), json);
  }
}
