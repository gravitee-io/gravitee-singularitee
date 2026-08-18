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
package io.gravitee.singularitee.inference.api.textgen;

/**
 * Performance metrics for inference operations.
 * Provides detailed timing and token statistics for monitoring and optimization.
 *
 * @param startTimeMs Timestamp when the sequence started (epoch milliseconds)
 * @param loadTimeMs Time taken to load the model (milliseconds)
 * @param promptEvalTimeMs Time taken to evaluate prompts (milliseconds)
 * @param evalTimeMs Time taken to generate tokens (milliseconds)
 * @param promptTokensEvaluated Number of prompt tokens processed
 * @param tokensGenerated Number of tokens generated
 * @param tokensReused Number of tokens reused (e.g., from cache)
 * @param samplingTimeMs Time spent on sampling (milliseconds)
 * @param sampleCount Number of sampling operations performed
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record InferencePerformance(
  long startTimeMs,
  long loadTimeMs,
  long promptEvalTimeMs,
  long evalTimeMs,
  int promptTokensEvaluated,
  int tokensGenerated,
  int tokensReused,
  long samplingTimeMs,
  int sampleCount
) {
  public InferencePerformance {
    if (startTimeMs < 0) {
      throw new IllegalArgumentException("startTimeMs must be non-negative");
    }
    if (loadTimeMs < 0) {
      throw new IllegalArgumentException("loadTimeMs must be non-negative");
    }
    if (promptEvalTimeMs < 0) {
      throw new IllegalArgumentException("promptEvalTimeMs must be non-negative");
    }
    if (evalTimeMs < 0) {
      throw new IllegalArgumentException("evalTimeMs must be non-negative");
    }
  }

  /**
   * Returns this snapshot minus a baseline: llama.cpp perf counters accumulate
   * for the CONTEXT's lifetime, so a raw read reports every request ever served
   * — a client computing tokens/second from it sees numbers drifting toward the
   * lifetime average. Subtracting the sequence-start snapshot yields the
   * per-request figures. Clamped at zero (a backend may reset counters).
   */
  public InferencePerformance minus(InferencePerformance baseline) {
    if (baseline == null) {
      return this;
    }
    return new InferencePerformance(
      startTimeMs,
      loadTimeMs,
      Math.max(0, promptEvalTimeMs - baseline.promptEvalTimeMs),
      Math.max(0, evalTimeMs - baseline.evalTimeMs),
      Math.max(0, promptTokensEvaluated - baseline.promptTokensEvaluated),
      Math.max(0, tokensGenerated - baseline.tokensGenerated),
      tokensReused,
      Math.max(0, samplingTimeMs - baseline.samplingTimeMs),
      Math.max(0, sampleCount - baseline.sampleCount)
    );
  }

  /**
   * Calculates tokens per second (TPS) for generation.
   * @return Tokens per second, or 0 if evalTimeMs is 0
   */
  public double tokensPerSecond() {
    if (evalTimeMs == 0) {
      return 0;
    }
    return (tokensGenerated * 1000.0) / evalTimeMs;
  }

  /**
   * Calculates tokens per second (TPS) for prompt evaluation.
   * @return Tokens per second, or 0 if promptEvalTimeMs is 0
   */
  public double promptTokensPerSecond() {
    if (promptEvalTimeMs == 0) {
      return 0;
    }
    return (promptTokensEvaluated * 1000.0) / promptEvalTimeMs;
  }

  /**
   * Calculates tokens per second (TPS) for the entire generation phase.
   * This includes the time spent generating tokens (excluding prompt evaluation).
   *
   * @return Tokens generated per second, or 0 if no tokens were generated or time is 0
   */
  public double generationTokensPerSecond() {
    if (evalTimeMs == 0 || tokensGenerated == 0) {
      return 0;
    }
    return (tokensGenerated * 1000.0) / evalTimeMs;
  }

  /**
   * Calculates the total processing time for the entire inference operation.
   * This includes model loading, prompt evaluation, and token generation.
   *
   * @return Total processing time in milliseconds
   */
  public long totalProcessingTimeMs() {
    return loadTimeMs + promptEvalTimeMs + evalTimeMs;
  }

  /**
   * Calculates the average time spent on each sampling operation.
   * This measures how long it takes on average to sample the next token.
   *
   * @return Average sampling time in milliseconds, or 0 if no samples were taken
   */
  public double averageSamplingTimeMs() {
    if (sampleCount == 0) {
      return 0;
    }
    return (double) samplingTimeMs / sampleCount;
  }
}
