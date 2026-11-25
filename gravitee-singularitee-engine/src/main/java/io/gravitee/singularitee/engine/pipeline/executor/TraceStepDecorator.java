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
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.StepModel;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.StepTrace;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Objects;

/**
 * Platform decorator that reports what each step did when the run is traced: the context fields it
 * added or changed, where the run went next, and how long the step took.
 */
public final class TraceStepDecorator implements StepExecutorDecorator {

  static final int MAX_VALUE_CHARS = 4096;
  static final String TRUNCATED = "...[truncated]";

  @Override
  public Maybe<String> around(StepModel step, StepContext ctx, StepInvocation next) {
    PipelineContext pctx = ctx.pipelineContext();
    if (!pctx.traceEnabled()) {
      return next.proceed(step, ctx);
    }
    return Maybe.defer(() -> {
      long started = System.nanoTime();
      Map<String, String> before = pctx.snapshot();
      return next
        .proceed(step, ctx)
        .doOnSuccess(nextStep -> emit(step, ctx, before, started, nextStep, null))
        .doOnComplete(() -> emit(step, ctx, before, started, null, null))
        .doOnError(error -> emit(step, ctx, before, started, null, error));
    });
  }

  private static void emit(
    StepModel step,
    StepContext ctx,
    Map<String, String> before,
    long started,
    String nextStep,
    Throwable error
  ) {
    PipelineContext pctx = ctx.pipelineContext();
    long now = System.nanoTime();
    var trace = StepTrace.newBuilder()
      .setStepId(step.id())
      .setStepType(step.type())
      .setStartedAtMs((started - pctx.traceStartNanos()) / 1_000_000)
      .setDurationMs((now - started) / 1_000_000)
      .setHalted(pctx.isHalted());
    if (nextStep != null) trace.setNextStep(nextStep);
    if (pctx.isHalted() && pctx.haltReason() != null) trace.setFinishReason(pctx.haltReason());
    if (
      step.config() instanceof ModelBoundConfig bound && bound.modelId() != null
    ) trace.setModelId(bound.modelId());
    if (error != null) trace.setError(
      Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName())
    );
    pctx
      .snapshot()
      .forEach((key, value) -> {
        if (!Objects.equals(before.get(key), value)) trace.putOutputs(key, cut(value));
      });
    ctx
      .response()
      .write(
        InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_STEP_TRACE)
          .setStepTrace(trace)
          .build()
      );
  }

  static String cut(String value) {
    return value.length() <= MAX_VALUE_CHARS
      ? value
      : value.substring(0, MAX_VALUE_CHARS) + TRUNCATED;
  }
}
