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

import static io.gravitee.singularitee.inference.math.vanilla.NativeMath.INSTANCE;

import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.math.simd.LoopBoundSIMDMath;
import io.gravitee.singularitee.inference.math.simd.MaskAwareSIMDMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the {@link GioMaths} implementation that matches the host CPU.
 *
 * Masked SIMD when the CPU reports AVX-512 or SVE, loop-bound SIMD when it reports AVX or
 * NEON, scalar {@code NativeMath} otherwise. Detection is a one-off read of the CPU feature
 * flags; call once at startup and share the result.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SIMDMathFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(SIMDMathFactory.class);

  /** The best {@link GioMaths} for this CPU; never {@code null}. */
  public static GioMaths gioMaths() {
    if (SIMDUtils.isSIMDSupported()) {
      LOGGER.debug("SIMD supported");
      if (SIMDUtils.isSIMDMaskSupported()) {
        LOGGER.debug("SIMD masking supported, using MaskAwareSIMDMath implementation");
        return MaskAwareSIMDMath.INSTANCE;
      }
      LOGGER.debug("SIMD masking not supported, using LoopBoundSIMDMath implementation");
      return LoopBoundSIMDMath.INSTANCE;
    }
    LOGGER.debug("SIMD not supported, using NativeMath implementation");
    return INSTANCE;
  }
}
