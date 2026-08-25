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
package io.gravitee.singularitee.plugin.loop;

import io.gravitee.singularitee.engine.api.pipeline.model.BranchingConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.MessageTemplate;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCondition;
import io.gravitee.singularitee.protocol.SamplingParams;
import java.util.List;

/**
 * Runtime config of a {@code loop} step: a bounded back-edge. While the exit condition
 * is not met execution jumps to {@code targetStepId}; once met it continues to
 * {@code nextStepId}, and once the budget is spent to {@code fallbackStepId} (or
 * {@code nextStepId} when unset).
 *
 * @param targetStepId        step to jump back to while the condition is not met
 * @param nextStepId          the exit step (the envelope's {@code next_step})
 * @param fallbackStepId      step taken when the ceiling is reached; blank = next step
 * @param maxIterations       hard ceiling on iterations; must be positive
 * @param condition           the exit condition, or {@code null} when never met
 * @param loopbackMessage     message appended to the conversation on every loop-back
 * @param retrySamplingParams sampling override installed on the retry edge only
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public record LoopStepConfig(
  String targetStepId,
  String nextStepId,
  String fallbackStepId,
  int maxIterations,
  StepCondition condition,
  MessageTemplate loopbackMessage,
  SamplingParams retrySamplingParams
) implements BranchingConfig {
  public LoopStepConfig {
    targetStepId = targetStepId == null ? "" : targetStepId;
    nextStepId = nextStepId == null ? "" : nextStepId;
    fallbackStepId = fallbackStepId == null ? "" : fallbackStepId;
    if (maxIterations <= 0) {
      throw new IllegalArgumentException("max_iterations must be positive");
    }
  }

  @Override
  public List<String> branchTargets() {
    return List.of(targetStepId, nextStepId, fallbackStepId)
      .stream()
      .filter(s -> !s.isBlank())
      .toList();
  }
}
