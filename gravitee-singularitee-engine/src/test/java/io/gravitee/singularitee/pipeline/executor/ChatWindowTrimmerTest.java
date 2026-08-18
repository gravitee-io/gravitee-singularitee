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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ChatWindowTrimmer}.
 *
 * <p>Uses a 1-token-per-char counter so budgets read directly in characters.
 */
class ChatWindowTrimmerTest {

  /** 1 token per character — makes budgets trivially readable. */
  private static final TokenCounter CHAR_COUNTER = s -> s == null ? 0 : s.length();

  private static final String TOOL_TAG = "<tool_call>";

  private static Map<String, Object> msg(String role, String content) {
    return Map.of("role", role, "content", content);
  }

  private static String chars(int n) {
    return "x".repeat(n);
  }

  // With contextTokens=1000, maxTokens=0: budget = 1000 - 0 - 50 = 950,
  // fast path threshold = 760, trim target = 665.

  @Test
  void fastPathReturnsSameInstance() {
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(100)),
      msg("user", chars(100)),
      msg("assistant", chars(100)),
      msg("user", chars(100))
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isSameAs(messages);
  }

  @Test
  void zeroContextReturnsSameInstance() {
    List<Map<String, Object>> messages = List.of(msg("user", chars(100_000)));
    var out = ChatWindowTrimmer.trim(messages, 0, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isSameAs(messages);
  }

  @Test
  void systemMessagesArePinnedAndOldTurnsDropNewestFirstRetention() {
    // total = 200 + 5*200 = 1200 > 760 (fast path exceeded); target = 665 - 200 = 465
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(200)),
      msg("user", chars(200)), // oldest — dropped
      msg("assistant", chars(200)), // dropped
      msg("user", chars(200)), // kept (newest 3 * 200 = 600 > 465? no — see below)
      msg("assistant", chars(200)),
      msg("user", chars(200))
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    // target 465: newest unit (200) always kept, next (200) fits (400),
    // next (200) would make 600 > 465 → stop. Kept = system + last 2.
    assertThat(out).hasSize(3);
    assertThat(out.get(0).get("role")).isEqualTo("system");
    assertThat(out.get(1)).isSameAs(messages.get(4));
    assertThat(out.get(2)).isSameAs(messages.get(5));
  }

  @Test
  void hysteresisResultFitsSeventyPercentTarget() {
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(msg("system", chars(100)));
    for (int i = 0; i < 20; i++) {
      messages.add(msg(i % 2 == 0 ? "user" : "assistant", chars(100)));
    }
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    long total = out
      .stream()
      .mapToLong(m -> CHAR_COUNTER.count((String) m.get("content")))
      .sum();
    // budget 950, target 70% = 665
    assertThat(total).isLessThanOrEqualTo(665);
  }

  @Test
  void toolCallUnitDroppedAtomically() {
    // The assistant tool-call and its following tool result must go together.
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(100)),
      msg("user", chars(100)), // dropped
      msg("assistant", TOOL_TAG + chars(189)), // tool call (200 tokens) — dropped
      msg("user", "<tool_response>" + chars(185)), // tool result (200) — dropped WITH it
      msg("assistant", chars(200)), // kept
      msg("user", chars(200)) // kept (newest)
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    // target = 665 - 100 = 565: newest (200) kept, next (200) kept (400);
    // tool unit = 400 → 800 > 565 → both dropped together.
    assertThat(out).hasSize(3);
    assertThat(out.get(1)).isSameAs(messages.get(4));
    assertThat(out.get(2)).isSameAs(messages.get(5));
    assertThat(
      out.stream().noneMatch(m -> ((String) m.get("content")).contains(TOOL_TAG))
    ).isTrue();
  }

  @Test
  void structuredToolCallUnitDroppedAtomically() {
    // Modern OpenAI shape: assistant carries a tool_calls LIST (content empty)
    // and the result is a role=tool message. Splitting them orphans the tool
    // message, which chat templates reject ("tool role without a previous
    // assistant tool call") — observed live with an agent client's huge
    // read-file result forcing a trim.
    Map<String, Object> call = new java.util.LinkedHashMap<>(msg("assistant", ""));
    call.put("tool_calls", List.of(Map.of("id", "call_1", "function", Map.of("name", "read"))));
    Map<String, Object> result = new java.util.LinkedHashMap<>(msg("tool", chars(400)));
    result.put("tool_call_id", "call_1");
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(100)),
      msg("user", chars(100)),
      call,
      result,
      msg("assistant", chars(200)),
      msg("user", chars(200))
    );

    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);

    // Whatever is dropped, no retained window may contain the tool result
    // without its assistant call.
    for (int i = 0; i < out.size(); i++) {
      if ("tool".equals(out.get(i).get("role"))) {
        assertThat(i).isGreaterThan(0);
        assertThat(out.get(i - 1).get("tool_calls")).isNotNull();
      }
    }
    assertThat(out.get(out.size() - 1)).isSameAs(messages.get(5));
  }

  @Test
  void retainedWindowNeverStartsWithToolMessages() {
    // Safety net: even if unit bookkeeping missed a shape, leading tool
    // messages are dropped rather than orphaned.
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(50)),
      msg("user", chars(600)),
      msg("tool", chars(300)), // pathological: tool result with no visible call
      msg("user", chars(200)),
      msg("assistant", chars(150))
    );

    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);

    assertThat(out).isNotEmpty();
    var firstNonSystem = out
      .stream()
      .filter(m -> !"system".equals(m.get("role")))
      .findFirst()
      .orElseThrow();
    assertThat(firstNonSystem.get("role")).isNotEqualTo("tool");
  }

  @Test
  void toolCallUnitKeptAtomically() {
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(50)),
      msg("user", chars(500)), // oldest — dropped
      msg("assistant", TOOL_TAG + chars(89)), // tool call (100) — kept as a unit
      msg("user", "<tool_response>" + chars(85)), // tool result (100) — kept
      msg("user", chars(100)) // newest — kept
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    // target = 665 - 50 = 615: newest (100), tool unit (200) → 300 ≤ 615 kept;
    // oldest user (500) → 800 > 615 dropped.
    assertThat(out).hasSize(4);
    assertThat(out.get(1)).isSameAs(messages.get(2));
    assertThat(out.get(2)).isSameAs(messages.get(3));
    assertThat(out.get(3)).isSameAs(messages.get(4));
  }

  @Test
  void oversizedLastMessageIsHeadTruncatedWithMarker() {
    String tail = "THE-VERY-END";
    String content = chars(2000) + tail;
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(50)),
      msg("user", chars(100)),
      msg("user", content)
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    // Last message alone (2012) > target (615) → kept but head-truncated.
    var last = out.get(out.size() - 1);
    String trimmedContent = (String) last.get("content");
    assertThat(trimmedContent).startsWith(ChatWindowTrimmer.TRIM_MARKER);
    assertThat(trimmedContent).endsWith(tail);
    assertThat(trimmedContent.length()).isLessThan(content.length());
    // System stays pinned even then.
    assertThat(out.get(0).get("role")).isEqualTo("system");
  }

  @Test
  void lastMessageAlwaysKeptEvenWhenAloneOverBudget() {
    List<Map<String, Object>> messages = List.of(msg("user", chars(5000)));
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).hasSize(1);
    assertThat((String) out.get(0).get("content")).startsWith(ChatWindowTrimmer.TRIM_MARKER);
  }

  @Test
  void maxTokensReducesTheBudget() {
    // context 1000, maxTokens 450 (below the ctx/2 cap) → budget = 1000 - 450 - 50
    // = 500; fast path 400; target 350.
    List<Map<String, Object>> messages = List.of(
      msg("user", chars(160)),
      msg("assistant", chars(160)),
      msg("user", chars(160))
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 450, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isNotSameAs(messages);
    // newest two (320) kept; the third would make 480 > 350 → dropped.
    assertThat(out).hasSize(2);
    assertThat(out.get(1)).isSameAs(messages.get(2));
  }

  @Test
  void blankToolTagFallsBackToDefault() {
    // Same scenario as toolCallUnitDroppedAtomically but with a blank tag —
    // the default <tool_call> must still bind the pair.
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(100)),
      msg("user", chars(100)),
      msg("assistant", "<tool_call>" + chars(189)),
      msg("user", "<tool_response>" + chars(185)),
      msg("assistant", chars(200)),
      msg("user", chars(200))
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, "");
    assertThat(out).hasSize(3);
    assertThat(
      out.stream().noneMatch(m -> ((String) m.get("content")).contains("<tool_call>"))
    ).isTrue();
  }

  @Test
  void greedyMaxTokensCannotStarveThePrompt() {
    // Regression: a client requesting max_tokens ~ contextTokens (OpenCode sends
    // 32000 against a 32768 window) must not shred the conversation. The
    // reservation is capped at contextTokens/2, so a small prompt passes untouched.
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(100)),
      msg("user", "Look at the README.md")
    );
    List<Map<String, Object>> out = ChatWindowTrimmer.trim(
      messages,
      1000,
      990, // reservation would leave a negative budget without the cap
      CHAR_COUNTER,
      TOOL_TAG
    );
    assertThat(out).isSameAs(messages);
  }

  @Test
  void allSystemMessagesReturnsSameInstance() {
    List<Map<String, Object>> messages = List.of(
      msg("system", chars(1000)),
      msg("system", chars(1000))
    );
    var out = ChatWindowTrimmer.trim(messages, 1000, 0, CHAR_COUNTER, TOOL_TAG);
    assertThat(out).isSameAs(messages);
  }
}
