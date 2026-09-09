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
package io.gravitee.singularitee.adapter.batching;

import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tuning knobs for a {@link MicroBatcher}, read once from the environment under a caller-supplied
 * prefix (mirrors the {@code GRAVITEE_ONNX_INTRA_OPS_NUM_THREADS} convention used by the ONNX
 * engines):
 *
 * <ul>
 *   <li>{@code <prefix>_MAX}: max items fused into one batched GPU run (default
 *       {@value #DEFAULT_MAX_BATCH}).</li>
 *   <li>{@code <prefix>_MAX_TOKENS}: max summed estimated tokens per batch (default
 *       {@value #DEFAULT_MAX_BATCH_TOKENS}). Bounds worst-case batch wall time: the encoder pads
 *       every batch item to the longest sequence, so a count-only cap lets one full batch of
 *       max-size items monopolise the device.</li>
 *   <li>{@code <prefix>_BUCKET_TOKENS}: short/long bucket boundary (default
 *       {@value #DEFAULT_BUCKET_TOKENS}). Items at or below it never share a batch with longer
 *       ones, so short requests don't pay the long items' padded-attention cost.</li>
 *   <li>{@code <prefix>_LINGER_MS}: how long the batcher waits for a batch to fill before
 *       dispatching (default {@value #DEFAULT_LINGER_MS} ms). Bounds the added latency under light
 *       load; under heavy load the batch fills well before this elapses.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class BatchingConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchingConfig.class);

  public static final int DEFAULT_MAX_BATCH = 16;
  public static final long DEFAULT_MAX_BATCH_TOKENS = 2048;
  public static final long DEFAULT_BUCKET_TOKENS = 128;
  public static final long DEFAULT_LINGER_MS = 5;
  /** Batches a lane runs concurrently; 1 = the lane thread runs them inline. */
  public static final int DEFAULT_PARALLELISM = 1;

  private final int maxBatchSize;
  private final long maxBatchTokens;
  private final long bucketTokens;
  private final long lingerMillis;
  private final int parallelism;

  private BatchingConfig(
    int maxBatchSize,
    long maxBatchTokens,
    long bucketTokens,
    long lingerMillis,
    int parallelism
  ) {
    this.maxBatchSize = maxBatchSize;
    this.maxBatchTokens = maxBatchTokens;
    this.bucketTokens = bucketTokens;
    this.lingerMillis = lingerMillis;
    this.parallelism = parallelism;
  }

  /**
   * Reads the four knobs from the environment under {@code prefix} (e.g.
   * {@code "GRAVITEE_ONNX_BATCH"} reads {@code GRAVITEE_ONNX_BATCH_MAX} and so on), falling back
   * to the defaults above.
   */
  public static BatchingConfig fromEnv(String prefix) {
    return new BatchingConfig(
      (int) readLong(prefix + "_MAX", DEFAULT_MAX_BATCH, 1),
      readLong(prefix + "_MAX_TOKENS", DEFAULT_MAX_BATCH_TOKENS, 1),
      readLong(prefix + "_BUCKET_TOKENS", DEFAULT_BUCKET_TOKENS, 1),
      readLong(prefix + "_LINGER_MS", DEFAULT_LINGER_MS, 0),
      (int) readLong(prefix + "_PARALLELISM", DEFAULT_PARALLELISM, 1)
    );
  }

  public int maxBatchSize() {
    return maxBatchSize;
  }

  /** Builds a {@link MicroBatcher} shaped by this config. */
  public <I, O> MicroBatcher<I, O> newBatcher(String name, Function<List<I>, List<O>> batchFn) {
    return new MicroBatcher<>(
      name,
      maxBatchSize,
      maxBatchTokens,
      bucketTokens,
      lingerMillis,
      parallelism,
      batchFn
    );
  }

  private static long readLong(String env, long fallback, long min) {
    String raw = System.getenv(env);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      long value = Long.parseLong(raw.trim());
      if (value < min) {
        LOGGER.warn("{}={} is below minimum {}; using {}", env, value, min, min);
        return min;
      }
      return value;
    } catch (NumberFormatException e) {
      LOGGER.warn("{}={} is not a valid number; using default {}", env, raw, fallback);
      return fallback;
    }
  }
}
