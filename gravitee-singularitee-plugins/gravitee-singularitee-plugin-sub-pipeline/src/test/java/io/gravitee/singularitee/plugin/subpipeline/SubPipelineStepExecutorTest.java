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
package io.gravitee.singularitee.plugin.subpipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SubPipelineCallbacks;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.api.tracing.TraceContext;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** A sub-pipeline that cannot run fails the request instead of being skipped. */
class SubPipelineStepExecutorTest {

  private final PipelineRegistry pipelines = mock(PipelineRegistry.class);
  private final StepContext ctx = mock(StepContext.class);
  private final SubPipelineStepExecutor executor = new SubPipelineStepExecutor(
    new StepExecutionContext(null, pipelines, null),
    new SubPipelineCallbacks()
  );

  SubPipelineStepExecutorTest() {
    when(ctx.pipelineContext()).thenReturn(
      new PipelineContext("hi", List.of(new ChatTurn(ChatRole.USER, "hi")), null, List.of(), null)
    );
    when(pipelines.get(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void a_pipeline_that_is_not_loaded_fails_the_request() {
    executor
      .execute("check", config("guards", ""), ctx)
      .test()
      .assertError(
        e ->
          e instanceof IllegalStateException &&
          e
            .getMessage()
            .equals("Step 'check': sub-pipeline 'guards' is not loaded and no remote_id is set")
      );
    verify(ctx, never()).rxNextStep(anyString());
  }

  @Test
  void an_unknown_remote_fails_the_request() {
    executor
      .execute("check", config("guards", "edge"), ctx)
      .test()
      .assertError(e ->
        e
          .getMessage()
          .equals(
            "Step 'check': sub-pipeline 'guards' names remote 'edge', which is not configured"
          )
      );
    verify(ctx, never()).rxNextStep(anyString());
  }

  @Test
  void a_remote_sub_pipeline_is_called_with_the_step_span_as_parent() {
    var seen = new AtomicReference<Map<String, String>>();
    var callbacks = new SubPipelineCallbacks();
    callbacks.register(
      null,
      Map.of("edge", (request, response, callerContext) -> {
        seen.set(TraceContext.current());
        return Completable.complete();
      })
    );
    var stepSpan = mock(Span.class);
    var tracer = mock(Tracer.class);
    doAnswer(inv -> {
      BiConsumer<String, String> setter = inv.getArgument(2);
      setter.accept("traceparent", "00-trace-step-01");
      return null;
    })
      .when(tracer)
      .injectSpanContext(any(), eq(stepSpan), any());
    when(ctx.tracer()).thenReturn(tracer);
    when(ctx.activeStepSpan()).thenReturn(new AtomicReference<>(stepSpan));

    new SubPipelineStepExecutor(new StepExecutionContext(null, pipelines, null), callbacks)
      .execute("check", config("guards", "edge"), ctx)
      .test()
      .dispose();

    assertThat(seen.get()).containsEntry("traceparent", "00-trace-step-01");
    assertThat(TraceContext.current()).isEmpty();
  }

  private static SubPipelineStepConfig config(String pipelineId, String remoteId) {
    return new SubPipelineStepConfig(pipelineId, "", "", remoteId, "", false);
  }
}
