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
package io.gravitee.singularitee.inference.api.memory;

/**
 * Result of a pre-flight VRAM estimation for a given model configuration.
 *
 * <p>Used to decide — before any weights are loaded — whether the model will
 * fit in available GPU memory, and to surface a human-readable suggestion when
 * it will not.
 *
 * @param totalGb         Total physical memory on the target device, in GiB.
 * @param usableGb        Usable memory budget after applying utilization and safety margins, in GiB.
 * @param requiredGb      Estimated VRAM required to load the model, in GiB.
 * @param willFit         {@code true} if {@code requiredGb <= usableGb}.
 * @param suggestion      Human-readable advice shown in logs / error messages when
 *                        {@code willFit} is {@code false}.
 * @param isApproximate   {@code false} for exact llama.cpp estimates (GGUF + real VRAM query);
 *                        {@code true} for vLLM estimates (CUDA graph overhead not captured).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record MemoryEstimate(
  double totalGb,
  double usableGb,
  double requiredGb,
  boolean willFit,
  String suggestion,
  boolean isApproximate
) {
  private static final double GB = 1024.0 * 1024.0 * 1024.0;

  /**
   * Sentinel value returned when estimation is impossible (e.g. HF API unreachable,
   * config.json missing). Callers should treat this as "skip the check".
   */
  public static MemoryEstimate unknown() {
    return new MemoryEstimate(0, 0, 0, true, "Memory estimate unavailable — skipping check.", true);
  }

  /** Returns {@code true} if this is the sentinel {@link #unknown()} value. */
  public boolean isUnknown() {
    return totalGb == 0 && usableGb == 0 && requiredGb == 0;
  }

  /**
   * Constructs a {@link MemoryEstimate} from raw byte values.
   *
   * <p>The usable budget is computed as {@code availableBytes * (1 - safetyMargin)}.
   * For callers that have already computed the usable budget (e.g. vLLM with
   * {@code gpuMemoryUtilization}), use {@link #of(long, long, long, String, boolean)}.
   *
   * @param requiredBytes  Total estimated bytes needed.
   * @param availableBytes Bytes currently free on the device.
   * @param totalBytes     Total physical bytes on the device.
   * @param safetyMargin   Fraction of available memory reserved (e.g. {@code 0.10} = 10 %).
   * @param suggestion     Human-readable suggestion if the model will not fit.
   * @param isApproximate  Whether the estimate is approximate.
   */
  public static MemoryEstimate of(
    long requiredBytes,
    long availableBytes,
    long totalBytes,
    double safetyMargin,
    String suggestion,
    boolean isApproximate
  ) {
    double total = totalBytes / GB;
    double usable = (availableBytes * (1.0 - safetyMargin)) / GB;
    double required = requiredBytes / GB;
    boolean fits = required <= usable;
    return new MemoryEstimate(total, usable, required, fits, suggestion, isApproximate);
  }

  /**
   * Constructs a {@link MemoryEstimate} from raw byte values when the usable
   * budget has already been computed by the caller (e.g. vLLM's
   * {@code totalBytes * gpuMemoryUtilization * (1 - safetyMargin)}).
   *
   * @param requiredBytes  Total estimated bytes needed.
   * @param usableBytes    Pre-computed usable memory budget in bytes.
   * @param totalBytes     Total physical bytes on the device.
   * @param suggestion     Human-readable suggestion if the model will not fit.
   * @param isApproximate  Whether the estimate is approximate.
   */
  public static MemoryEstimate of(
    long requiredBytes,
    long usableBytes,
    long totalBytes,
    String suggestion,
    boolean isApproximate
  ) {
    double total = totalBytes / GB;
    double usable = usableBytes / GB;
    double required = requiredBytes / GB;
    boolean fits = required <= usable;
    return new MemoryEstimate(total, usable, required, fits, suggestion, isApproximate);
  }

  /** Returns a formatted, human-readable summary line. */
  public String toHumanReadable() {
    String fitStr = willFit ? "fits" : "does NOT fit";
    String approxStr = isApproximate ? " (approximate)" : " (exact)";
    return String.format(
      "VRAM estimate%s: required=%.2f GiB, usable=%.2f GiB, total=%.2f GiB — %s. %s",
      approxStr,
      requiredGb,
      usableGb,
      totalGb,
      fitStr,
      suggestion != null ? suggestion : ""
    );
  }
}
