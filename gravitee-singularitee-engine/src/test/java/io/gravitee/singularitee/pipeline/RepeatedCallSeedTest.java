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

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stuck-call signal: the trailing run of consecutive assistant tool-call
 * turns with identical (name, arguments) — an engine-counted fact of the
 * transcript that a graph gate uses to break behavioral loops.
 */
class RepeatedCallSeedTest {

  private static long count(List<ChatTurn> turns) throws Exception {
    var pctx = new PipelineContext(null, turns, null, List.of(), null);
    Method m = PipelineExecutor.class.getDeclaredMethod(
      "trailingRepeatedCalls",
      PipelineContext.class
    );
    m.setAccessible(true);
    return (long) m.invoke(null, pctx);
  }

  private static ChatTurn callTurn(String name, String args) {
    return new ChatTurn(
      ChatRole.ASSISTANT,
      "",
      List.of(),
      List.of(new ChatTurn.ToolCallTurn("c", name, args)),
      null,
      null
    );
  }

  private static ChatTurn resultTurn(String text) {
    return new ChatTurn(ChatRole.TOOL, text, List.of(), List.of(), "c", "edit");
  }

  @Test
  void counts_trailing_identical_calls_skipping_results() throws Exception {
    var turns = List.of(
      new ChatTurn(ChatRole.USER, "fix it"),
      callTurn("edit", "{\"old\":\"a\"}"),
      resultTurn("3 occurrences, must be unique"),
      callTurn("edit", "{\"old\":\"a\"}"),
      resultTurn("3 occurrences, must be unique"),
      callTurn("edit", "{\"old\":\"a\"}"),
      resultTurn("3 occurrences, must be unique")
    );
    assertThat(count(turns)).isEqualTo(3);
  }

  @Test
  void different_arguments_break_the_run() throws Exception {
    var turns = List.of(
      callTurn("edit", "{\"old\":\"a\"}"),
      resultTurn("err"),
      callTurn("edit", "{\"old\":\"b\"}"),
      resultTurn("err")
    );
    assertThat(count(turns)).isEqualTo(0);
  }

  @Test
  void single_call_is_not_a_run_and_ask_user_is_exempt() throws Exception {
    assertThat(count(List.of(callTurn("bash", "{}"), resultTurn("ok")))).isEqualTo(0);
    assertThat(
      count(
        List.of(
          callTurn("ask_user", "{\"q\":1}"),
          resultTurn("a"),
          callTurn("ask_user", "{\"q\":1}"),
          resultTurn("a"),
          callTurn("ask_user", "{\"q\":1}")
        )
      )
    ).isEqualTo(0);
  }
}
