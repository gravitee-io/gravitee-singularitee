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
package io.gravitee.singularitee.plugin.llmguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ModelEngineToken;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.model.StepCodecContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LlmGuardStepExecutorSamplingTest {

  @Test
  void a_zero_temperature_reaches_the_engine_as_greedy() {
    assertThat(run(Map.of("temperature", 0)).temperature()).isZero();
  }

  @Test
  void an_unset_or_negative_temperature_leaves_the_engine_default() {
    assertThat(run(Map.of("max_tokens", 8)).temperature()).isNull();
    assertThat(run(Map.of("temperature", -1)).temperature()).isNull();
  }

  private static TextGenRequest run(Map<String, Object> sampling) {
    var cfg = new LlmGuardStepCodec().parse(
      "judge",
      Map.of(
        "model_id",
        "judge",
        "prompt",
        Map.of("messages", List.of(Map.of("content", "Is this safe?"))),
        "sampling",
        sampling
      ),
      new StepCodecContext(Map.of(), null, Map.of(), null)
    );
    var execContext = mock(StepExecutionContext.class);
    when(execContext.lookupModel(anyString())).thenReturn(Optional.empty());
    var executor = new LlmGuardStepExecutor(execContext, (template, tag, ctx) -> template);
    var engine = new CapturingEngine();

    executor.rxExecuteWithEngine("judge", cfg, engine, stepContext()).test();

    return engine.captured.get();
  }

  private static StepContext stepContext() {
    var pctx = new PipelineContext(
      "hello",
      List.of(new ChatTurn(ChatRole.USER, "hello")),
      null,
      List.of(),
      null
    );
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    when(ctx.stepScribe()).thenReturn(SpanScribe.NOOP);
    return ctx;
  }

  private static final class CapturingEngine implements TextGenEngine {

    final AtomicReference<TextGenRequest> captured = new AtomicReference<>();

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public Flowable<ModelEngineToken> rxStream(int seqId) {
      return Flowable.never();
    }

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      captured.set(request);
      return Completable.never();
    }

    @Override
    public void close() {}
  }
}
