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

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Parsing of {@code nvidia-smi --query-gpu=compute_cap} output.
 *
 * <p>The reading decides whether the engine silently switches to {@code float16}
 * and turns the FlashInfer sampler off, so a misread is not cosmetic: too low
 * degrades precision on a card that never needed it, too high leaves a
 * pre-Ampere card failing at model load. An *absent* reading has to stay absent
 * rather than defaulting either way.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
class GpuCapabilityTest {

  @Test
  void reads_a_single_gpu() {
    assertThat(GpuCapability.parse("7.5")).hasValue(7.5);
  }

  @Test
  void takes_the_lowest_of_several_gpus() {
    // dtype and attention backend apply engine-wide, so the weakest card binds:
    // picking the 8.6 here would leave the 7.5 failing at load.
    assertThat(GpuCapability.parse("8.6\n7.5\n8.9")).hasValue(7.5);
  }

  @Test
  void tolerates_blank_lines_and_trailing_newline() {
    assertThat(GpuCapability.parse("8.6\n\n9.0\n")).hasValue(8.6);
  }

  @Test
  void skips_cards_the_driver_cannot_report() {
    // nvidia-smi prints [N/A] for some virtualised GPUs — one unreadable card
    // must not discard the reading for the rest.
    assertThat(GpuCapability.parse("[N/A]\n7.5")).hasValue(7.5);
  }

  @Test
  void no_output_means_unknown_not_zero() {
    // Zero would compare as pre-Ampere and force float16 on every machine
    // without nvidia-smi.
    assertThat(GpuCapability.parse("")).isEmpty();
    assertThat(GpuCapability.parse("   ")).isEmpty();
    assertThat(GpuCapability.parse(null)).isEmpty();
  }

  @Test
  void entirely_unparseable_output_is_unknown() {
    assertThat(GpuCapability.parse("[N/A]")).isEmpty();
    assertThat(GpuCapability.parse("Failed to initialize NVML: Driver/library mismatch")).isEmpty();
  }

  @Test
  void the_ampere_boundary_is_inclusive_below_only() {
    // 8.0 is Ampere itself: it has bfloat16 and must NOT be treated as legacy.
    assertThat(isPreAmpere("7.5")).isTrue();
    assertThat(isPreAmpere("7.9")).isTrue();
    assertThat(isPreAmpere("8.0")).isFalse();
    assertThat(isPreAmpere("8.6")).isFalse();
    assertThat(isPreAmpere("12.0")).isFalse();
  }

  /** Mirrors {@link GpuCapability#isPreAmpere()} over a parsed reading. */
  private static boolean isPreAmpere(String nvidiaSmiOutput) {
    OptionalDouble capability = GpuCapability.parse(nvidiaSmiOutput);
    return capability.isPresent() && capability.getAsDouble() < 8.0;
  }
}
