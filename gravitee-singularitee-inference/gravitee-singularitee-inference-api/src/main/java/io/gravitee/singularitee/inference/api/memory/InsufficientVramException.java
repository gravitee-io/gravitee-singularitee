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
 * Thrown when a pre-flight VRAM check determines that the model will not fit in
 * available GPU memory and {@link MemoryCheckPolicy#FAIL} is active.
 *
 * <p>Carries the full {@link MemoryEstimate} so callers can surface a precise,
 * human-readable message (see {@link MemoryEstimate#toHumanReadable()}).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class InsufficientVramException extends RuntimeException {

  private final MemoryEstimate estimate;

  public InsufficientVramException(String modelId, MemoryEstimate estimate) {
    super(buildMessage(modelId, estimate));
    this.estimate = estimate;
  }

  /**
   * Variant carrying a caller-supplied explanation instead of the generic
   * estimate summary.
   *
   * <p>Used where the caller knows something sharper than "the estimate says it
   * will not fit" — for instance that the weights alone overflow the configured
   * budget, which is certain rather than approximate and points at the exact
   * setting to change.
   */
  public InsufficientVramException(String modelId, MemoryEstimate estimate, String detail) {
    super(String.format("Insufficient VRAM to load model [%s]: %s", modelId, detail));
    this.estimate = estimate;
  }

  public MemoryEstimate estimate() {
    return estimate;
  }

  private static String buildMessage(String modelId, MemoryEstimate estimate) {
    return String.format(
      "Insufficient VRAM to load model [%s]: %s",
      modelId,
      estimate.toHumanReadable()
    );
  }
}
