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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the CPU and accelerators of the local machine. Like the vLLM engine, it shells out
 * ({@code nvidia-smi}, {@code sysctl}, {@code system_profiler}) because the JVM exposes none of it
 * and these tools ship with the driver or the OS. A missing tool, a timeout or unparseable output
 * only leaves that part empty. AMD and Intel GPUs are not detected on Linux yet.
 */
final class HardwareDetector {

  private static final Logger LOGGER = LoggerFactory.getLogger(HardwareDetector.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int TIMEOUT_SECONDS = 5;

  /** Instruction sets that decide how fast CPU inference runs. */
  private static final Set<String> CPU_FEATURES = Set.of(
    "avx",
    "avx2",
    "avx512f",
    "avx512_vnni",
    "avx512_bf16",
    "amx_tile",
    "amx_int8",
    "fma",
    "neon",
    "asimd",
    "sve",
    "sve2"
  );

  /** Runs a command and returns its standard output, or empty on any failure. */
  interface Commands {
    Optional<String> run(String... command);
  }

  private HardwareDetector() {}

  static HardwareInfo detect() {
    try {
      return detect(
        new ProcessCommands(),
        System.getProperty("os.name", ""),
        System.getProperty("os.version", ""),
        System.getProperty("os.arch", ""),
        Runtime.getRuntime().availableProcessors(),
        Path.of("/proc/cpuinfo")
      );
    } catch (RuntimeException e) {
      LOGGER.warn("Hardware detection failed: {}", e.toString());
      return new HardwareInfo(null, null, List.of());
    }
  }

  static HardwareInfo detect(
    Commands commands,
    String osName,
    String osVersion,
    String arch,
    int logicalCores,
    Path cpuInfo
  ) {
    String os = (osName + " " + osVersion).trim();
    boolean mac = osName.toLowerCase(Locale.ROOT).contains("mac");
    boolean linux = osName.toLowerCase(Locale.ROOT).contains("linux");
    HardwareInfo.Cpu cpu = mac
      ? macCpu(commands, arch, logicalCores)
      : linux
        ? linuxCpu(cpuInfo, arch, logicalCores)
        : new HardwareInfo.Cpu(arch, null, logicalCores, null, List.of());
    List<HardwareInfo.Gpu> gpus = new ArrayList<>(nvidia(commands));
    if (mac) {
      gpus.addAll(macGpus(commands));
    }
    return new HardwareInfo(os.isEmpty() ? null : os, cpu, List.copyOf(gpus));
  }

  // ---- macOS

  private static HardwareInfo.Cpu macCpu(Commands commands, String arch, int logicalCores) {
    String model = commands
      .run("sysctl", "-n", "machdep.cpu.brand_string")
      .map(String::trim)
      .orElse(null);
    Integer physical = commands
      .run("sysctl", "-n", "hw.physicalcpu")
      .map(String::trim)
      .map(HardwareDetector::toInt)
      .orElse(null);
    List<String> features = new ArrayList<>();
    if (arch.contains("aarch64") || arch.contains("arm")) {
      features.add("neon");
    } else {
      commands
        .run("sysctl", "-n", "machdep.cpu.features", "machdep.cpu.leaf7_features")
        .ifPresent(out -> features.addAll(relevantFeatures(out)));
    }
    return new HardwareInfo.Cpu(
      arch,
      model,
      logicalCores,
      physical,
      features.stream().distinct().sorted().toList()
    );
  }

  private static List<HardwareInfo.Gpu> macGpus(Commands commands) {
    return commands
      .run("system_profiler", "SPDisplaysDataType", "-json")
      .map(HardwareDetector::parseSystemProfiler)
      .orElse(List.of());
  }

  /** {@code system_profiler SPDisplaysDataType -json}: Apple Silicon reports cores and shares system memory. */
  static List<HardwareInfo.Gpu> parseSystemProfiler(String json) {
    List<HardwareInfo.Gpu> gpus = new ArrayList<>();
    try {
      for (JsonNode d : JSON.readTree(json).path("SPDisplaysDataType")) {
        String vendor = vendor(
          d.path("spdisplays_vendor").asText(d.path("sppci_vendor").asText(""))
        );
        String name = d.path("sppci_model").asText(d.path("_name").asText(null));
        Integer cores = toInt(d.path("sppci_cores").asText(null));
        boolean apple = "Apple".equals(vendor);
        Long memory = apple ? null : vramMb(d.path("spdisplays_vram").asText(null));
        gpus.add(new HardwareInfo.Gpu(vendor, name, memory, null, null, "Metal", cores));
      }
    } catch (IOException e) {
      LOGGER.debug("Unreadable system_profiler output");
    }
    return gpus;
  }

  private static String vendor(String raw) {
    String v = raw.replace("sppci_vendor_", "").trim();
    if (v.isEmpty()) {
      return null;
    }
    return switch (v.toLowerCase(Locale.ROOT)) {
      case "apple" -> "Apple";
      case "amd" -> "AMD";
      case "intel" -> "Intel";
      case "nvidia" -> "NVIDIA";
      default -> v;
    };
  }

  private static Long vramMb(String text) {
    if (text == null) {
      return null;
    }
    String[] parts = text.trim().split("\\s+");
    Integer amount = parts.length > 0 ? toInt(parts[0]) : null;
    if (amount == null) {
      return null;
    }
    return parts.length > 1 && parts[1].toUpperCase(Locale.ROOT).startsWith("G")
      ? amount * 1024L
      : amount.longValue();
  }

  // ---- Linux

  private static HardwareInfo.Cpu linuxCpu(Path cpuInfo, String arch, int logicalCores) {
    try {
      return parseCpuInfo(Files.readString(cpuInfo, StandardCharsets.UTF_8), arch, logicalCores);
    } catch (IOException e) {
      return new HardwareInfo.Cpu(arch, null, logicalCores, null, List.of());
    }
  }

  /** {@code /proc/cpuinfo}: model name, instruction-set flags and, when listed, the number of physical cores. */
  static HardwareInfo.Cpu parseCpuInfo(String text, String arch, int logicalCores) {
    String model = null;
    Set<String> features = new HashSet<>();
    Set<String> cores = new HashSet<>();
    String physicalId = "0";
    for (String line : text.split("\n")) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      switch (key) {
        case "model name", "hardware", "model" -> {
          if (model == null && !value.isEmpty()) {
            model = value;
          }
        }
        case "flags", "features" -> features.addAll(relevantFeatures(value));
        case "physical id" -> physicalId = value;
        case "core id" -> cores.add(physicalId + "/" + value);
        default -> {}
      }
    }
    return new HardwareInfo.Cpu(
      arch,
      model,
      logicalCores,
      cores.isEmpty() ? null : cores.size(),
      features.stream().sorted().toList()
    );
  }

