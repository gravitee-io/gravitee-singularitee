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
package io.gravitee.singularitee.inference.vllm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VllmConfigTest {

  @Nested
  @DisplayName("Compact constructor validation")
  class Validation {

    @Test
    @DisplayName("null model throws IllegalArgumentException")
    void null_model_throws() {
      assertThatThrownBy(() -> buildConfig(null, "auto", MemoryCheckPolicy.WARN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model must not be null or blank");
    }

    @Test
    @DisplayName("blank model throws IllegalArgumentException")
    void blank_model_throws() {
      assertThatThrownBy(() -> buildConfig("   ", "auto", MemoryCheckPolicy.WARN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model must not be null or blank");
    }

    @Test
    @DisplayName("empty model throws IllegalArgumentException")
    void empty_model_throws() {
      assertThatThrownBy(() -> buildConfig("", "auto", MemoryCheckPolicy.WARN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model must not be null or blank");
    }
  }

  @Nested
  @DisplayName("Default values")
  class Defaults {

    @Test
    @DisplayName("null dtype defaults to 'auto'")
    void null_dtype_defaults_to_auto() {
      VllmConfig config = buildConfig("Qwen/Qwen3-0.6B", null, MemoryCheckPolicy.FAIL);
      assertThat(config.dtype()).isEqualTo("auto");
    }

    @Test
    @DisplayName("blank dtype defaults to 'auto'")
    void blank_dtype_defaults_to_auto() {
      VllmConfig config = buildConfig("Qwen/Qwen3-0.6B", "  ", MemoryCheckPolicy.FAIL);
      assertThat(config.dtype()).isEqualTo("auto");
    }

    @Test
    @DisplayName("null memoryCheckPolicy defaults to WARN")
    void null_policy_defaults_to_warn() {
      VllmConfig config = buildConfig("Qwen/Qwen3-0.6B", "bfloat16", null);
      assertThat(config.memoryCheckPolicy()).isEqualTo(MemoryCheckPolicy.WARN);
    }
  }

  @Nested
  @DisplayName("Valid construction")
  class ValidConstruction {

    @Test
    @DisplayName("all fields are preserved")
    void all_fields_preserved() {
      VllmConfig config = new VllmConfig(
        "meta-llama/Llama-3.1-8B",
        null,
        "bfloat16",
        4096,
        32,
        0.9,
        8192,
        false,
        true,
        "awq",
        4.0,
        42,
        true,
        false,
        "fp8",
        true,
        4,
        64,
        Path.of("/opt/venv"),
        MemoryCheckPolicy.FAIL,
        8030261248L,
        2,
        32,
        8,
        128,
        false,
        131072,
        "hf_abc123",
        null,
        0,
        0,
        null
      );

      assertThat(config.model()).isEqualTo("meta-llama/Llama-3.1-8B");
      assertThat(config.dtype()).isEqualTo("bfloat16");
      assertThat(config.maxModelLen()).isEqualTo(4096);
      assertThat(config.maxNumSeqs()).isEqualTo(32);
      assertThat(config.gpuMemoryUtilization()).isEqualTo(0.9);
      assertThat(config.maxNumBatchedTokens()).isEqualTo(8192);
      assertThat(config.enforceEager()).isFalse();
      assertThat(config.trustRemoteCode()).isTrue();
      assertThat(config.quantization()).isEqualTo("awq");
      assertThat(config.swapSpace()).isEqualTo(4.0);
      assertThat(config.seed()).isEqualTo(42);
      assertThat(config.enablePrefixCaching()).isTrue();
      assertThat(config.enableChunkedPrefill()).isFalse();
      assertThat(config.kvCacheDtype()).isEqualTo("fp8");
      assertThat(config.enableLora()).isTrue();
      assertThat(config.maxLoras()).isEqualTo(4);
      assertThat(config.maxLoraRank()).isEqualTo(64);
      assertThat(config.venvPath()).isEqualTo(Path.of("/opt/venv"));
      assertThat(config.memoryCheckPolicy()).isEqualTo(MemoryCheckPolicy.FAIL);
      assertThat(config.totalParams()).isEqualTo(8030261248L);
      assertThat(config.bytesPerParam()).isEqualTo(2);
      assertThat(config.numHiddenLayers()).isEqualTo(32);
      assertThat(config.numKvHeads()).isEqualTo(8);
      assertThat(config.headDim()).isEqualTo(128);
      assertThat(config.multimodal()).isFalse();
      assertThat(config.maxPositionEmbeddings()).isEqualTo(131072);
      assertThat(config.hfToken()).isEqualTo("hf_abc123");
    }

    @Test
    @DisplayName("minimal config with required fields only")
    void minimal_config() {
      VllmConfig config = buildConfig("Qwen/Qwen3-0.6B", "auto", MemoryCheckPolicy.DISABLED);
      assertThat(config.model()).isEqualTo("Qwen/Qwen3-0.6B");
      assertThat(config.dtype()).isEqualTo("auto");
      assertThat(config.memoryCheckPolicy()).isEqualTo(MemoryCheckPolicy.DISABLED);
    }
  }

  // --- helper ---

  private static VllmConfig buildConfig(String model, String dtype, MemoryCheckPolicy policy) {
    return new VllmConfig(
      model,
      null,
      dtype,
      0,
      0,
      0.0,
      0,
      false,
      false,
      null,
      0.0,
      null,
      false,
      false,
      null,
      false,
      0,
      0,
      null,
      policy,
      0,
      0,
      0,
      0,
      0,
      false,
      0,
      null,
      null,
      0,
      0,
      null
    );
  }
}
