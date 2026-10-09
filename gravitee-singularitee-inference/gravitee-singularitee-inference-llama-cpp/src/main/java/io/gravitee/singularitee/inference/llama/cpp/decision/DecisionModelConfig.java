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
package io.gravitee.singularitee.inference.llama.cpp.decision;

import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import java.nio.file.Path;
import java.util.List;

/**
 * How to load a llama.cpp decision model.
 *
 * @param modelPath         the GGUF file
 * @param loraPath          a LoRA adapter attached to every decider, or {@code null}
 * @param nGpuLayers        layers offloaded to the GPU
 * @param nCtx              context size per decider, or {@code null} for the decider default
 * @param nBatch            batch size, or {@code null} for the family default
 * @param nSeqMax           label families: sequences per decider (one holds the shared prefix),
 *                          or {@code null} for the default
 * @param nParallel         deciders in the pool, each with its own context
 * @param codes             custom models: one single-token label code per option position;
 *                          {@code null} or empty for a native decision model
 * @param memoryCheckPolicy what to do when the estimate says the model may not fit
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record DecisionModelConfig(
  Path modelPath,
  Path loraPath,
  int nGpuLayers,
  Integer nCtx,
  Integer nBatch,
  Integer nSeqMax,
  int nParallel,
  List<String> codes,
  MemoryCheckPolicy memoryCheckPolicy
) {
  public DecisionModelConfig {
    codes = codes == null ? List.of() : List.copyOf(codes);
    nParallel = Math.max(1, nParallel);
  }

  /** Whether this is any causal model scored on {@link #codes}, rather than a native one. */
  public boolean custom() {
    return !codes.isEmpty();
  }
}
