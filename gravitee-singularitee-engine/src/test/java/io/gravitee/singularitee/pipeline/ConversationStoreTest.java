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
package io.gravitee.singularitee.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.node.plugin.cache.standalone.StandaloneCacheManager;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for {@link ConversationStore}: the transcript — tool
 * call/result pairs included — and the todo plan must survive storage intact,
 * since the next turn's prompt is rebuilt from them.
 */
class ConversationStoreTest {

  private static ConversationStore store() {
    return new ConversationStore(new StandaloneCacheManager(), 60, 100);
  }

  @Test
  void transcript_with_tool_turns_round_trips_exactly() {
    var store = store();
    var turns = List.of(
      new ChatTurn(ChatRole.USER, "do the task"),
      new ChatTurn(
        ChatRole.ASSISTANT,
        null,
        List.of(),
        List.of(new ChatTurn.ToolCallTurn("call_1", "set_todos", "{\"todos\":[]}")),
        null,
        null
      ),
      new ChatTurn(ChatRole.TOOL, "{\"ok\":true}", List.of(), List.of(), "call_1", "set_todos"),
      new ChatTurn(ChatRole.ASSISTANT, "Which season?")
    );
    var todos = List.of(
      new PipelineContext.TodoItem("1", "a", "done"),
      new PipelineContext.TodoItem("2", "b", "in_progress")
    );

    store.put("resp_x", turns, todos, "use uv; pygame GUI");
    var stored = store.get("resp_x");

    var restoredTurns = ConversationStore.toChatTurns(stored);
    assertThat(restoredTurns).hasSize(4);
    assertThat(restoredTurns.get(1).toolCalls().get(0).name()).isEqualTo("set_todos");
    assertThat(restoredTurns.get(1).toolCalls().get(0).id()).isEqualTo("call_1");
    assertThat(restoredTurns.get(2).role()).isEqualTo(ChatRole.TOOL);
    assertThat(restoredTurns.get(2).toolCallId()).isEqualTo("call_1");
    assertThat(restoredTurns.get(3).content()).isEqualTo("Which season?");

    var restoredTodos = ConversationStore.toTodoItems(stored);
    assertThat(restoredTodos).hasSize(2);
    assertThat(restoredTodos.get(1).status()).isEqualTo("in_progress");
    assertThat(stored.constraints()).isEqualTo("use uv; pygame GUI");
  }

  @Test
  void unknown_id_and_disabled_store_return_null() {
    assertThat(store().get("nope")).isNull();
    var disabled = new ConversationStore(new StandaloneCacheManager(), 0, 100);
    assertThat(disabled.isEnabled()).isFalse();
    disabled.put("resp_x", List.of(new ChatTurn(ChatRole.USER, "hi")), List.of(), null);
    assertThat(disabled.get("resp_x")).isNull();
  }
}