  private static List<String> relevantFeatures(String raw) {
    List<String> out = new ArrayList<>();
    for (String f : raw.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
      if (CPU_FEATURES.contains(f)) {
        out.add(f);
      }
    }
    return out;
  }

  // ---- NVIDIA

  private static List<HardwareInfo.Gpu> nvidia(Commands commands) {
    Optional<String> out = commands.run(
      "nvidia-smi",
      "--query-gpu=name,memory.total,driver_version,compute_cap",
      "--format=csv,noheader,nounits"
    );
    if (out.isEmpty()) {
      // Older drivers do not know compute_cap.
      out = commands.run(
        "nvidia-smi",
        "--query-gpu=name,memory.total,driver_version",
        "--format=csv,noheader,nounits"
      );
    }
    return out.map(HardwareDetector::parseNvidiaSmi).orElse(List.of());
  }

  /** CSV rows of {@code name, memory.total (MiB), driver_version[, compute_cap]}. */
  static List<HardwareInfo.Gpu> parseNvidiaSmi(String csv) {
    List<HardwareInfo.Gpu> gpus = new ArrayList<>();
    for (String line : csv.split("\n")) {
      String[] f = line.split(",");
      if (f.length < 3) {
        continue;
      }
      Integer memory = toInt(f[1].trim());
      String capability = f.length > 3 && f[3].trim().matches("\\d+\\.\\d+") ? f[3].trim() : null;
      gpus.add(
        new HardwareInfo.Gpu(
          "NVIDIA",
          f[0].trim(),
          memory == null ? null : memory.longValue(),
          f[2].trim(),
          capability,
          "CUDA",
          null
        )
      );
    }
    return gpus;
  }

  private static Integer toInt(String text) {
    try {
      return text == null ? null : Integer.valueOf(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Runs real commands with a short timeout; anything unexpected yields empty. */
  private static final class ProcessCommands implements Commands {

    @Override
    public Optional<String> run(String... command) {
      try {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          return Optional.empty();
        }
        if (process.exitValue() != 0) {
          return Optional.empty();
        }
        String text = new String(out, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
      } catch (IOException e) {
        return Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }
  }
}
