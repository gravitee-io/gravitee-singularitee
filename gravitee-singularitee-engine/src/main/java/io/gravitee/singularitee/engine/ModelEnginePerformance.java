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
package io.gravitee.singularitee.engine;

/**
 * Timing and throughput metrics for a completed inference sequence.
 *
 * <p>This is the local equivalent of {@code InferencePerformance} from
 * {@code gravitee-inference-api}. Populated only on the final token of a sequence.
 *
 * @param startTimeMs           wall-clock epoch milliseconds when the sequence started
 * @param loadTimeMs            model load time in ms (non-zero on first request only)
 * @param promptEvalTimeMs      time spent evaluating the prompt in ms
 * @param evalTimeMs            time spent generating tokens in ms
 * @param promptTokensEvaluated number of prompt tokens actually evaluated
 * @param tokensGenerated       total tokens generated
 * @param tokensReused          tokens reused from KV cache
 * @param samplingTimeMs        cumulative sampling time in ms
 * @param sampleCount           number of sampling operations performed
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record ModelEnginePerformance(
  long startTimeMs,
  long loadTimeMs,
  long promptEvalTimeMs,
  long evalTimeMs,
  int promptTokensEvaluated,
  int tokensGenerated,
  int tokensReused,
  long samplingTimeMs,
  int sampleCount
) {}
