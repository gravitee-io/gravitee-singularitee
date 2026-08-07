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
package io.gravitee.singularitee.inference.math.simd.factory;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

/**
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SIMDUtils {

  private static final CentralProcessor PROCESSOR = new SystemInfo().getHardware().getProcessor();
  private static final String X_86 = "x86";
  private static final String ARM = "arm";
  private static final String AVX_512 = "avx512";
  private static final String SVE = "sve";
  private static final String UNKNOWN = "unknown";

  public static boolean isSIMDSupported() {
    return switch (getCPUArchitecture()) {
      case X_86 -> checkCPUFeature("avx");
      case ARM -> checkCPUFeature("neon");
      default -> false;
    };
  }

  public static boolean isSIMDMaskSupported() {
    return switch (getCPUArchitecture().toLowerCase()) {
      case X_86 -> checkCPUFeature(AVX_512);
      case ARM -> checkCPUFeature(SVE);
      default -> false;
    };
  }

  private static String getCPUArchitecture() {
    final String processor = PROCESSOR.toString();

    if (isX86(processor)) {
      return X_86;
    } else if (isArm(processor)) {
      return ARM;
    }
    return UNKNOWN;
  }

  private static boolean isArm(String architecture) {
    return architecture.toLowerCase().contains(ARM);
  }

  private static boolean isX86(String architecture) {
    return (
      architecture.toLowerCase().contains("intel") || architecture.toLowerCase().contains("amd")
    );
  }

  private static boolean checkCPUFeature(String feature) {
    return PROCESSOR.getFeatureFlags()
      .stream()
      .map(String::toLowerCase)
      .anyMatch(f -> f.contains(feature));
  }
}
