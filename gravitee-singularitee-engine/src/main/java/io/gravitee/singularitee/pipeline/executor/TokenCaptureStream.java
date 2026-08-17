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

import io.gravitee.singularitee.protocol.*;
import io.reactivex.rxjava3.core.CompletableEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link WriteStream} that accumulates tokens into a {@link StringBuilder}
 * and optionally forwards them to a downstream client stream.
 *
 * <p>With the Responses API event model, this stream receives typed events:
 * <ul>
 *   <li>{@code RESPONSE_EVENT_TYPE_CREATED} — ignored (lifecycle managed by PipelineExecutor)</li>
 *   <li>{@code RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA} — text delta to accumulate/forward</li>
 *   <li>{@code RESPONSE_EVENT_TYPE_COMPLETED} — final event with usage (stored as lastResponse)</li>
 *   <li>{@code RESPONSE_EVENT_TYPE_FAILED} — error event</li>
 * </ul>
 *
 * <p>Reasoning handling is controlled by a {@link ThinkingMode}:
 * <ul>
 *   <li>{@link ThinkingMode#NONE} — fully transparent: accumulate and forward
 *       every delta verbatim, no tag scanning.</li>
 *   <li>{@link ThinkingMode#STRIP} — legacy {@code strip_thinking: true}
 *       behavior: the reasoning block is removed from both the accumulator
 *       and the forwarded stream.</li>
 *   <li>{@link ThinkingMode#ROUTE} — the default for INFER steps: the
 *       reasoning block's content is forwarded on a separate flux tagged
 *       {@link StepRole#STEP_ROLE_THINKING} (tag markers excluded), while the
 *       answer is forwarded with the step's wire role. The accumulator keeps
 *       the RAW text (tags included), byte-identical to {@code NONE} — ROUTE
 *       changes only the wire presentation, never the data plane, so step
 *       outputs, CoT loops and conversation context are unaffected
 *       ({@link JinjaContextHelper#stripThinking} still sanitises
 *       {@code generated_messages}).</li>
 * </ul>
 *
 * <p>In {@code STRIP} and {@code ROUTE} the stream runs a three-state machine
 * that recognises a single reasoning block at the start of the output and
 * passes real content through untouched once it has begun. The tag pair
 * defaults to {@code <think>}/{@code </think>} and can be overridden via
 * constructor parameters (sourced from the step's {@code reasoning_tags}
 * config).
 *
 * <p>Rationale for the start-anchored design: Qwen-family models (the main
 * consumers of this engine) emit reasoning blocks only at the very beginning
 * of a generation, before any real answer. Small Qwen variants (0.6B, 1.7B)
 * occasionally echo the literal string {@code <think>} inside real prose
 * (e.g. when explaining their classifier rules or the shape of a tool call).
 * If we scanned for {@code <think>} anywhere in the stream, those literal
 * mentions would be mistaken for new thinking blocks and the surrounding
 * content would be silently dropped. By only recognising a reasoning block
 * at the start of the output, mid-stream {@code <think>} is treated as
 * literal text — which is what the user actually wrote.
 *
 * <p>We still strip a stray {@code </think>} anywhere in the stream. Qwen
 * sometimes emits a single closing tag at end-of-generation (even when no
 * opening tag was emitted) because the chat-template pre-fill
 * {@code <think>\n\n</think>\n} conditions it that way. Stripping the stray
 * close is always safe — it's never legitimate content.
 *
 * <p>State machine:
 * <pre>
 *   AT_START    — haven't seen a non-whitespace, non-tag character yet.
 *                 Buffers incoming tokens while checking whether the output
 *                 begins with a thinking tag.
 *                   • sees {@code <think>}  → go to SUPPRESSING (strip tag)
 *                   • sees {@code </think>} → strip the stray close,
 *                                             go to PASSTHROUGH
 *                   • sees real content     → flush buffer as content,
 *                                             go to PASSTHROUGH
 *                   • else (whitespace or a partial tag prefix) — keep
 *                     buffering.
 *
 *   SUPPRESSING — inside a reasoning block. Tokens are silently dropped
 *                 until {@code </think>} is seen. Content after the close
 *                 tag is fed back into PASSTHROUGH (leading whitespace
 *                 stripped, because Qwen usually emits {@code </think>\n\n}).
 *
 *   PASSTHROUGH — normal output mode. Tokens are forwarded as-is, EXCEPT
 *                 that a stray {@code </think>} (which can appear at
 *                 end-of-generation on Qwen) is silently stripped. The
 *                 engine does NOT scan for {@code <think>} here — a
 *                 mid-stream {@code <think>} is treated as literal content
 *                 the model wrote.
 * </pre>
 *
 * <p>End-of-stream handling:
 * <ul>
 *   <li>If AT_START with buffered content: flush it (no tag ever materialised).</li>
 *   <li>If PASSTHROUGH with residual tail buffer: flush it.</li>
 *   <li>If SUPPRESSING (unclosed {@code <think>}): drop silently.</li>
 * </ul>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TokenCaptureStream implements WriteStream<InferResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TokenCaptureStream.class);

  /** How reasoning blocks in the generated stream are handled. */
  public enum ThinkingMode {
    /** No tag scanning — accumulate and forward everything verbatim. */
    NONE,
    /** Remove the reasoning block from both accumulator and wire. */
    STRIP,
    /**
     * Forward reasoning content on a separate {@link StepRole#STEP_ROLE_THINKING}
     * flux (tags excluded); accumulate the raw text unchanged.
     */
    ROUTE,
  }

  private static final String DEFAULT_OPEN_TAG = "<think>";
  private static final String DEFAULT_CLOSE_TAG = "</think>";

  /**
   * Small models occasionally emit a few junk characters (":", stray punctuation) before their
   * thinking block. AT_START tolerates up to this many leading characters while waiting for the
   * open tag; the junk is dropped with the tag. Beyond this budget the output is treated as real
   * content — keeping the start-anchored guarantee that a mid-stream literal {@code <think>} is
   * never mistaken for a new thinking block.
   */
  private static final int LEADING_JUNK_ALLOWANCE = 16;

  private final StringBuilder accumulator;
  private final CompletableEmitter emitter;
  private final WriteStream<InferResponse> downstream;
  private final boolean shouldForward;

  /**
   * The step's configured tool-call open markers. A model that composes its
   * tool call INSIDE the thinking channel would otherwise stream the raw call
   * syntax to the client on the thinking flux (engine-stamped thinking
   * bypasses the tag machine): once one of these markers appears in forwarded
   * thinking, the rest of the generation's thinking is machine territory and
   * stops being forwarded. Config-driven — no dialect literals here.
   */
  private java.util.List<String> thinkingCutMarkers = java.util.List.of();
  private int thinkingCutMaxLen = 0;
  private final StringBuilder thinkingHold = new StringBuilder();
  private boolean thinkingCut = false;
  /**
   * Whether THINKING-role deltas may reach the downstream even when
   * {@code shouldForward} is false (an internal step with
   * {@code stream_thinking: true}): the reasoning channel escapes, content
   * and tool deltas stay suppressed.
   */
  private final boolean shouldForwardThinking;
  private final StepRole wireRole;

  // ── Thinking handling state ─────────────────────────────────────────────
  private final ThinkingMode mode;
  private final String openTag;
  private final String closeTag;
  private final int maxTagLen;

  /**
   * Three-state machine for thinking suppression. See class javadoc for
   * the full state transitions.
   */
  private enum ThinkState {
    AT_START,
    SUPPRESSING,
    PASSTHROUGH,
  }

  private ThinkState thinkState;

  /**
   * Buffer used in {@link ThinkState#AT_START} and {@link ThinkState#PASSTHROUGH}
   * to hold the tail of incoming content while we check for tag matches
   * that might span token boundaries. Bounded to {@code maxTagLen - 1}
   * chars after each flush — any older content is safe to emit.
   */
  private final StringBuilder pendingBuffer;

  /**
   * Buffer used during {@link ThinkState#SUPPRESSING} to detect the close
   * tag across token boundaries. Grows as tokens arrive; once the close
   * tag is found the state transitions back to {@code PASSTHROUGH} and
   * the buffer is cleared.
   */
  private final StringBuilder closeBuffer;

  /**
   * When true, the next real-content flush strips its leading whitespace.
   * Set after exiting SUPPRESSING or after stripping a stray close tag,
   * to handle Qwen's {@code </think>\n\n<answer>} convention.
   */
  private boolean trimNextFlush;

  /**
   * Collects the BARE tool-call payload from deltas the engine pre-classified as
   * {@link StepRole#STEP_ROLE_TOOL} (tag markers suppressed engine-side, so this text
   * carries no {@code <tool_call>}-style wrappers). Kept separate from the main
   * accumulator — the step executor re-wraps it with the step's configured tool tags
   * to preserve template fidelity for multi-turn tool loops.
   */
  private final StringBuilder toolBuffer = new StringBuilder();

  /**
   * ANSWER-channel text only (no reasoning, no tool spans, no tag markers):
   * the model's visible words. For internal steps this is the narration a
   * client-tool halt can surface ("I'll run git status now").
   */
  private final StringBuilder answerBuffer = new StringBuilder();

  /** The last response received — carries usage and performance on the completed event. */
  private volatile InferResponse lastResponse;

  /**
   * Set at end-of-stream when the tag machine was still inside a thinking block — the model
   * opened {@code <think>} and never closed it ("stuck reasoning"). Only meaningful for
   * {@link ThinkingMode#STRIP} / {@link ThinkingMode#ROUTE}; always {@code false} otherwise.
   */
  private volatile boolean thinkingUnclosed;

  /**
   * Engine-classified reasoning was seen ({@code STEP_ROLE_THINKING} deltas — e.g. gpt-oss
   * Harmony channels, where the tag machine never runs). Together with
   * {@link #sawAnswerContent} this detects the classified-path variant of "stuck reasoning":
   * the whole generation stayed in the analysis channel and never produced an answer or a
   * tool call.
   */
  private volatile boolean sawClassifiedThinking;

  /** Non-blank answer content (or a tool span — a call is a productive outcome) was seen. */
  private volatile boolean sawAnswerContent;

  /**
   * Set when a downstream write fails (typically: the client disconnected
   * mid-stream and the gRPC response is closed). Once set, all further
   * forwarding is skipped — but accumulation and end-of-stream handling
   * continue, so the step's {@link CompletableEmitter} always completes and
   * the pipeline can never be wedged by a dead client connection.
   */
  private volatile boolean downstreamFailed;

  /**
   * Creates a capture stream that accumulates and optionally forwards tokens.
   *
   * @param accumulator    buffer that collects generated text
   * @param emitter        completed when {@code end()} is called so the reactive chain can proceed
   * @param downstream     the client response stream to forward to (may be {@code null} if not forwarding)
   * @param shouldForward  whether to forward tokens to the downstream stream
   * @param wireRole       the role tag to stamp on forwarded responses (ignored when not forwarding)
   * @param mode           how reasoning blocks are handled (see {@link ThinkingMode})
   * @param openTag        reasoning open tag (e.g. {@code <think>}); {@code null} uses the default
   * @param closeTag       reasoning close tag (e.g. {@code </think>}); {@code null} uses the default
   */
  public TokenCaptureStream(
    StringBuilder accumulator,
    CompletableEmitter emitter,
    WriteStream<InferResponse> downstream,
    boolean shouldForward,
    StepRole wireRole,
    ThinkingMode mode,
    String openTag,
    String closeTag
  ) {
    this(
      accumulator,
      emitter,
      downstream,
      shouldForward,
      shouldForward,
      wireRole,
      mode,
      openTag,
      closeTag
    );
  }

  /**
   * Full constructor: {@code shouldForwardThinking} lets an internal step
   * (content suppressed) still stream its reasoning channel live.
   */
  public TokenCaptureStream(
    StringBuilder accumulator,
    CompletableEmitter emitter,
    WriteStream<InferResponse> downstream,
    boolean shouldForward,
    boolean shouldForwardThinking,
    StepRole wireRole,
    ThinkingMode mode,
    String openTag,
    String closeTag
  ) {
    this.shouldForwardThinking = shouldForwardThinking || shouldForward;
    this.accumulator = accumulator;
    this.emitter = emitter;
    this.downstream = downstream;
    this.shouldForward = shouldForward;
    this.wireRole = wireRole;
    this.mode = mode;
    this.openTag = openTag != null && !openTag.isBlank() ? openTag : DEFAULT_OPEN_TAG;
    this.closeTag = closeTag != null && !closeTag.isBlank() ? closeTag : DEFAULT_CLOSE_TAG;
    this.maxTagLen = Math.max(this.openTag.length(), this.closeTag.length());
    this.thinkState = mode == ThinkingMode.NONE ? ThinkState.PASSTHROUGH : ThinkState.AT_START;
    this.pendingBuffer = new StringBuilder();
    this.closeBuffer = new StringBuilder();
    this.trimNextFlush = false;
  }

  /**
   * Backwards-compatible constructor taking the legacy {@code strip_thinking}
   * boolean: {@code true} maps to {@link ThinkingMode#STRIP}, {@code false}
   * to {@link ThinkingMode#NONE}.
   */
  public TokenCaptureStream(
    StringBuilder accumulator,
    CompletableEmitter emitter,
    WriteStream<InferResponse> downstream,
    boolean shouldForward,
    StepRole wireRole,
    boolean stripThinking,
    String openTag,
    String closeTag
  ) {
    this(
      accumulator,
      emitter,
      downstream,
      shouldForward,
      wireRole,
      stripThinking ? ThinkingMode.STRIP : ThinkingMode.NONE,
      openTag,
      closeTag
    );
  }

  /**
   * Backwards-compatible constructor without thinking handling.
   */
  public TokenCaptureStream(
    StringBuilder accumulator,
    CompletableEmitter emitter,
    WriteStream<InferResponse> downstream,
    boolean shouldForward,
    StepRole wireRole
  ) {
    this(accumulator, emitter, downstream, shouldForward, wireRole, ThinkingMode.NONE, null, null);
  }

  /** Convenience: capture-and-always-forward, preserving the original step role from the source. */
  public static TokenCaptureStream forwardAll(
    StringBuilder accumulator,
    CompletableEmitter emitter,
    WriteStream<InferResponse> downstream
  ) {
    return new TokenCaptureStream(
      accumulator,
      emitter,
      downstream,
      true,
      null // null = preserve original role
    );
  }

  // ---------------------------------------------------------------------------
  // WriteStream implementation
  // ---------------------------------------------------------------------------

  @Override
  public Future<Void> write(InferResponse data) {
    lastResponse = data;

    // Only process OUTPUT_TEXT_DELTA events for text accumulation.
    // CREATED events are ignored (lifecycle managed externally).
    // COMPLETED/FAILED events shouldn't arrive via write() — only via end().
    if (data.getEventType() != ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA) {
      return Future.succeededFuture();
    }

    String token = data.getResponseOutputTextDelta().getDelta();
    if (token.isEmpty()) {
      return Future.succeededFuture();
    }

    // Deltas the engine pre-classified as TOOL carry the bare tool-call payload
    // (tag markers suppressed engine-side). They bypass the tag machine, never
    // enter the main accumulator (the executor re-wraps them with the step's
    // tool tags), and are forwarded with the TOOL role preserved so the HTTP
    // layer can buffer/parse them.
    if (data.getStepRole() == StepRole.STEP_ROLE_TOOL) {
      handleToolDelta(data, token);
      return Future.succeededFuture();
    }

    // Deltas already stamped STEP_ROLE_THINKING by the writer (engine-side
    // per-token channel classification — e.g. llama.cpp with a <think>-prefilled
    // prompt, where no literal open tag ever appears in the text) BYPASS the
    // tag-scanning state machine entirely. The machine stays in place for
    // un-stamped OUTPUT deltas (engines that don't classify) and keeps the
    // stray-</think> stripping on the answer flux.
    if (data.getStepRole() == StepRole.STEP_ROLE_THINKING) {
      sawClassifiedThinking = true;
      switch (mode) {
        case ROUTE -> {
          // Data plane unchanged: the accumulator keeps the raw reasoning text,
          // consistent with the tag-based ROUTE path; the wire gets it verbatim
          // on the thinking flux.
          accumulator.append(token);
          forwardThinking(token);
        }
        case STRIP -> {
          // Drop from both accumulator and wire.
        }
        case NONE -> {
          // Fully transparent: accumulate and forward verbatim, preserving the
          // THINKING role stamped upstream (no wireRole re-stamping).
          accumulator.append(token);
          if (shouldForwardThinking && downstream != null) {
            safeDownstreamWrite(data);
          }
        }
      }
      return Future.succeededFuture();
    }

    if (mode == ThinkingMode.NONE) {
      // Fast path: no thinking handling — accumulate and forward as-is.
      if (!token.isBlank()) {
        sawAnswerContent = true;
      }
      answerBuffer.append(token);
      accumulator.append(token);
      forwardDeltaToDownstream(data);
      return Future.succeededFuture();
    }

    if (mode == ThinkingMode.ROUTE) {
      // ROUTE never touches the data plane: the accumulator gets the raw
      // text verbatim (tags included). The state machine below only decides
      // which WIRE flux (thinking vs output) each piece is forwarded on.
      accumulator.append(token);
    }

    processChunk(data, token);
    return Future.succeededFuture();
  }

  /**
   * TOOL-classified delta: accumulate the bare payload in {@link #toolBuffer} and
   * forward downstream with the TOOL role preserved (never re-stamped to the step's
   * wire role — the role IS the tool signal now that markers are suppressed).
   */
  private void handleToolDelta(InferResponse data, String token) {
    sawAnswerContent = true;
    toolBuffer.append(token);
    if (shouldForward && downstream != null) {
      safeDownstreamWrite(data);
    }
  }

  /**
   * Feeds a chunk of generated text through the state machine. Called from
   * both {@link #write} (live tokens) and recursively (content after a
   * tag match).
   */
  private void processChunk(InferResponse data, String token) {
    switch (thinkState) {
      case AT_START -> handleAtStart(data, token);
      case SUPPRESSING -> handleSuppressing(data, token);
      case PASSTHROUGH -> handlePassthrough(data, token);
    }
  }

  /**
   * AT_START: we haven't emitted any real content yet. Scan the buffer for
   * an open tag, a stray close tag, or the first non-whitespace
   * non-tag-prefix character. Whichever comes first determines the
   * transition.
   */
  private void handleAtStart(InferResponse data, String token) {
    pendingBuffer.append(token);
    String buf = pendingBuffer.toString();

    int openIdx = buf.indexOf(openTag);
    int closeIdx = buf.indexOf(closeTag);

    // A tag only counts as start-anchored when everything before it is junk (whitespace or a few
    // stray punctuation chars) — real content before a tag makes the tag literal text.
    if (openIdx > 0 && !isLeadingJunk(buf.substring(0, openIdx))) openIdx = -1;
    if (closeIdx > 0 && !isLeadingJunk(buf.substring(0, closeIdx))) closeIdx = -1;

    // Full open tag found: anything before it is leading junk;
    // drop it, enter SUPPRESSING, recurse on the remainder.
    if (openIdx >= 0 && (closeIdx < 0 || openIdx <= closeIdx)) {
      String remainder = buf.substring(openIdx + openTag.length());
      pendingBuffer.setLength(0);
      thinkState = ThinkState.SUPPRESSING;
      if (!remainder.isEmpty()) {
        handleSuppressing(data, remainder);
      }
      return;
    }

    // Full stray close tag found: drop it, go straight to PASSTHROUGH,
    // trim leading whitespace from the following content.
    if (closeIdx >= 0) {
      String remainder = buf.substring(closeIdx + closeTag.length());
      pendingBuffer.setLength(0);
      thinkState = ThinkState.PASSTHROUGH;
      trimNextFlush = true;
      if (!remainder.isEmpty()) {
        handlePassthrough(data, remainder);
      }
      return;
    }

    // No complete tag yet. Keep waiting while the buffer is junk (possibly followed by a tag
    // prefix) within the junk budget. Otherwise: real content started, flush everything we've
    // seen and go to PASSTHROUGH.
    if (isJunkThenTagPrefix(buf)) {
      return;
    }

    // Prefix diverges — no thinking block here. Flush the buffer as real
    // content and switch to PASSTHROUGH (where we still watch for stray
    // </think> but ignore new <think>).
    thinkState = ThinkState.PASSTHROUGH;
    String toFlush = buf;
    pendingBuffer.setLength(0);
    // Feed back through handlePassthrough so the tail-buffering logic
    // keeps the last N-1 chars in case a stray </think> is still coming.
    handlePassthrough(data, toFlush);
  }

  /** True when the string carries no real content (no letters or digits). */
  private static boolean isLeadingJunk(String s) {
    return s.length() <= LEADING_JUNK_ALLOWANCE && s.chars().noneMatch(Character::isLetterOrDigit);
  }

  /**
   * True while the buffer could still resolve to a start-anchored tag: a junk prefix (within the
   * allowance) optionally followed by an incomplete tag prefix. The first letter/digit outside a
   * tag prefix means real content started.
   */
  private boolean isJunkThenTagPrefix(String buf) {
    int i = buf.length();
    // Longest suffix that is a prefix of either tag.
    while (i > 0) {
      String suffix = buf.substring(buf.length() - i);
      if (
        (openTag.startsWith(suffix) && suffix.length() < openTag.length()) ||
        (closeTag.startsWith(suffix) && suffix.length() < closeTag.length())
      ) break;
      i--;
    }
    return isLeadingJunk(buf.substring(0, buf.length() - i));
  }

  /**
   * SUPPRESSING: inside the reasoning block. Scan {@link #closeBuffer} for
   * the close tag. In {@code STRIP} the content is dropped; in {@code ROUTE}
   * it is forwarded incrementally on the {@code STEP_ROLE_THINKING} flux
   * (holding back the last {@code closeTag.length() - 1} chars so a close
   * tag split across deltas is never half-forwarded). Once the close tag is
   * found, the remainder feeds back into PASSTHROUGH.
   */
  private void handleSuppressing(InferResponse data, String token) {
    closeBuffer.append(token);
    String buf = closeBuffer.toString();
    int idx = buf.indexOf(closeTag);
    if (idx < 0) {
      if (mode == ThinkingMode.ROUTE) {
        // Stream the safe prefix as reasoning so long think blocks render
        // live instead of arriving in one burst at close-tag time.
        int safeLen = Math.max(0, buf.length() - (closeTag.length() - 1));
        if (safeLen > 0) {
          forwardThinking(buf.substring(0, safeLen));
          closeBuffer.delete(0, safeLen);
        }
      }
      return; // keep waiting for the close tag
    }

    if (mode == ThinkingMode.ROUTE && idx > 0) {
      forwardThinking(buf.substring(0, idx));
    }

    String afterClose = buf.substring(idx + closeTag.length());
    closeBuffer.setLength(0);
    thinkState = ThinkState.PASSTHROUGH;
    // Qwen emits "\n\n" between </think> and the real answer. Trim it.
    trimNextFlush = true;

    if (!afterClose.isEmpty()) {
      handlePassthrough(data, afterClose);
    }
  }

  /**
   * PASSTHROUGH: forward content as real output. Still scan for stray
   * {@code </think>} (Qwen sometimes emits one at end-of-generation). Do
   * NOT scan for {@code <think>} — a mid-stream {@code <think>} is
   * treated as literal content the model wrote.
   */
  private void handlePassthrough(InferResponse data, String token) {
    pendingBuffer.append(token);
    String buf = pendingBuffer.toString();

    int closeIdx = buf.indexOf(closeTag);
    if (closeIdx >= 0) {
      // Flush content before the stray close.
      String before = buf.substring(0, closeIdx);
      String remainder = buf.substring(closeIdx + closeTag.length());
      pendingBuffer.setLength(0);
      flushRealContent(data, before);
      trimNextFlush = true;
      if (!remainder.isEmpty()) {
        handlePassthrough(data, remainder);
      }
      return;
    }

    // No close tag. Flush everything except the last maxTagLen - 1 chars
    // (which might still become part of a tag on the next write).
    int safeLen = Math.max(0, buf.length() - (maxTagLen - 1));
    if (safeLen > 0) {
      String safe = buf.substring(0, safeLen);
      flushRealContent(data, safe);
      pendingBuffer.delete(0, safeLen);
    }
  }

  /**
   * Flushes a piece of real (non-reasoning) content to the accumulator
   * (STRIP mode only — ROUTE already accumulated the raw text in
   * {@link #write}) and the downstream stream. Handles
   * {@link #trimNextFlush}: when set, strips leading whitespace from the
   * first non-empty flush after a close tag (matches Qwen's
   * {@code </think>\n\n<answer>} convention).
   */
  private void flushRealContent(InferResponse data, String text) {
    if (text.isEmpty()) return;
    if (!text.isBlank()) {
      sawAnswerContent = true;
    }
    answerBuffer.append(text);

    String out = text;
    if (trimNextFlush) {
      out = out.stripLeading();
      if (!out.isEmpty()) {
        trimNextFlush = false;
      } else {
        // Entire chunk was whitespace — keep trimming on the next one.
        return;
      }
    }

    if (mode == ThinkingMode.STRIP) {
      accumulator.append(out);
    }
    forwardSyntheticDeltaToDownstream(out, wireRole);
  }

  /**
   * Forwards reasoning content on the {@code STEP_ROLE_THINKING} flux
   * (ROUTE mode). The accumulator is untouched here — the raw text was
   * already appended in {@link #write}.
   */
  /** Installs the step's tool-open markers for thinking-flux suppression. */
  public void setThinkingCutMarkers(java.util.List<String> markers) {
    this.thinkingCutMarkers = markers == null ? java.util.List.of() : markers;
    this.thinkingCutMaxLen = this.thinkingCutMarkers.stream()
      .mapToInt(String::length)
      .max()
      .orElse(0);
  }

  private void forwardThinking(String text) {
    if (text.isEmpty() || thinkingCut) return;
    if (thinkingCutMarkers.isEmpty()) {
      forwardSyntheticDeltaToDownstream(text, StepRole.STEP_ROLE_THINKING);
      return;
    }
    // Markers can split across deltas: scan over held tail + new text, emit
    // what is provably clean, hold back any suffix that could be a marker
    // prefix. On a full marker: emit up to it, then cut for good.
    String candidate = thinkingHold.toString() + text;
    int cutAt = -1;
    for (String m : thinkingCutMarkers) {
      int i = candidate.indexOf(m);
      if (i >= 0 && (cutAt < 0 || i < cutAt)) cutAt = i;
    }
    if (cutAt >= 0) {
      thinkingCut = true;
      String clean = candidate.substring(0, cutAt);
      thinkingHold.setLength(0);
      if (!clean.isEmpty()) forwardSyntheticDeltaToDownstream(clean, StepRole.STEP_ROLE_THINKING);
      return;
    }
    int hold = 0;
    int maxHold = Math.min(thinkingCutMaxLen - 1, candidate.length());
    for (int len = maxHold; len > 0; len--) {
      String tail = candidate.substring(candidate.length() - len);
      boolean prefix = false;
      for (String m : thinkingCutMarkers) {
        if (m.startsWith(tail)) {
          prefix = true;
          break;
        }
      }
      if (prefix) {
        hold = len;
        break;
      }
    }
    String emit = candidate.substring(0, candidate.length() - hold);
    thinkingHold.setLength(0);
    thinkingHold.append(candidate, candidate.length() - hold, candidate.length());
    if (!emit.isEmpty()) forwardSyntheticDeltaToDownstream(emit, StepRole.STEP_ROLE_THINKING);
  }

  /** Flushes held thinking that never completed a marker (end of generation). */
  private void flushThinkingHold() {
    if (!thinkingCut && thinkingHold.length() > 0) {
      forwardSyntheticDeltaToDownstream(thinkingHold.toString(), StepRole.STEP_ROLE_THINKING);
    }
    thinkingHold.setLength(0);
  }

  /**
   * Forward the original delta {@link InferResponse} to the downstream client stream
   * (if forwarding is enabled), stamping the wireRole.
   */
  private void forwardDeltaToDownstream(InferResponse data) {
    if (shouldForward && downstream != null && data != null) {
      if (wireRole != null) {
        safeDownstreamWrite(data.toBuilder().setStepRole(wireRole).build());
      } else {
        safeDownstreamWrite(data);
      }
    }
  }

  /**
   * Writes to the downstream stream, downgrading any failure (e.g. the
   * client closed the connection mid-stream) to a one-time warning. The
   * write path must never throw back into the engine's token dispatch —
   * an unhandled exception there orphans this capture stream and leaves
   * the step's emitter permanently incomplete, hanging the pipeline.
   */
  private void safeDownstreamWrite(InferResponse data) {
    if (downstreamFailed) return;
    try {
      downstream.write(data);
    } catch (RuntimeException e) {
      downstreamFailed = true;
      LOGGER.warn(
        "Downstream stream write failed (client disconnected?) — " +
          "forwarding disabled for the rest of this step, generation continues: {}",
        e.getMessage()
      );
    }
  }

  /**
   * Forward synthetic content (e.g. flushed buffer from thinking handling)
   * to the downstream stream. Builds a new OUTPUT_TEXT_DELTA event stamped
   * with the given role.
   *
   * @param text the text to forward
   * @param role the step role to stamp; {@code null} leaves the role unset
   */
  private void forwardSyntheticDeltaToDownstream(String text, StepRole role) {
    boolean allowed = role == StepRole.STEP_ROLE_THINKING ? shouldForwardThinking : shouldForward;
    if (!allowed || downstream == null || text == null || text.isEmpty()) {
      return;
    }
    var builder = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
      .setResponseOutputTextDelta(ResponseOutputTextDelta.newBuilder().setDelta(text).build());
    if (role != null) {
      builder.setStepRole(role);
    }
    safeDownstreamWrite(builder.build());
  }

  // ---------------------------------------------------------------------------
  // End handling
  // ---------------------------------------------------------------------------

  @Override
  public Future<Void> end() {
    flushThinkingHold();
    flushOnEnd();
    emitter.onComplete();
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> end(InferResponse data) {
    if (data != null) {
      lastResponse = data;

      // If the final message is a COMPLETED event that also carries a last delta
      // (legacy pattern from dispatchToken), extract and process it.
      if (data.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA) {
        String token = data.getResponseOutputTextDelta().getDelta();
        if (!token.isEmpty() && data.getStepRole() == StepRole.STEP_ROLE_TOOL) {
          // Same TOOL bypass as write().
          handleToolDelta(data, token);
        } else if (!token.isEmpty() && data.getStepRole() == StepRole.STEP_ROLE_THINKING) {
          // Same channel bypass as write(): pre-classified reasoning never
          // enters the tag machine.
          sawClassifiedThinking = true;
          if (mode == ThinkingMode.ROUTE) {
            accumulator.append(token);
            forwardThinking(token);
          } else if (mode == ThinkingMode.NONE) {
            accumulator.append(token);
            if (shouldForward && downstream != null) {
              safeDownstreamWrite(data);
            }
          } // STRIP: drop
        } else if (!token.isEmpty()) {
          if (mode == ThinkingMode.NONE) {
            if (!token.isBlank()) {
              sawAnswerContent = true;
            }
            accumulator.append(token);
            if (shouldForward && downstream != null) {
              var builder = data.toBuilder();
              if (wireRole != null) {
                builder.setStepRole(wireRole);
              }
              safeDownstreamWrite(builder.build());
            }
          } else {
            if (mode == ThinkingMode.ROUTE) {
              accumulator.append(token); // data plane stays verbatim
            }
            processChunk(data, token);
          }
        }
      }
      // COMPLETED events are just stored as lastResponse (for usage extraction).
    }
    flushOnEnd();
    emitter.onComplete();
    return Future.succeededFuture();
  }

  /**
   * Called at stream end. Flushes whatever is still buffered so partial
   * content doesn't get lost.
   * <ul>
   *   <li>{@code AT_START} with a non-empty buffer: no tag ever
   *       materialised — treat the buffer as real content.</li>
   *   <li>{@code PASSTHROUGH} with a non-empty tail buffer: flush it.</li>
   *   <li>{@code SUPPRESSING}: unclosed {@code <think>}, drop silently
   *       (per the strip_thinking contract).</li>
   * </ul>
   */
  private void flushOnEnd() {
    if (mode == ThinkingMode.NONE) return;
    if (thinkState == ThinkState.AT_START && !pendingBuffer.isEmpty()) {
      // No tag ever appeared — all buffered content is real (minus a
      // truncated trailing tag, if any).
      String tail = stripTrailingTagPrefix(pendingBuffer.toString());
      pendingBuffer.setLength(0);
      thinkState = ThinkState.PASSTHROUGH;
      flushRealContent(null, tail);
    } else if (thinkState == ThinkState.PASSTHROUGH && !pendingBuffer.isEmpty()) {
      String tail = stripTrailingTagPrefix(pendingBuffer.toString());
      pendingBuffer.setLength(0);
      flushRealContent(null, tail);
    } else if (thinkState == ThinkState.SUPPRESSING) {
      thinkingUnclosed = true;
    }
    if (thinkState == ThinkState.SUPPRESSING && !closeBuffer.isEmpty()) {
      // Unclosed reasoning block at end-of-stream. STRIP drops it (per the
      // strip_thinking contract); ROUTE forwards the residual on the
      // thinking flux, minus a truncated trailing tag if max_tokens cut
      // the close tag mid-way.
      if (mode == ThinkingMode.ROUTE) {
        forwardThinking(stripTrailingTagPrefix(closeBuffer.toString()));
      }
      closeBuffer.setLength(0);
    }
  }

  /**
   * Strips from the end of the stream's final residual the longest non-empty
   * suffix that is a proper prefix of the close (or open) tag. At
   * end-of-stream such a suffix can only be a truncated reasoning tag —
   * e.g. Qwen3 emitting {@code </thin} when {@code max_tokens} cuts the
   * close tag mid-way (observed in the field on French prompts, where small
   * Qwen models re-echo think tags as plain text). Under
   * {@code strip_thinking} a partial reasoning tag is never legitimate
   * content, so dropping it is always safe. Only applied at stream end;
   * mid-stream content is never touched by this rule.
   */
  private String stripTrailingTagPrefix(String tail) {
    int maxCheck = Math.min(tail.length(), maxTagLen - 1);
    // Longest suffix first, so "</thin" strips entirely rather than just "<".
    for (int len = maxCheck; len >= 1; len--) {
      String suffix = tail.substring(tail.length() - len);
      if (
        (closeTag.length() > len && closeTag.startsWith(suffix)) ||
        (openTag.length() > len && openTag.startsWith(suffix))
      ) {
        return tail.substring(0, tail.length() - len);
      }
    }
    return tail;
  }

  // ---------------------------------------------------------------------------
  // WriteStream no-ops
  // ---------------------------------------------------------------------------

  @Override
  public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public WriteStream<InferResponse> setWriteQueueMaxSize(int size) {
    if (downstream != null) {
      downstream.setWriteQueueMaxSize(size);
    }
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    // Propagate the real client's backpressure so the per-sequence reactive subscriber
    // upstream stops pulling when the client cannot keep up. A capture that does not
    // forward (e.g. internal guard steps) is never full — nothing is written to a client.
    return shouldForward && downstream != null && downstream.writeQueueFull();
  }

  @Override
  public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
    if (downstream != null) {
      downstream.drainHandler(handler);
    }
    return this;
  }

  // ---------------------------------------------------------------------------
  // Accessor
  // ---------------------------------------------------------------------------

  /**
   * Returns the last {@link InferResponse} received, which typically is the
   * {@code RESPONSE_EVENT_TYPE_COMPLETED} event carrying
   * {@link TokenUsage} and {@link InferencePerformance}.
   */
  public InferResponse lastResponse() {
    return lastResponse;
  }

  /**
   * The bare tool-call payload accumulated from {@code STEP_ROLE_TOOL} deltas
   * (empty when the engine emitted no classified tool span — e.g. legacy engines
   * that still emit literal tag markers in the text).
   */
  /** The answer-channel text alone — reasoning, tool spans and markers excluded. */
  public String answerOutput() {
    return answerBuffer.toString();
  }

  public String toolOutput() {
    return toolBuffer.toString();
  }

  /**
   * Whether the generation ended "stuck reasoning", on either path: the tag machine was
   * still inside an unclosed {@code <think>} block, or engine-classified reasoning
   * ({@code STEP_ROLE_THINKING} — e.g. Harmony analysis channels) was seen while no answer
   * content and no tool span ever materialised. A reliable signal for repair loops and
   * fallbacks.
   */
  public boolean thinkingUnclosed() {
    return thinkingUnclosed || (sawClassifiedThinking && !sawAnswerContent);
  }
}
