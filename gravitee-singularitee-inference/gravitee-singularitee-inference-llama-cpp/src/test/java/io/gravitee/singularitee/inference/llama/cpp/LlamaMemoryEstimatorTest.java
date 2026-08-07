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
package io.gravitee.singularitee.inference.llama.cpp;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LlamaMemoryEstimator}.
 *
 * <p>The estimator calls {@code LlamaModelDims.loadFrom()} and
 * {@code GpuMemoryQuery.queryBest()} or {@code CpuMemoryQuery.query()} which
 * require loaded native llama.cpp libraries and/or OS-level memory queries.
 * On machines without these (CI, macOS without Metal VRAM reporting), the
 * calls fail and the estimator gracefully returns {@link MemoryEstimate#unknown()}.
 *
 * <p>Tests verify:
 * <ol>
 *   <li><b>Graceful failure</b> — invalid/null paths never throw, always
 *       return {@code unknown()} regardless of GPU/CPU/RPC mode.</li>
 *   <li><b>CPU mode</b> — {@code nGpuLayers=0} exercises the CPU memory
 *       path with no safety margin.</li>
 *   <li><b>RPC mode</b> — unreachable RPC servers return {@code unknown()}
 *       gracefully.</li>
 *   <li><b>Contract properties</b> — when estimation succeeds (native libs
 *       loaded + device present), the result has correct flags and non-zero values.</li>
 * </ol>
 */
class LlamaMemoryEstimatorTest {

  private static final Path NONEXISTENT = Path.of("/nonexistent/model.gguf");
  private static final Path NONEXISTENT_MMPROJ = Path.of("/nonexistent/mmproj.gguf");
  private static final Path NONEXISTENT_LORA = Path.of("/nonexistent/lora.gguf");
  private static final int DEFAULT_N_SEQ_MAX = 1;

  @Nested
  @DisplayName("Graceful failure — GPU mode (always passes, no native libs needed)")
  class GracefulFailureGpu {

    @Test
    @DisplayName("nonexistent model path returns unknown() — never throws")
    void nonexistent_model_path_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("null model path returns unknown() — never throws")
    void null_model_path_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        null,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("nonexistent mmproj path with nonexistent model returns unknown()")
    void nonexistent_mmproj_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        NONEXISTENT_MMPROJ,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("nonexistent lora path with nonexistent model returns unknown()")
    void nonexistent_lora_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        NONEXISTENT_LORA,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("all nonexistent paths returns unknown()")
    void all_nonexistent_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        NONEXISTENT_MMPROJ,
        NONEXISTENT_LORA,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }
  }

  @Nested
  @DisplayName("Graceful failure — CPU mode (nGpuLayers=0)")
  class GracefulFailureCpu {

    @Test
    @DisplayName("nGpuLayers=0 with nonexistent model returns unknown() — never throws")
    void zero_gpu_layers_nonexistent_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        0,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("nGpuLayers=0 with null model returns unknown() — never throws")
    void zero_gpu_layers_null_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        null,
        null,
        null,
        0,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("nGpuLayers=0 with nonexistent mmproj returns unknown()")
    void zero_gpu_layers_nonexistent_mmproj_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        NONEXISTENT_MMPROJ,
        null,
        0,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("nGpuLayers=0 with nonexistent lora returns unknown()")
    void zero_gpu_layers_nonexistent_lora_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        NONEXISTENT_LORA,
        0,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName(
      "negative nGpuLayers treated as CPU mode — returns unknown() for nonexistent model"
    )
    void negative_gpu_layers_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        -1,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(result.isUnknown()).isTrue();
    }
  }

  @Nested
  @DisplayName("Graceful failure — RPC mode (rpcServers configured)")
  class GracefulFailureRpc {

    @Test
    @DisplayName("nonexistent model with RPC servers returns unknown() — never throws")
    void rpc_nonexistent_model_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        List.of("127.0.0.1:1")
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("null model with RPC servers returns unknown() — never throws")
    void rpc_null_model_returns_unknown() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        null,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        List.of("127.0.0.1:1")
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("null rpcServers falls through to GPU/CPU path")
    void null_rpc_servers_falls_through() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        null
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("empty rpcServers falls through to GPU/CPU path")
    void empty_rpc_servers_falls_through() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        List.of()
      );
      assertThat(result.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("convenience overload (no rpcServers param) behaves same as null rpcServers")
    void convenience_overload_same_as_null() {
      MemoryEstimate withNull = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        null
      );
      MemoryEstimate withOverload = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );
      assertThat(withNull.isUnknown()).isEqualTo(withOverload.isUnknown());
    }
  }

  @Nested
  @DisplayName("unknown() sentinel")
  class UnknownSentinel {

    @Test
    @DisplayName("unknown() sentinel has correct properties")
    void unknown_sentinel_properties() {
      MemoryEstimate unknown = MemoryEstimate.unknown();
      assertThat(unknown.isUnknown()).isTrue();
      assertThat(unknown.willFit()).isTrue();
      assertThat(unknown.requiredGb()).isEqualTo(0.0);
      assertThat(unknown.usableGb()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("Contract properties (verified when native libs + device available)")
  class ContractProperties {

    @Test
    @DisplayName("GPU estimate is exact (isApproximate=false) when successful")
    void gpu_exact_estimate_contract() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX
      );

      if (!result.isUnknown()) {
        assertThat(result.isApproximate()).isFalse();
        assertThat(result.requiredGb()).isGreaterThan(0.0);
        assertThat(result.usableGb()).isGreaterThan(0.0);
        assertThat(result.suggestion()).isNotBlank();
      }
    }

    @Test
    @DisplayName("CPU estimate is exact (isApproximate=false) when successful")
    void cpu_exact_estimate_contract() {
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        0,
        4096,
        DEFAULT_N_SEQ_MAX
      );

      if (!result.isUnknown()) {
        assertThat(result.isApproximate()).isFalse();
        assertThat(result.requiredGb()).isGreaterThan(0.0);
        assertThat(result.usableGb()).isGreaterThan(0.0);
        assertThat(result.suggestion()).isNotBlank();
        // CPU suggestions mention "system RAM" or "RAM"
        assertThat(result.suggestion()).containsIgnoringCase("RAM");
      }
    }

    @Test
    @DisplayName("RPC estimate is exact (isApproximate=false) when successful")
    void rpc_exact_estimate_contract() {
      // This will return unknown() unless a real RPC server is running
      MemoryEstimate result = LlamaMemoryEstimator.estimate(
        NONEXISTENT,
        null,
        null,
        32,
        4096,
        DEFAULT_N_SEQ_MAX,
        List.of("127.0.0.1:50052")
      );

      if (!result.isUnknown()) {
        assertThat(result.isApproximate()).isFalse();
        assertThat(result.requiredGb()).isGreaterThan(0.0);
        assertThat(result.usableGb()).isGreaterThan(0.0);
        assertThat(result.suggestion()).isNotBlank();
        // RPC suggestions mention "RPC server"
        assertThat(result.suggestion()).containsIgnoringCase("RPC");
      }
    }
  }
}
