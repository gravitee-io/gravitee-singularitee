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
package io.gravitee.singularitee.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.pipeline.TodoStatus;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.TodoStepConfig;
import io.gravitee.singularitee.protocol.ToolCall;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TodoStepExecutor}: server-side execution of the todo
 * tools, transcript bookkeeping, and the never-leak-to-client invariant.
 */
class TodoStepExecutorTest {

  private final TodoStepExecutor executor = new TodoStepExecutor();

  private static PipelineContext pctx() {
    var pctx = new PipelineContext(
      "do the task",
      List.of(new ChatTurn(ChatRole.USER, "do the task")),
      null,
      List.of(),
      null
    );
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    return pctx;
  }

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private static ToolCall call(String name, String argsJson) {
    return ToolCall.newBuilder().setName(name).setArgumentsJson(argsJson).build();
  }

  private static TodoStepConfig config() {
    return TodoStepConfig.newBuilder().setHandledStepId("work").build();
  }

  @Test
  void set_todos_installs_plan_and_loops_to_handled_step() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(
      List.of(
        call(
          "set_todos",
          "{\"todos\":[{\"id\":\"1\",\"title\":\"a\"},{\"id\":\"2\",\"title\":\"b\"}]}"
        )
      )
    );
    int turnsBefore = pctx.messages().size();

    String next = executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("work");
    assertThat(pctx.todos()).hasSize(2);
    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    assertThat(pctx.get("todos.total")).isEqualTo("2");
    assertThat(pctx.get("todos.remaining")).isEqualTo("2");
    // Consumed calls never leak; internal generation reverts to a plain stop.
    assertThat(pctx.extractedToolCalls()).isEmpty();
    assertThat(pctx.lastEngineFinishReason()).isEqualTo(FinishReason.FINISH_REASON_STOP);
    // Transcript carries the call and its TOOL answer.
    assertThat(pctx.messages()).hasSize(turnsBefore + 2);
    ChatTurn callTurn = pctx.messages().get(turnsBefore);
    ChatTurn resultTurn = pctx.messages().get(turnsBefore + 1);
    assertThat(callTurn.role()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(callTurn.toolCalls()).hasSize(1);
    assertThat(resultTurn.role()).isEqualTo(ChatRole.TOOL);
    assertThat(resultTurn.toolCallId()).isEqualTo(callTurn.toolCalls().get(0).id());
  }

  @Test
  void complete_todo_marks_done_and_promotes_next() {
    var pctx = pctx();
    pctx.setTodos(
      List.of(
        new PipelineContext.TodoItem("1", "a", TodoStatus.IN_PROGRESS, null),
        new PipelineContext.TodoItem("2", "b", TodoStatus.PENDING, null)
      )
    );
    pctx.setExtractedToolCalls(List.of(call("complete_todo", "{\"id\":\"1\"}")));

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.DONE);
    assertThat(pctx.todos().get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    assertThat(pctx.get("todos.remaining")).isEqualTo("1");
  }

  @Test
  void client_tool_call_halts_the_turn_for_the_client() {
    var pctx = pctx();
    var clientCall = call("send_email", "{\"to\":\"a@b.com\"}");
    pctx.setExtractedToolCalls(List.of(clientCall));

    executor.execute("track", config(), stepContext(pctx)).test().assertComplete();

    // The pipeline ends with finish_reason tool_calls and the call attached:
    // the client must execute it — looping onward would swallow the call.
    assertThat(pctx.isHalted()).isTrue();
    assertThat(pctx.haltReason()).isEqualTo(FinishReason.FINISH_REASON_TOOL_CALLS);
    assertThat(pctx.extractedToolCalls()).containsExactly(clientCall);
    assertThat(pctx.get("track.client_tool_calls")).isEqualTo("1");
  }

  @Test
  void no_calls_at_all_passes_through() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(List.of());

