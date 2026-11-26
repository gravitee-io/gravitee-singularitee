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

import java.util.List;

/**
 * What the node runs on, for the control plane: operating system, CPU and accelerators. Anything
 * that cannot be read is {@code null} or empty; detection is best effort and never fails the node.
 */
public record HardwareInfo(String os, Cpu cpu, List<Gpu> gpus) {
  /**
   * @param arch JVM architecture name, for example {@code aarch64} or {@code amd64}
   * @param model marketing name, for example {@code Apple M4 Pro}
   * @param features relevant instruction sets (avx2, avx512f, neon...) as the kernel reports them
   */
  public record Cpu(
    String arch,
    String model,
    int logicalCores,
    Integer physicalCores,
    List<String> features
  ) {}

  /**
   * @param vendor NVIDIA, Apple, AMD, Intel
   * @param memoryMb dedicated memory, {@code null} for unified memory (Apple Silicon)
   * @param computeCapability CUDA compute capability such as {@code 8.9}, NVIDIA only
   * @param backend how an engine reaches it: CUDA, Metal
   * @param cores GPU cores when the platform reports them (Apple Silicon)
   */
  public record Gpu(
    String vendor,
    String name,
    Long memoryMb,
    String driver,
    String computeCapability,
    String backend,
    Integer cores
  ) {}
}
