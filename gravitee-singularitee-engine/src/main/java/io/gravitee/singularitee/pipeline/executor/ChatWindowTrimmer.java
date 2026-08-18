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

import io.gravitee.singularitee.engine.ChatTurn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trims a chat history to fit a model's context window.
 *
 * <p>Budget: {@code contextTokens - max(0, maxTokens) - contextTokens/20}
 * (a 5% safety margin on top of the completion reservation). When the whole
 * conversation is comfortably below the budget (fast path, &lt; 80%), the
 * input list is returned <em>unchanged — same instance</em>, so callers can
 * detect trimming with an identity check.
 *
 * <p>Trimming rules:
 * <ul>
 *   <li>All <em>leading</em> system messages are pinned (never dropped).</li>
 *   <li>The remainder is walked newest→oldest, keeping messages while they
 *       fit a 70%-of-budget target (hysteresis: a trimmed conversation leaves
 *       headroom so the very next turn does not immediately re-trim).</li>
 *   <li>An assistant message carrying the step's tool-call open tag and the
 *       message immediately following it (the tool result) form one atomic
 *       unit — kept or dropped together.</li>
 *   <li>The last message overall (the newest turn) is always kept; if it
 *       alone exceeds the target its content is truncated from the head
 *       (tail kept), prefixed with {@value #TRIM_MARKER}. Media-carrying
 *       turns are never truncated — they are kept whole.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ChatWindowTrimmer {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChatWindowTrimmer.class);

  /** Prefix marking a head-truncated message content. */
  public static final String TRIM_MARKER = "[...trimmed]";

  /** Default tool-call open tag when the step config defines none. */
  public static final String DEFAULT_TOOL_OPEN_TAG = "<tool_call>";

  /** Fast path: no trim when the total is below this fraction of the budget. */
  private static final double FAST_PATH_RATIO = 0.8;

  /** Hysteresis: trim down to this fraction of the budget. */
  private static final double TARGET_RATIO = 0.7;

  private ChatWindowTrimmer() {}

  /**
   * Trims a {@code role}/{@code content} map-shaped history (pipeline path).
   *
   * @param messages      the conversation ({@code {"role","content"}} maps)
   * @param contextTokens the model context window in tokens; {@code <= 0} disables trimming
   * @param maxTokens     the completion reservation (0 when unset)
   * @param counter       token counter (exact or estimated)
   * @param toolOpenTag   the step's tool-call open tag (blank/null → {@value #DEFAULT_TOOL_OPEN_TAG})
   * @return the same list instance when no trim is needed, otherwise a new trimmed list
   */
  public static List<Map<String, Object>> trim(
    List<Map<String, Object>> messages,
    int contextTokens,
    int maxTokens,
    TokenCounter counter,
    String toolOpenTag
  ) {
    return trimGeneric(messages, contextTokens, maxTokens, counter, toolOpenTag, MAP_ADAPTER);
  }

  /**
   * Trims a {@link ChatTurn} history (direct-model path). Media-carrying turns
   * are preserved exactly — never content-truncated.
   *
   * @see #trim(List, int, int, TokenCounter, String)
   */
  public static List<ChatTurn> trimTurns(
    List<ChatTurn> messages,
    int contextTokens,
    int maxTokens,
    TokenCounter counter,
    String toolOpenTag
  ) {
    return trimGeneric(messages, contextTokens, maxTokens, counter, toolOpenTag, TURN_ADAPTER);
  }

  // -----------------------------------------------------------------------
  // Generic core
  // -----------------------------------------------------------------------

  /** Message-shape accessor so the same algorithm serves maps and ChatTurns. */
  private interface Adapter<M> {
    String role(M m);
    String content(M m);
    /** Whether the message carries STRUCTURED tool calls (OpenAI {@code tool_calls}). */
    boolean hasToolCalls(M m);
    /** {@code false} when the message must never be content-truncated (e.g. carries media). */
    boolean truncatable(M m);
    M withContent(M m, String content);
  }

  private static final Adapter<Map<String, Object>> MAP_ADAPTER = new Adapter<>() {
    @Override
    public String role(Map<String, Object> m) {
      Object r = m.get("role");
      return r != null ? r.toString() : "";
    }

    @Override
    public String content(Map<String, Object> m) {
      Object c = m.get("content");
      return c != null ? c.toString() : "";
    }

    @Override
    public boolean hasToolCalls(Map<String, Object> m) {
      Object calls = m.get("tool_calls");
      return calls instanceof List<?> l && !l.isEmpty();
    }

    @Override
    public boolean truncatable(Map<String, Object> m) {
      return true;
    }

    @Override
    public Map<String, Object> withContent(Map<String, Object> m, String content) {
      Map<String, Object> copy = new LinkedHashMap<>(m);
      copy.put("content", content);
      return copy;
    }
  };

  private static final Adapter<ChatTurn> TURN_ADAPTER = new Adapter<>() {
    @Override
    public String role(ChatTurn m) {
      return m.role() != null ? m.role().name().toLowerCase(Locale.ROOT) : "";
    }

    @Override
    public String content(ChatTurn m) {
      return m.content() != null ? m.content() : "";
    }

    @Override
    public boolean hasToolCalls(ChatTurn m) {
      return m.toolCalls() != null && !m.toolCalls().isEmpty();
    }

    @Override
    public boolean truncatable(ChatTurn m) {
      return m.media() == null || m.media().isEmpty();
    }

    @Override
    public ChatTurn withContent(ChatTurn m, String content) {
      // Preserve tool metadata: head-truncating a giant tool RESULT must not
      // strip its tool_call_id, or the pair breaks in the chat template.
      return new ChatTurn(m.role(), content, m.media(), m.toolCalls(), m.toolCallId(), m.name());
    }
  };

  private static <M> List<M> trimGeneric(
    List<M> messages,
    int contextTokens,
    int maxTokens,
    TokenCounter counter,
    String toolOpenTag,
    Adapter<M> a
  ) {
    if (messages == null || messages.isEmpty() || contextTokens <= 0) {
      return messages;
    }
    // The completion reservation may never starve the prompt: a client asking for
    // max_tokens near (or above) the context size would drive the budget to ~0 and
    // shred the conversation. Cap the reservation at half the window — the engine
    // clamps the actual completion to whatever remains after the prompt anyway.
    int reservation = Math.min(Math.max(0, maxTokens), contextTokens / 2);
    long budget = Math.max(0L, (long) contextTokens - reservation - contextTokens / 20);
    String openTag = (toolOpenTag == null || toolOpenTag.isBlank())
      ? DEFAULT_TOOL_OPEN_TAG
      : toolOpenTag;

    int n = messages.size();
    int[] tok = new int[n];
    long total = 0;
    for (int i = 0; i < n; i++) {
      tok[i] = Math.max(0, counter.count(a.content(messages.get(i))));
      total += tok[i];
    }
    // Fast path: comfortably within budget — untouched, same instance.
    if (total < budget * FAST_PATH_RATIO) {
      return messages;
    }

    // Pin all leading system messages.
    int pinnedEnd = 0;
    while (pinnedEnd < n && "system".equalsIgnoreCase(a.role(messages.get(pinnedEnd)))) {
      pinnedEnd++;
    }
    if (pinnedEnd >= n) {
      return messages; // nothing but pinned systems — nothing to trim
    }
    long pinnedTokens = 0;
    for (int i = 0; i < pinnedEnd; i++) {
      pinnedTokens += tok[i];
    }
    long target = Math.max(0L, (long) (budget * TARGET_RATIO) - pinnedTokens);

    // Atomic units: an assistant message that makes tool calls — STRUCTURED
    // (OpenAI tool_calls, content typically null) or legacy tagged text —
    // plus ALL immediately following tool-result messages (parallel calls
    // yield several) trim together. Splitting the pair orphans the tool
    // message, which chat templates reject outright ("tool role without a
    // previous assistant tool call").
    int[] unitStart = new int[n];
    for (int j = pinnedEnd; j < n; ) {
      unitStart[j] = j;
      M msg = messages.get(j);
      boolean callCarrier =
        "assistant".equalsIgnoreCase(a.role(msg)) &&
        (a.hasToolCalls(msg) || a.content(msg).contains(openTag));
      int end = j;
      if (callCarrier) {
        while (end + 1 < n && "tool".equalsIgnoreCase(a.role(messages.get(end + 1)))) {
          end++;
          unitStart[end] = j;
        }
      }
      j = end + 1;
    }

    // Walk newest → oldest, keeping whole units while they fit the target.
    // The newest unit (containing the last message) is always kept.
    int keepFrom = n;
    long kept = 0;
    boolean newest = true;
    for (int u = n - 1; u >= pinnedEnd; ) {
      int start = unitStart[u];
      long utok = 0;
      for (int k = start; k <= u; k++) {
        utok += tok[k];
      }
      if (newest || kept + utok <= target) {
        keepFrom = start;
        kept += utok;
        newest = false;
        u = start - 1;
      } else {
        break; // keep the retained window contiguous — stop at the first non-fit
      }
    }

    // Safety net: the retained window must never START with tool results —
    // even if unit bookkeeping missed a shape, an orphaned tool message is a
    // guaranteed template error, while dropping it merely loses old context.
    while (keepFrom < n - 1 && "tool".equalsIgnoreCase(a.role(messages.get(keepFrom)))) {
      LOGGER.debug("Trim window opened on a tool result at index {} — advancing past it", keepFrom);
      keepFrom++;
    }

    // Oversized newest turn: truncate the last message's content from the
    // head so the tail (the most recent text) survives. Skipped for
    // non-truncatable (media-carrying) messages — those are kept whole.
    M last = messages.get(n - 1);
    M truncatedLast = null;
    if (kept > target && tok[n - 1] > target && a.truncatable(last)) {
      String content = a.content(last);
      long keepTokens = Math.max(1L, target - (kept - tok[n - 1]));
      if (keepTokens < tok[n - 1] && !content.isEmpty()) {
        // Char boundary derived from the counter's own density on this content.
        double charsPerToken = (double) content.length() / tok[n - 1];
        int keepChars = (int) Math.max(
          1L,
          Math.min(content.length() - 1L, (long) Math.floor(keepTokens * charsPerToken))
        );
        truncatedLast = a.withContent(
          last,
          TRIM_MARKER + content.substring(content.length() - keepChars)
        );
      }
    }

    if (keepFrom == pinnedEnd && truncatedLast == null) {
      return messages; // everything fits after all — untouched
    }

    List<M> out = new ArrayList<>(pinnedEnd + (n - keepFrom));
    out.addAll(messages.subList(0, pinnedEnd));
    for (int k = keepFrom; k < n - 1; k++) {
      out.add(messages.get(k));
    }
    out.add(truncatedLast != null ? truncatedLast : last);
    return List.copyOf(out);
  }
}