    String next = executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("next");
    assertThat(pctx.isHalted()).isFalse();
  }

  @Test
  void mixed_calls_execute_todo_then_halt_with_client_call() {
    var pctx = pctx();
    var clientCall = call("send_email", "{\"to\":\"a@b.com\"}");
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"1\",\"title\":\"a\"}]}"), clientCall)
    );

    executor.execute("track", config(), stepContext(pctx)).test().assertComplete();

    // The todo call executed server-side, the client call ends the turn.
    assertThat(pctx.todos()).hasSize(1);
    assertThat(pctx.isHalted()).isTrue();
    assertThat(pctx.haltReason()).isEqualTo(FinishReason.FINISH_REASON_TOOL_CALLS);
    assertThat(pctx.extractedToolCalls()).containsExactly(clientCall);
  }

  @Test
  void malformed_arguments_fail_open_with_error_result() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(List.of(call("set_todos", "not json at all")));
    int turnsBefore = pctx.messages().size();

    String next = executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(next).isEqualTo("work"); // still handled — the model retries with the error
    assertThat(pctx.get("track.todo_error")).isNotBlank();
    // The error rides back to the model as the TOOL result.
    assertThat(pctx.messages()).hasSize(turnsBefore + 2);
    assertThat(pctx.messages().get(turnsBefore + 1).content()).contains("\"ok\":false");
  }

  @Test
  void ask_user_streams_question_and_halts() {
    var pctx = pctx();
    pctx.setTodos(
      List.of(
        new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null),
        new PipelineContext.TodoItem("2", "b", TodoStatus.IN_PROGRESS, null)
      )
    );
    pctx.setExtractedToolCalls(
      List.of(call("ask_user", "{\"question\":\"Which season is your favourite?\"}"))
    );
    var streamed = new StringBuilder();
    var response = mock(io.vertx.core.streams.WriteStream.class);
    when(response.write(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
      io.gravitee.singularitee.protocol.InferResponse r = inv.getArgument(0);
      if (
        r.getEventType() ==
        io.gravitee.singularitee.protocol.ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA
      ) {
        streamed.append(r.getResponseOutputTextDelta().getDelta());
      }
      return io.vertx.core.Future.succeededFuture();
    });
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    when(ctx.response()).thenReturn(response);

    executor.execute("track", config(), ctx).blockingGet();

    // The question is the visible answer, the pipeline is halted (BREAK maps
    // to "stop" on the OpenAI surface), the plan is untouched.
    assertThat(streamed.toString()).isEqualTo("Which season is your favourite?");
    assertThat(pctx.isHalted()).isTrue();
    assertThat(pctx.haltReason()).isEqualTo(FinishReason.FINISH_REASON_BREAK_CONDITION);
    assertThat(pctx.get("track.question")).isEqualTo("Which season is your favourite?");
    assertThat(pctx.todos().get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    assertThat(pctx.extractedToolCalls()).isEmpty();
  }

  @Test
  void set_todos_stores_constraints_and_a_resend_without_them_keeps_them() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(
      List.of(
        call(
          "set_todos",
          "{\"todos\":[{\"id\":\"1\",\"title\":\"a\"}],\"constraints\":\"use uv; pygame GUI\"}"
        )
      )
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();
    assertThat(pctx.todoConstraints()).isEqualTo("use uv; pygame GUI");

    // A re-plan without constraints must not drop what the user decided.
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"1\",\"title\":\"a2\"}]}"))
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();
    assertThat(pctx.todoConstraints()).isEqualTo("use uv; pygame GUI");
  }

  @Test
  void jinja_constraints_variable_is_bound_and_empty_when_unset() {
    var pctx = pctx();
    assertThat(JinjaContextHelper.buildBaseContext(pctx).get("constraints")).isEqualTo("");
    pctx.setTodoConstraints("use uv");
    assertThat(JinjaContextHelper.buildBaseContext(pctx).get("constraints")).isEqualTo("use uv");
  }

  @Test
  void client_declared_ask_user_passes_through_as_client_call() {
    var pctx = new PipelineContext(
      "do the task",
      List.of(new ChatTurn(ChatRole.USER, "do the task")),
      null,
      List.of(
        io.gravitee.singularitee.protocol.ToolDefinition.newBuilder()
          .setName("ask_user")
          .setDescription("client-owned multiple-choice questions")
          .build()
      ),
      null
    );
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    pctx.setExtractedToolCalls(
      List.of(
        call("ask_user", "{\"questions\":[{\"question\":\"Which?\",\"options\":[\"a\",\"b\"]}]}")
      )
    );
    int turnsBefore = pctx.messages().size();

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    // Delegated: the call survives to the client, nothing executed server-side.
    assertThat(pctx.extractedToolCalls()).hasSize(1);
    assertThat(pctx.extractedToolCalls().get(0).getName()).isEqualTo("ask_user");
    assertThat(pctx.lastEngineFinishReason()).isEqualTo(FinishReason.FINISH_REASON_TOOL_CALLS);
    assertThat(pctx.messages()).hasSize(turnsBefore);
  }

  @Test
  void client_declared_set_todos_is_still_executed_server_side() {
    var pctx = new PipelineContext(
      "do the task",
      List.of(new ChatTurn(ChatRole.USER, "do the task")),
      null,
      List.of(
        io.gravitee.singularitee.protocol.ToolDefinition.newBuilder()
          .setName("set_todos")
          .setDescription("a client trying to own the plan")
          .build()
      ),
      null
    );
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"1\",\"title\":\"a\"}]}"))
    );

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos()).hasSize(1);
    assertThat(pctx.extractedToolCalls()).isEmpty();
  }

  @Test
  void set_todos_carries_per_item_proof_through_plan_and_progress() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(
      List.of(
        call(
          "set_todos",
          "{\"todos\":[{\"id\":\"1\",\"title\":\"grid logic\",\"proof\":\"pytest tests/test_grid.py passes\"},{\"id\":\"2\",\"title\":\"cli\"}]}"
        )
      )
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos().get(0).proof()).isEqualTo("pytest tests/test_grid.py passes");
    assertThat(pctx.todos().get(1).proof()).isNull();
    // status transitions keep the proof
    pctx.completeTodo("1", null);
    assertThat(pctx.todos().get(0).proof()).isEqualTo("pytest tests/test_grid.py passes");
    assertThat(pctx.todos().get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    assertThat(pctx.todos().get(1).proof()).isNull();
  }

  @Test
  void complete_todo_with_unknown_id_falls_back_to_the_single_in_progress_item() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(
      List.of(
        call(
          "set_todos",
          "{\"todos\":[{\"id\":\"1\",\"title\":\"readme\"},{\"id\":\"2\",\"title\":\"tests\"}]}"
        )
      )
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.completeTodo("write_readme", null)).isTrue();
    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.DONE);
    assertThat(pctx.todos().get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    // now two guesses in a row: the fallback stays unambiguous
    assertThat(pctx.completeTodo("nope", null)).isTrue();
    assertThat(pctx.todos().get(1).status()).isEqualTo(TodoStatus.DONE);
    assertThat(pctx.completeTodo("still-nope", null)).isFalse();
  }

  @Test
  void set_todos_is_rejected_while_a_plan_is_in_flight() {
    var pctx = pctx();
    pctx.setTodos(
      List.of(
        new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null),
        new PipelineContext.TodoItem("2", "b", TodoStatus.IN_PROGRESS, null)
      )
    );
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"x\",\"title\":\"rewrite\"}]}"))
    );
    int turnsBefore = pctx.messages().size();

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    // The installed plan survives untouched; the model sees the refusal.
    assertThat(pctx.todos()).hasSize(2);
    assertThat(pctx.todos().get(1).title()).isEqualTo("b");
    assertThat(pctx.messages().get(turnsBefore + 1).content()).contains("still in progress");
  }

  @Test
  void set_todos_is_accepted_after_the_restore_policy_unlocks_a_finished_plan() {
    var pctx = pctx();
    pctx.setTodos(List.of(new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null)));
    // PipelineExecutor lifts the lock at restore when the plan is finished
    // and the request opens with a fresh user message.
    pctx.setPlanLocked(false);
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"x\",\"title\":\"next task\"}]}"))
    );

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos()).hasSize(1);
    assertThat(pctx.todos().get(0).title()).isEqualTo("next task");
    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.IN_PROGRESS);
  }

  @Test
  void finished_plan_rejects_set_todos_until_the_restore_policy_unlocks_it() {
    var pctx = pctx();
    // Installing a plan locks it; finishing it does NOT lift the lock.
    pctx.setTodos(List.of(new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null)));
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"x\",\"title\":\"chained plan\"}]}"))
    );

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos()).hasSize(1);
    assertThat(pctx.todos().get(0).title()).isEqualTo("a");
  }

  @Test
  void ask_user_saves_plan_to_session_store() {
    var store = new io.gravitee.singularitee.pipeline.TodoSessionStore(
      new io.gravitee.node.plugin.cache.standalone.StandaloneCacheManager(),
      60,
      100
    );
    var withStore = new TodoStepExecutor(store);
    var pctx = new PipelineContext(
      "do the task",
      List.of(new ChatTurn(ChatRole.USER, "do the task")),
      null,
      List.of(),
      null
    );
    pctx.setCacheKey("session-1");
    pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
    pctx.setTodos(List.of(new PipelineContext.TodoItem("1", "a", TodoStatus.IN_PROGRESS, null)));
    pctx.setExtractedToolCalls(List.of(call("ask_user", "{\"question\":\"Which?\"}")));

    withStore.execute("track", config(), stepContext(pctx)).blockingGet();

    var restored = store.restore("session-1").orElseThrow();
    assertThat(restored.todos()).hasSize(1);
    assertThat(restored.todos().get(0).status()).isEqualTo(TodoStatus.IN_PROGRESS);
  }

  @Test
  void restore_round_trip_preserves_statuses_without_promotion() {
    var store = new io.gravitee.singularitee.pipeline.TodoSessionStore(
      new io.gravitee.node.plugin.cache.standalone.StandaloneCacheManager(),
      60,
      100
    );
    // A plan paused at step 3/5: exact statuses must survive the round trip.
    store.save(
      "s",
      List.of(
        new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null),
        new PipelineContext.TodoItem("2", "b", TodoStatus.DONE, null),
        new PipelineContext.TodoItem("3", "c", TodoStatus.IN_PROGRESS, null),
        new PipelineContext.TodoItem("4", "d", TodoStatus.PENDING, null),
        new PipelineContext.TodoItem("5", "e", TodoStatus.PENDING, null)
      ),
      "use uv"
    );

    var pctx = pctx();
    var restored = store.restore("s").orElseThrow();
    pctx.restoreTodos(restored.todos());
    pctx.setTodoConstraints(restored.constraints());

    assertThat(pctx.todos())
      .extracting(PipelineContext.TodoItem::status)
      .containsExactly(
        TodoStatus.DONE,
        TodoStatus.DONE,
        TodoStatus.IN_PROGRESS,
        TodoStatus.PENDING,
        TodoStatus.PENDING
      );
    assertThat(pctx.get("todos.completed")).isEqualTo("2");
    assertThat(pctx.get("todos.remaining")).isEqualTo("3");
    assertThat(pctx.todoConstraints()).isEqualTo("use uv");

    store.clear("s");
    assertThat(store.restore("s")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jinja_todos_variable_is_the_item_list_not_the_mirrored_field_map() {
    // Regression: the mirrored condition fields (todos.total/completed/remaining)
    // nest into a map under the "todos" key in the step-output context and used
    // to REPLACE the todo list — templates then iterated three key strings and
    // the model never saw its plan.
    var pctx = pctx();
    pctx.setTodos(
      List.of(
        new PipelineContext.TodoItem("1", "write spring haiku", TodoStatus.IN_PROGRESS, null),
        new PipelineContext.TodoItem("2", "write autumn haiku", TodoStatus.PENDING, null)
      )
    );

    var ctx = JinjaContextHelper.buildBaseContext(pctx);

    var todos = (List<java.util.Map<String, Object>>) ctx.get("todos");
    assertThat(todos).hasSize(2);
    assertThat(todos.get(0).get("title")).isEqualTo("write spring haiku");
    assertThat(todos.get(0).get("status")).isEqualTo("in_progress");
  }

  @Test
  void empty_set_todos_is_refused_and_installs_no_plan() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(List.of(call("set_todos", "{\"todos\":[]}")));

    executor.execute("apply_plan", config(), stepContext(pctx)).blockingGet();

    // No 0/0 plan: todos stay absent so plan_check routes to the direct branch,
    // and the model sees an explicit error instead of a hollow success.
    assertThat(pctx.todos()).isEmpty();
    assertThat(pctx.get("todos.total")).isNull();
    var toolTurn = pctx
      .messages()
      .stream()
      .filter(t -> t.role() == io.gravitee.singularitee.engine.ChatRole.TOOL)
      .reduce((a, b) -> b)
      .orElseThrow();
    assertThat(toolTurn.content()).contains("\"ok\":false").contains("at least one item");
  }

  @Test
  void unknown_complete_id_falls_back_to_single_in_progress_item() {
    var pctx = pctx();
    pctx.setTodos(List.of(new PipelineContext.TodoItem("1", "a", TodoStatus.IN_PROGRESS, null)));
    pctx.setExtractedToolCalls(List.of(call("complete_todo", "{\"id\":\"99\"}")));

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.DONE);
  }

  @Test
  void unknown_complete_id_with_no_unambiguous_target_returns_error_result() {
    var pctx = pctx();
    pctx.setTodos(List.of(new PipelineContext.TodoItem("1", "a", TodoStatus.DONE, null)));
    pctx.setExtractedToolCalls(List.of(call("complete_todo", "{\"id\":\"99\"}")));
    int turnsBefore = pctx.messages().size();

    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.DONE);
    assertThat(pctx.messages().get(turnsBefore + 1).content()).contains("no todo with id 99");
  }

  @Test
  void complete_todo_note_is_recorded_as_the_items_proof() {
    var pctx = pctx();
    pctx.setExtractedToolCalls(
      List.of(call("set_todos", "{\"todos\":[{\"id\":\"1\",\"title\":\"spring haiku\"}]}"))
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    pctx.setExtractedToolCalls(
      List.of(
        call("complete_todo", "{\"id\":\"1\",\"note\":\"Cherry blossoms drift / over the pond\"}")
      )
    );
    executor.execute("track", config(), stepContext(pctx)).blockingGet();

    assertThat(pctx.todos().get(0).status()).isEqualTo(TodoStatus.DONE);
    assertThat(pctx.todos().get(0).proof()).isEqualTo("Cherry blossoms drift / over the pond");
  }

  @Test
  void a_blank_note_keeps_the_existing_proof() {
    var pctx = pctx();
    pctx.setTodos(
      List.of(
        new PipelineContext.TodoItem("1", "spring haiku", TodoStatus.IN_PROGRESS, "earlier proof")
      )
    );
    assertThat(pctx.completeTodo("1", "  ")).isTrue();
    assertThat(pctx.todos().get(0).proof()).isEqualTo("earlier proof");
  }
}
