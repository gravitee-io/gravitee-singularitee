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
package io.gravitee.singularitee.plugin.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardOnEmpty;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardScope;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.plugin.guard.GuardStepConfig.GuardTrigger;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which texts a classifier guard inspects, and what it does when there are none. */
class GuardStepExecutorTest {

  private ClassifierEngine engine;
  private GuardStepExecutor executor;

  @BeforeEach
  void setUp() {
    // Real default methods, so rxClassifyBatch scores each text through the rxClassify stub.
    engine = mock(ClassifierEngine.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    when(engine.task()).thenReturn(ModelTasks.TEXT_CLASSIFICATION);
    // "toxic" scores high only on texts containing BAD.
    when(engine.rxClassify(any())).thenAnswer(inv -> {
      String text = inv.<ClassifyRequest>getArgument(0).text();
      float toxic = text.contains("BAD") ? 0.95f : 0.05f;
      return Single.just(new ClassifyResponse("toxic", toxic, Map.of("toxic", toxic), List.of()));
    });
    executor = new GuardStepExecutor(
      new StepExecutionContext(new ModelRegistry(), null, null),
      mock(TemplateRenderer.class)
    );
  }

  private static GuardStepConfig cfg(GuardAction action, GuardScope scope, GuardOnEmpty onEmpty) {
    return new GuardStepConfig(
      "tox",
      "",
      "",
      action,
      List.of(new GuardTrigger("toxic", 0.5f)),
      "",
      false,
      scope,
      onEmpty
    );
  }

  private static PipelineContext context(List<ChatTurn> messages) {
    String prompt = ChatTurn.lastUserContent(messages).orElse("");
    return new PipelineContext(prompt, messages, null, List.of(), null);
  }

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.stepScribe()).thenReturn(SpanScribe.NOOP);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private PipelineContext run(GuardStepConfig cfg, List<ChatTurn> messages) {
    var pctx = context(messages);
    executor.rxExecuteWithEngine("g", cfg, engine, stepContext(pctx)).blockingGet();
    return pctx;
  }

  private static final List<ChatTurn> HOSTILE_EARLIER_TURN = List.of(
    new ChatTurn(ChatRole.USER, "BAD request"),
    new ChatTurn(ChatRole.ASSISTANT, "ok"),
    new ChatTurn(ChatRole.USER, "harmless follow-up")
  );

  private static final List<ChatTurn> HOSTILE_TOOL_RESULT = List.of(
    new ChatTurn(ChatRole.USER, "summarise the page"),
    new ChatTurn(ChatRole.TOOL, "BAD instructions", List.of(), List.of(), "call-1", "fetch")
  );

  @Test
  void conversation_scope_catches_an_earlier_user_turn() {
    var pctx = run(
      cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT),
      HOSTILE_EARLIER_TURN
    );
    assertThat(pctx.isHalted()).isTrue();
    verify(engine, times(1)).rxClassifyBatch(argThat(batch -> batch.size() == 3));
  }

  @Test
  void last_user_scope_only_reads_the_last_turn() {
    var pctx = run(
      cfg(GuardAction.REJECT, GuardScope.LAST_USER, GuardOnEmpty.REJECT),
      HOSTILE_EARLIER_TURN
    );
    assertThat(pctx.isHalted()).isFalse();
    verify(engine, times(1)).rxClassifyBatch(argThat(batch -> batch.size() == 1));
  }

  @Test
  void conversation_scope_catches_a_tool_result() {
    assertThat(
      run(
        cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT),
        HOSTILE_TOOL_RESULT
      ).isHalted()
    ).isTrue();
    assertThat(
      run(
        cfg(GuardAction.REJECT, GuardScope.USER_TURNS, GuardOnEmpty.REJECT),
        HOSTILE_TOOL_RESULT
      ).isHalted()
    ).isFalse();
  }

  @Test
  void redact_masks_every_matching_turn_and_keeps_prompt_in_sync() {
    var messages = List.of(
      new ChatTurn(ChatRole.USER, "BAD one"),
      new ChatTurn(ChatRole.ASSISTANT, "fine"),
      new ChatTurn(ChatRole.USER, "BAD two")
    );
    var pctx = run(cfg(GuardAction.REDACT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT), messages);

    assertThat(pctx.isHalted()).isFalse();
    assertThat(pctx.messages())
      .extracting(ChatTurn::content)
      .containsExactly("************", "fine", "************");
    assertThat(pctx.get(PipelineContext.KEY_PROMPT)).isEqualTo("************");
    assertThat(pctx.get("g.redacted")).isEqualTo("************");
  }

  @Test
  void nothing_to_inspect_rejects_by_default() {
    var imageOnly = List.of(new ChatTurn(ChatRole.USER, ""));
    var pctx = run(
      cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT),
      imageOnly
    );
    assertThat(pctx.isHalted()).isTrue();
    verify(engine, never()).rxClassify(any());
  }

  @Test
  void nothing_to_inspect_passes_with_on_empty_skip() {
    var imageOnly = List.of(new ChatTurn(ChatRole.USER, ""));
    var pctx = run(cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.SKIP), imageOnly);
    assertThat(pctx.isHalted()).isFalse();
  }

  @Test
  void a_guard_whose_model_is_not_loaded_fails_the_request() {
    var pctx = context(HOSTILE_EARLIER_TURN);
    var cfg = cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT);

    assertThatThrownBy(() -> executor.execute("g", cfg, stepContext(pctx)).blockingGet())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("not loaded");
  }

  @Test
  void a_guard_bound_to_a_model_of_another_type_fails_the_request() {
    var registry = new ModelRegistry();
    registry.register("tox", "an-llm", mock(TextGenEngine.class), token -> {});
    var bound = new GuardStepExecutor(
      new StepExecutionContext(registry, null, null),
      mock(TemplateRenderer.class)
    );
    var pctx = context(HOSTILE_EARLIER_TURN);
    var cfg = cfg(GuardAction.REJECT, GuardScope.CONVERSATION, GuardOnEmpty.REJECT);

    assertThatThrownBy(() -> bound.execute("g", cfg, stepContext(pctx)).blockingGet())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("ClassifierEngine");
  }
}
