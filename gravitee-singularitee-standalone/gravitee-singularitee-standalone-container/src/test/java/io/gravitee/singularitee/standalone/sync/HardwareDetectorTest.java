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
package io.gravitee.singularitee.standalone.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HardwareDetectorTest {

  @TempDir
  Path tmp;

  /** Canned output per command line; anything else is "not installed". */
  private static HardwareDetector.Commands commands(Map<String, String> outputs) {
    return command -> Optional.ofNullable(outputs.get(String.join(" ", command)));
  }

  @Test
  void apple_silicon_reports_the_chip_cores_and_a_metal_gpu_with_unified_memory() {
    var hw = HardwareDetector.detect(
      commands(
        Map.of(
          "sysctl -n machdep.cpu.brand_string",
          "Apple M4 Pro\n",
          "sysctl -n hw.physicalcpu",
          "12",
          "system_profiler SPDisplaysDataType -json",
          """
          {"SPDisplaysDataType":[{"_name":"Apple M4 Pro","sppci_model":"Apple M4 Pro","sppci_cores":"20","spdisplays_vendor":"sppci_vendor_Apple"}]}
          """
        )
      ),
      "Mac OS X",
      "26.3",
      "aarch64",
      12,
      tmp.resolve("none")
    );

    assertThat(hw.os()).isEqualTo("Mac OS X 26.3");
    assertThat(hw.cpu()).isEqualTo(
      new HardwareInfo.Cpu("aarch64", "Apple M4 Pro", 12, 12, List.of("neon"))
    );
    assertThat(hw.gpus()).containsExactly(
      new HardwareInfo.Gpu("Apple", "Apple M4 Pro", null, null, null, "Metal", 20)
    );
  }

  @Test
  void linux_with_nvidia_reports_flags_physical_cores_and_the_cuda_capability() throws Exception {
    Path cpuInfo = tmp.resolve("cpuinfo");
    Files.writeString(
      cpuInfo,
      """
      processor\t: 0
      physical id\t: 0
      model name\t: AMD EPYC 7763 64-Core Processor
      core id\t: 0
      flags\t\t: fpu sse2 avx avx2 fma avx512f avx512_vnni foo
      processor\t: 1
      physical id\t: 0
      model name\t: AMD EPYC 7763 64-Core Processor
      core id\t: 0
      flags\t\t: fpu sse2 avx avx2 fma avx512f avx512_vnni foo
      processor\t: 2
      physical id\t: 0
      model name\t: AMD EPYC 7763 64-Core Processor
      core id\t: 1
      flags\t\t: fpu sse2 avx avx2 fma avx512f avx512_vnni foo
      """
    );

    var hw = HardwareDetector.detect(
      commands(
        Map.of(
          "nvidia-smi --query-gpu=name,memory.total,driver_version,compute_cap --format=csv,noheader,nounits",
          "NVIDIA A100-SXM4-80GB, 81920, 550.54.15, 8.0\nNVIDIA A100-SXM4-80GB, 81920, 550.54.15, 8.0"
        )
      ),
      "Linux",
      "6.8.0",
      "amd64",
      3,
      cpuInfo
    );

    assertThat(hw.cpu().model()).isEqualTo("AMD EPYC 7763 64-Core Processor");
    assertThat(hw.cpu().physicalCores()).isEqualTo(2);
    assertThat(hw.cpu().features()).containsExactly("avx", "avx2", "avx512_vnni", "avx512f", "fma");
    assertThat(hw.gpus()).hasSize(2);
    assertThat(hw.gpus().get(0)).isEqualTo(
      new HardwareInfo.Gpu(
        "NVIDIA",
        "NVIDIA A100-SXM4-80GB",
        81920L,
        "550.54.15",
        "8.0",
        "CUDA",
        null
      )
    );
  }

  @Test
  void an_older_nvidia_driver_without_compute_cap_is_still_detected() {
    var hw = HardwareDetector.detect(
      commands(
        Map.of(
          "nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits",
          "Tesla T4, 15360, 470.82.01"
        )
      ),
      "Linux",
      "5.15",
      "amd64",
      4,
      tmp.resolve("none")
    );

    assertThat(hw.gpus())
      .singleElement()
      .satisfies(gpu -> {
        assertThat(gpu.name()).isEqualTo("Tesla T4");
        assertThat(gpu.memoryMb()).isEqualTo(15360L);
        assertThat(gpu.computeCapability()).isNull();
      });
  }

  @Test
  void a_machine_with_no_tools_and_no_cpuinfo_still_reports_what_the_jvm_knows() {
    var hw = HardwareDetector.detect(
      commands(Map.of()),
      "Linux",
      "6.1",
      "aarch64",
      8,
      tmp.resolve("none")
    );

    assertThat(hw.cpu()).isEqualTo(new HardwareInfo.Cpu("aarch64", null, 8, null, List.of()));
    assertThat(hw.gpus()).isEmpty();
  }

  @Test
  void unreadable_tool_output_is_ignored() {
    assertThat(HardwareDetector.parseSystemProfiler("not json")).isEmpty();
    assertThat(HardwareDetector.parseNvidiaSmi("garbage\n,,\n")).isEmpty();
  }

  @Test
  void a_discrete_mac_gpu_keeps_its_vendor_and_memory() {
    var gpus = HardwareDetector.parseSystemProfiler(
      """
      {"SPDisplaysDataType":[{"sppci_model":"AMD Radeon Pro 5500M","spdisplays_vendor":"sppci_vendor_amd","spdisplays_vram":"8 GB"}]}
      """
    );

    assertThat(gpus)
      .singleElement()
      .satisfies(gpu -> {
        assertThat(gpu.vendor()).isEqualTo("AMD");
        assertThat(gpu.memoryMb()).isEqualTo(8192L);
      });
  }
}
