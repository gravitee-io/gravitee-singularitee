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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CUDA compute capability of the local GPUs, read from {@code nvidia-smi}.
 *
 * <p>Two vLLM defaults are wrong on a pre-Ampere (compute capability &lt; 8.0)
 * card, and both fail late and cryptically:
 *
 * <ul>
 *   <li>{@code dtype: auto} resolves to the checkpoint's {@code bfloat16}, which
 *       those cards do not implement — vLLM refuses the load.</li>
 *   <li>FlashInfer is selected for sampling and attention, then JIT-compiles its
 *       kernels at engine init and fails against the CUDA toolchain it pulls
 *       in.</li>
 * </ul>
 *
 * <p>Knowing the capability lets {@link EngineAdapter} correct both before the
 * engine is built, so an example workspace written for a modern card still runs.
 *
 * <p>Deliberately shells out rather than asking CPython: this has to be known
 * <em>before</em> the vLLM import, and {@code nvidia-smi} ships with the driver
 * that must be present anyway. Anything unexpected — no binary, no GPU, a
 * timeout, unparseable output — yields an empty result, which every caller reads
 * as "assume a modern card and change nothing".
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class GpuCapability {

  private static final Logger LOGGER = LoggerFactory.getLogger(GpuCapability.class);

  /** bfloat16, and FlashInfer's prebuilt kernels, start at Ampere. */
  private static final double AMPERE = 8.0;

  private static final int QUERY_TIMEOUT_SECONDS = 5;

  /** Memoised: the GPUs do not change under a running JVM, and this forks a process. */
  private static volatile OptionalDouble cached;

  private GpuCapability() {}

  /**
   * Returns {@code true} only when a capability was read <em>and</em> it is below
   * Ampere. An unreadable capability is never treated as pre-Ampere: silently
   * forcing {@code float16} on a card that supports {@code bfloat16} would
   * degrade quality on every machine where {@code nvidia-smi} is merely absent.
   */
  static boolean isPreAmpere() {
    OptionalDouble capability = lowest();
    return capability.isPresent() && capability.getAsDouble() < AMPERE;
  }

  /**
   * The lowest compute capability across the visible GPUs — the binding one in a
   * mixed set, since the engine's dtype and backend apply to all of them.
   */
  static OptionalDouble lowest() {
    OptionalDouble local = cached;
    if (local == null) {
      local = query();
      cached = local;
    }
    return local;
  }

  private static OptionalDouble query() {
    Process process = null;
    try {
      process = new ProcessBuilder("nvidia-smi", "--query-gpu=compute_cap", "--format=csv,noheader")
        .redirectErrorStream(false)
        .start();

      String output;
      try (
        BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )
      ) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }

      if (!process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        LOGGER.debug(
          "nvidia-smi did not answer within {}s — GPU capability unknown",
          QUERY_TIMEOUT_SECONDS
        );
        return OptionalDouble.empty();
      }
      if (process.exitValue() != 0) {
        LOGGER.debug("nvidia-smi exited with {} — GPU capability unknown", process.exitValue());
        return OptionalDouble.empty();
      }

      OptionalDouble capability = parse(output);
      if (capability.isPresent()) {
        LOGGER.debug("Lowest CUDA compute capability: {}", capability.getAsDouble());
      }
      return capability;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OptionalDouble.empty();
    } catch (Exception e) {
      // No nvidia-smi at all (CPU box, macOS/Metal) is the common case and not
      // worth a warning — every caller degrades to "change nothing".
      LOGGER.debug("Could not read GPU compute capability: {}", e.toString());
      return OptionalDouble.empty();
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /**
   * Parses {@code nvidia-smi --query-gpu=compute_cap} output: one value per GPU,
   * one per line. Unparseable lines — {@code [N/A]} on a card the driver cannot
   * report, or a stray banner — are skipped rather than failing the whole read.
   */
  static OptionalDouble parse(String output) {
    if (output == null || output.isBlank()) {
      return OptionalDouble.empty();
    }
    return output
      .lines()
      .map(String::trim)
      .filter(line -> !line.isEmpty())
      .map(GpuCapability::parseLine)
      .filter(OptionalDouble::isPresent)
      .mapToDouble(OptionalDouble::getAsDouble)
      .min();
  }

  private static OptionalDouble parseLine(String line) {
    try {
      return OptionalDouble.of(Double.parseDouble(line));
    } catch (NumberFormatException e) {
      return OptionalDouble.empty();
    }
  }
}
