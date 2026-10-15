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
package io.gravitee.singularitee.engine.api.pipeline.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.GuardInputs.Target;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardOnEmpty;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardInputsTest {

  private static final List<ChatTurn> TRANSCRIPT = List.of(
    new ChatTurn(ChatRole.SYSTEM, "system rules"),
    new ChatTurn(ChatRole.USER, "first question"),
    new ChatTurn(
      ChatRole.ASSISTANT,
      "calling a tool",
      List.of(),
      List.of(new ChatTurn.ToolCallTurn("call-1", "search", "{}")),
      null,
      null
    ),
    new ChatTurn(ChatRole.TOOL, "tool result", List.of(), List.of(), "call-1", "search"),
    new ChatTurn(ChatRole.USER, "last question")
  );

  private static PipelineContext context(List<ChatTurn> messages, String prompt) {
    return new PipelineContext(prompt, messages, null, List.of(), Map.of("other", "field text"));
  }

  private static List<String> texts(List<Target> targets) {
    return targets.stream().map(Target::text).toList();
  }

  @Test
  void last_user_scope_reads_the_last_user_turn() {
    var targets = GuardInputs.resolve(
      "",
      GuardScope.LAST_USER,
      context(TRANSCRIPT, "last question")
    );
    assertThat(texts(targets)).containsExactly("last question");
  }

  @Test
  void user_turns_scope_reads_every_user_turn() {
    var targets = GuardInputs.resolve(
      "prompt",
      GuardScope.USER_TURNS,
      context(TRANSCRIPT, "last question")
    );
    assertThat(texts(targets)).containsExactly("first question", "last question");
  }

  @Test
  void conversation_scope_reads_every_turn_but_system() {
    var targets = GuardInputs.resolve(
      null,
      GuardScope.CONVERSATION,
      context(TRANSCRIPT, "last question")
    );
    assertThat(texts(targets)).containsExactly(
      "first question",
      "calling a tool",
      "tool result",
      "last question"
    );
  }

  @Test
  void a_named_field_is_read_alone() {
    var targets = GuardInputs.resolve(
      "other",
      GuardScope.CONVERSATION,
      context(TRANSCRIPT, "last question")
    );
    assertThat(texts(targets)).containsExactly("field text");
    assertThat(targets.getFirst().isTurn()).isFalse();
  }

  @Test
  void blank_turns_and_fields_yield_nothing() {
    var imageOnly = List.of(new ChatTurn(ChatRole.USER, ""));
    assertThat(GuardInputs.resolve("", GuardScope.CONVERSATION, context(imageOnly, ""))).isEmpty();
    assertThat(
      GuardInputs.resolve("missing", GuardScope.CONVERSATION, context(TRANSCRIPT, "x"))
    ).isEmpty();
  }

  @Test
  void redactions_rewrite_turns_keep_tool_metadata_and_sync_prompt() {
    var pctx = context(TRANSCRIPT, "last question");
    var targets = GuardInputs.resolve("", GuardScope.CONVERSATION, pctx);

    GuardInputs.applyRedactions(
      pctx,
      Map.of(targets.get(2), "[REDACTED] result", targets.get(3), "[REDACTED]")
    );

    assertThat(pctx.messages())
      .extracting(ChatTurn::content)
      .containsExactly(
        "system rules",
        "first question",
        "calling a tool",
        "[REDACTED] result",
        "[REDACTED]"
      );
    assertThat(pctx.messages().get(3).toolCallId()).isEqualTo("call-1");
    assertThat(pctx.messages().get(3).name()).isEqualTo("search");
    assertThat(pctx.get(PipelineContext.KEY_PROMPT)).isEqualTo("[REDACTED]");
  }

  @Test
  void primary_text_is_the_last_user_turn() {
    var pctx = context(TRANSCRIPT, "last question");
    var targets = GuardInputs.resolve("", GuardScope.CONVERSATION, pctx);
    assertThat(GuardInputs.primaryText(targets, pctx, Map.of())).isEqualTo("last question");
  }

  @Test
  void scope_with_a_named_field_is_refused() {
    assertThatThrownBy(() -> GuardInputs.parseScope("g", "other", "user_turns"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Step 'g'");
    assertThat(GuardInputs.parseScope("g", "prompt", "user_turns")).isEqualTo(
      GuardScope.USER_TURNS
    );
    assertThat(GuardInputs.parseScope("g", "other", null)).isEqualTo(GuardScope.CONVERSATION);
  }

  @Test
  void unknown_values_are_refused() {
    assertThatThrownBy(() -> GuardInputs.parseScope("g", "", "everything")).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() ->
      GuardInputs.parseOnEmpty("g", "maybe", GuardAction.REJECT)
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void on_empty_defaults_to_reject_except_for_warn_guards() {
    assertThat(GuardInputs.parseOnEmpty("g", null, GuardAction.REJECT)).isEqualTo(
      GuardOnEmpty.REJECT
    );
    assertThat(GuardInputs.parseOnEmpty("g", null, GuardAction.REDACT)).isEqualTo(
      GuardOnEmpty.REJECT
    );
    assertThat(GuardInputs.parseOnEmpty("g", null, GuardAction.WARN)).isEqualTo(GuardOnEmpty.SKIP);
    assertThat(GuardInputs.parseOnEmpty("g", "reject", GuardAction.WARN)).isEqualTo(
      GuardOnEmpty.REJECT
    );
  }
}
