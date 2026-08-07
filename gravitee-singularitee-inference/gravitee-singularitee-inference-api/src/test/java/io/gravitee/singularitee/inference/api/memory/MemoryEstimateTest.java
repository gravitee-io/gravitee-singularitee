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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemoryEstimateTest {

  private static final double GB = 1024.0 * 1024.0 * 1024.0;

  @Nested
  @DisplayName("unknown() sentinel")
  class UnknownSentinel {

    @Test
    @DisplayName("unknown() returns a sentinel with zero values and willFit=true")
    void unknown_returns_zero_sentinel() {
      MemoryEstimate unknown = MemoryEstimate.unknown();
      assertThat(unknown.totalGb()).isEqualTo(0.0);
      assertThat(unknown.usableGb()).isEqualTo(0.0);
      assertThat(unknown.requiredGb()).isEqualTo(0.0);
      assertThat(unknown.willFit()).isTrue();
      assertThat(unknown.isApproximate()).isTrue();
      assertThat(unknown.suggestion()).contains("unavailable");
    }

    @Test
    @DisplayName("isUnknown() returns true for the sentinel")
    void isUnknown_returns_true_for_sentinel() {
      assertThat(MemoryEstimate.unknown().isUnknown()).isTrue();
    }

    @Test
    @DisplayName("isUnknown() returns true for any estimate with all zeros")
    void isUnknown_returns_true_when_all_zero() {
      MemoryEstimate estimate = new MemoryEstimate(0, 0, 0, false, "anything", false);
      assertThat(estimate.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("isUnknown() returns false when requiredGb is non-zero")
    void isUnknown_returns_false_when_required_is_nonzero() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 20.0, 1.0, true, "", false);
      assertThat(estimate.isUnknown()).isFalse();
    }

    @Test
    @DisplayName("isUnknown() returns false when usableGb is non-zero")
    void isUnknown_returns_false_when_usable_is_nonzero() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 2.0, 0, true, "", false);
      assertThat(estimate.isUnknown()).isFalse();
    }

    @Test
    @DisplayName("isUnknown() returns false when totalGb is non-zero")
    void isUnknown_returns_false_when_total_is_nonzero() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 0, 0, true, "", false);
      assertThat(estimate.isUnknown()).isFalse();
    }
  }

  @Nested
  @DisplayName("of() factory with safety margin")
  class OfFactoryWithMargin {

    @Test
    @DisplayName("model that fits: willFit=true with 10% safety margin")
    void model_fits_with_safety_margin() {
      // 8 GiB required, 24 GiB free, 24 GiB total, 10% margin -> usable = 21.6 GiB -> fits
      long required = (long) (8 * GB);
      long available = (long) (24 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(
        required,
        available,
        total,
        0.10,
        "It fits!",
        false
      );

      assertThat(estimate.requiredGb()).isCloseTo(8.0, within(0.01));
      assertThat(estimate.usableGb()).isCloseTo(21.6, within(0.01));
      assertThat(estimate.totalGb()).isCloseTo(24.0, within(0.01));
      assertThat(estimate.willFit()).isTrue();
      assertThat(estimate.suggestion()).isEqualTo("It fits!");
      assertThat(estimate.isApproximate()).isFalse();
    }

    @Test
    @DisplayName("model that does NOT fit: willFit=false with 10% safety margin")
    void model_does_not_fit_with_safety_margin() {
      // 22 GiB required, 24 GiB free, 24 GiB total, 10% margin -> usable = 21.6 GiB -> does NOT fit
      long required = (long) (22 * GB);
      long available = (long) (24 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(
        required,
        available,
        total,
        0.10,
        "Too big",
        true
      );

      assertThat(estimate.willFit()).isFalse();
      assertThat(estimate.isApproximate()).isTrue();
    }

    @Test
    @DisplayName("exact boundary: required == usable -> willFit=true")
    void exact_boundary_fits() {
      // 21.6 GiB required, 24 GiB free, 24 GiB total, 10% margin -> usable = 21.6 GiB -> fits (equal)
      long available = (long) (24 * GB);
      long total = (long) (24 * GB);
      long required = (long) (available * 0.90); // exactly usable

      MemoryEstimate estimate = MemoryEstimate.of(
        required,
        available,
        total,
        0.10,
        "boundary",
        false
      );

      assertThat(estimate.willFit()).isTrue();
    }

    @Test
    @DisplayName("zero safety margin: entire available space is usable")
    void zero_safety_margin() {
      long required = (long) (23 * GB);
      long available = (long) (24 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(
        required,
        available,
        total,
        0.0,
        "no margin",
        false
      );

      assertThat(estimate.willFit()).isTrue();
    }

    @Test
    @DisplayName("25% safety margin for multimodal models")
    void multimodal_safety_margin() {
      // 16 GiB required, 24 GiB free, 24 GiB total, 25% margin -> usable = 18 GiB -> fits
      long required = (long) (16 * GB);
      long available = (long) (24 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(
        required,
        available,
        total,
        0.25,
        "multimodal",
        true
      );

      assertThat(estimate.willFit()).isTrue();

      // 19 GiB required -> does NOT fit (19 > 18)
      long required2 = (long) (19 * GB);
      MemoryEstimate estimate2 = MemoryEstimate.of(
        required2,
        available,
        total,
        0.25,
        "multimodal",
        true
      );
      assertThat(estimate2.willFit()).isFalse();
    }

    @Test
    @DisplayName("zero required bytes produces requiredGb=0 and willFit=true")
    void zero_required_bytes() {
      long total = (long) (24 * GB);
      MemoryEstimate estimate = MemoryEstimate.of(0, total, total, 0.10, "empty", false);
      assertThat(estimate.requiredGb()).isEqualTo(0.0);
      assertThat(estimate.willFit()).isTrue();
    }

    @Test
    @DisplayName("zero available bytes: nothing fits (unless zero required)")
    void zero_available_bytes() {
      MemoryEstimate estimate = MemoryEstimate.of((long) (8 * GB), 0, 0, 0.10, "no gpu", false);
      assertThat(estimate.usableGb()).isEqualTo(0.0);
      assertThat(estimate.willFit()).isFalse();
    }

    @Test
    @DisplayName("isUnknown() returns false for a real estimate")
    void real_estimate_is_not_unknown() {
      long total = (long) (24 * GB);
      MemoryEstimate estimate = MemoryEstimate.of((long) (8 * GB), total, total, 0.10, "ok", false);
      assertThat(estimate.isUnknown()).isFalse();
    }
  }

  @Nested
  @DisplayName("of() factory with pre-computed usable budget")
  class OfFactoryPreComputed {

    @Test
    @DisplayName("model that fits with pre-computed usable budget")
    void model_fits_pre_computed() {
      // 8 GiB required, 20 GiB usable budget, 24 GiB total -> fits
      long required = (long) (8 * GB);
      long usable = (long) (20 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(required, usable, total, "It fits!", true);

      assertThat(estimate.requiredGb()).isCloseTo(8.0, within(0.01));
      assertThat(estimate.usableGb()).isCloseTo(20.0, within(0.01));
      assertThat(estimate.totalGb()).isCloseTo(24.0, within(0.01));
      assertThat(estimate.willFit()).isTrue();
      assertThat(estimate.isApproximate()).isTrue();
    }

    @Test
    @DisplayName("model does NOT fit with pre-computed usable budget")
    void model_does_not_fit_pre_computed() {
      // 22 GiB required, 20 GiB usable budget, 24 GiB total -> does NOT fit
      long required = (long) (22 * GB);
      long usable = (long) (20 * GB);
      long total = (long) (24 * GB);

      MemoryEstimate estimate = MemoryEstimate.of(required, usable, total, "Too big", true);

      assertThat(estimate.willFit()).isFalse();
    }
  }

  @Nested
  @DisplayName("toHumanReadable()")
  class ToHumanReadable {

    @Test
    @DisplayName("fits + exact: includes 'fits' and '(exact)'")
    void fits_exact() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 21.6, 8.0, true, "All good.", false);
      String readable = estimate.toHumanReadable();

      assertThat(readable).contains("(exact)");
      assertThat(readable).contains("fits");
      assertThat(readable).contains("8.00");
      assertThat(readable).contains("21.60");
      assertThat(readable).contains("24.00");
      assertThat(readable).contains("All good.");
    }

    @Test
    @DisplayName("does NOT fit + approximate: includes 'does NOT fit' and '(approximate)'")
    void does_not_fit_approximate() {
      MemoryEstimate estimate = new MemoryEstimate(8.0, 6.0, 22.0, false, "Too big.", true);
      String readable = estimate.toHumanReadable();

      assertThat(readable).contains("(approximate)");
      assertThat(readable).contains("does NOT fit");
      assertThat(readable).contains("22.00");
      assertThat(readable).contains("6.00");
      assertThat(readable).contains("Too big.");
    }

    @Test
    @DisplayName("null suggestion is handled gracefully")
    void null_suggestion() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 21.6, 8.0, true, null, false);
      String readable = estimate.toHumanReadable();

      assertThat(readable).doesNotContain("null");
      assertThat(readable).contains("fits");
    }
  }

  @Nested
  @DisplayName("Record equality and accessors")
  class RecordEquality {

    @Test
    @DisplayName("two unknown() sentinels are equal")
    void unknown_sentinels_are_equal() {
      assertThat(MemoryEstimate.unknown()).isEqualTo(MemoryEstimate.unknown());
    }

    @Test
    @DisplayName("record accessors return correct values")
    void accessors() {
      MemoryEstimate estimate = new MemoryEstimate(24.0, 20.0, 16.5, true, "ok", false);
      assertThat(estimate.totalGb()).isEqualTo(24.0);
      assertThat(estimate.usableGb()).isEqualTo(20.0);
      assertThat(estimate.requiredGb()).isEqualTo(16.5);
      assertThat(estimate.willFit()).isTrue();
      assertThat(estimate.suggestion()).isEqualTo("ok");
      assertThat(estimate.isApproximate()).isFalse();
    }
  }
}
