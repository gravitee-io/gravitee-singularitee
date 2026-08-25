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
package io.gravitee.singularitee.engine.pipeline.executor;

import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutorDecorator;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepInvocation;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.reactivex.rxjava3.core.Maybe;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform decorator: the per-step diagnostics log. INFO on dispatch and completion,
 * DEBUG snapshots of the pipeline context before and after with a compact delta of what
 * the step produced (new fields, generated messages, verdicts), TRACE for the full config.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DiagnosticsStepDecorator implements StepExecutorDecorator {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticsStepDecorator.class);

  @Override
  public Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next) {
    String stepId = step.id();
    PipelineContext pctx = ctx.pipelineContext();
    Object config = step.config();

    LOGGER.info(
      "Dispatching step '{}' (type={}) with config type {}",
      stepId,
      step.type(),
      config != null ? config.getClass().getSimpleName() : "null"
    );
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Step '{}': config {}", stepId, StepConfigDescriber.describe(config));
    }
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Step '{}': full config {}", stepId, StepConfigDescriber.describeFull(config));
    }

    Set<String> fieldsBefore = null;
    int generatedBefore = 0;
    int verdictsBefore = 0;
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Step '{}': pre-execution context\n{}", stepId, pctx.debugSnapshot());
      fieldsBefore = new LinkedHashSet<>(pctx.snapshot().keySet());
      generatedBefore = pctx.generatedMessages().size();
      verdictsBefore = pctx.verdicts().size();
    }
    final Set<String> preFields = fieldsBefore;
    final int preGenerated = generatedBefore;
    final int preVerdicts = verdictsBefore;

    return next
      .proceed(step, ctx)
      .doOnSuccess(nextId -> {
        LOGGER.info("Step '{}' completed, nextStep='{}'", stepId, nextId);
        logPostStep(stepId, pctx, preFields, preGenerated, preVerdicts);
      })
      .doOnComplete(() -> {
        LOGGER.info("Step '{}' completed, terminal (no next step)", stepId);
        logPostStep(stepId, pctx, preFields, preGenerated, preVerdicts);
      });
  }

  private static void logPostStep(
    String stepId,
    PipelineContext pctx,
    Set<String> preFields,
    int preGenerated,
    int preVerdicts
  ) {
    if (!LOGGER.isDebugEnabled()) return;

    LOGGER.debug("Step '{}': post-execution context\n{}", stepId, pctx.debugSnapshot());

    if (preFields == null) return;
    Set<String> added = new LinkedHashSet<>(pctx.snapshot().keySet());
    added.removeAll(preFields);
    int addedGenerated = pctx.generatedMessages().size() - preGenerated;
    int addedVerdicts = pctx.verdicts().size() - preVerdicts;

    if (added.isEmpty() && addedGenerated == 0 && addedVerdicts == 0) {
      LOGGER.debug("Step '{}': no context changes", stepId);
      return;
    }
    StringBuilder delta = new StringBuilder();
    if (!added.isEmpty()) delta.append("fields=").append(added);
    if (addedGenerated > 0) {
      if (!delta.isEmpty()) delta.append(", ");
      delta.append("+").append(addedGenerated).append(" generatedMessage(s)");
    }
    if (addedVerdicts > 0) {
      if (!delta.isEmpty()) delta.append(", ");
      delta.append("+").append(addedVerdicts).append(" verdict(s)");
    }
    LOGGER.debug("Step '{}': produced {}", stepId, delta);
  }
}
