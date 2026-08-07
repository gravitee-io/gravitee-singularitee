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

import io.gravitee.singularitee.protocol.*;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TokenCaptureStreamTest {

  // ── Helpers ─────────────────────────────────────────────────────────────

  private static final class CapturingDownstream implements WriteStream<InferResponse> {

    final List<String> received = new ArrayList<>();
    final StringBuilder thinkingFlux = new StringBuilder();
    final StringBuilder toolFlux = new StringBuilder();
    final StringBuilder outputFlux = new StringBuilder();

    @Override
    public Future<Void> write(InferResponse data) {
      if (data.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA) {
        String delta = data.getResponseOutputTextDelta().getDelta();
        received.add(delta);
        if (data.getStepRole() == StepRole.STEP_ROLE_THINKING) {
          thinkingFlux.append(delta);
        } else if (data.getStepRole() == StepRole.STEP_ROLE_TOOL) {
          toolFlux.append(delta);
        } else {
          outputFlux.append(delta);
        }
      }
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(InferResponse data) {
      if (
        data != null &&
        data.getEventType() == ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA
      ) {
        received.add(data.getResponseOutputTextDelta().getDelta());
      }
      return Future.succeededFuture();
    }

    @Override
    public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
      return this;
    }

    @Override
    public WriteStream<InferResponse> setWriteQueueMaxSize(int i) {
      return this;
    }

    @Override
    public boolean writeQueueFull() {
      return false;
    }

    @Override
    public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
      return this;
    }
  }

  private static final class Result {

    final String accumulator;
    final String forwarded;
    final String thinkingFlux;
    final String outputFlux;

    Result(String accumulator, String forwarded) {
      this(accumulator, forwarded, "", "");
    }

    Result(String accumulator, String forwarded, String thinkingFlux, String outputFlux) {
      this.accumulator = accumulator;
      this.forwarded = forwarded;
      this.thinkingFlux = thinkingFlux;
      this.outputFlux = outputFlux;
    }
  }

  /**
   * Feeds the given tokens through a TokenCaptureStream with strip_thinking enabled.
   * Returns both the accumulator content (what would be stored in the pipeline
   * context) and the concatenated forwarded tokens (what the client streams see).
   */
  private static Result runStripping(String... tokens) {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var emitterHolder = new CompletableEmitter[1];
    var stream = new TokenCaptureStream[1];

    var completable = Completable.create(emitter -> {
      emitterHolder[0] = emitter;
      stream[0] = new TokenCaptureStream(
        accumulator,
        emitter,
        downstream,
        true,
        StepRole.STEP_ROLE_OUTPUT,
        true,
        "<think>",
        "</think>"
      );
    });

    // Subscribe so the emitter materialises.
    AtomicBoolean done = new AtomicBoolean(false);
    completable.subscribe(() -> done.set(true), Throwable::printStackTrace);

    // Feed all but the last token via write(), and the final one via end(data).
    if (tokens.length == 0) {
      stream[0].end();
    } else {
      for (int i = 0; i < tokens.length - 1; i++) {
        stream[0].write(deltaEvent(tokens[i]));
      }
      stream[0].end(deltaEvent(tokens[tokens.length - 1]));
    }

    String forwarded = String.join("", downstream.received);
    return new Result(accumulator.toString(), forwarded);
  }

  /**
   * Feeds the given tokens through a TokenCaptureStream in ROUTE mode.
   * Returns the accumulator (raw, data plane) plus the two wire fluxes:
   * thinking (STEP_ROLE_THINKING deltas) and output (everything else).
   */
  private static Result runRouting(String... tokens) {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = new TokenCaptureStream[1];

    var completable = Completable.create(emitter ->
      stream[0] = new TokenCaptureStream(
        accumulator,
        emitter,
        downstream,
        true,
        StepRole.STEP_ROLE_OUTPUT,
        TokenCaptureStream.ThinkingMode.ROUTE,
        "<think>",
        "</think>"
      )
    );

    AtomicBoolean done = new AtomicBoolean(false);
    completable.subscribe(() -> done.set(true), Throwable::printStackTrace);

    if (tokens.length == 0) {
      stream[0].end();
    } else {
      for (int i = 0; i < tokens.length - 1; i++) {
        stream[0].write(deltaEvent(tokens[i]));
      }
      stream[0].end(deltaEvent(tokens[tokens.length - 1]));
    }

    return new Result(
      accumulator.toString(),
      String.join("", downstream.received),
      downstream.thinkingFlux.toString(),
      downstream.outputFlux.toString()
    );
  }

  /** Builds a ResponseOutputTextDelta InferResponse event. */
  private static InferResponse deltaEvent(String text) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
      .setResponseOutputTextDelta(ResponseOutputTextDelta.newBuilder().setDelta(text).build())
      .build();
  }

  // ── Tests ───────────────────────────────────────────────────────────────

  @Test
  void passthrough_no_tags() {
    // When no tag is present, output must be preserved exactly.
    var r = runStripping("Hello ", "world", "!");
    assertThat(r.accumulator).isEqualTo("Hello world!");
    assertThat(r.forwarded).isEqualTo("Hello world!");
  }

  @Test
  void short_output_no_tags_flushes_tail_at_end() {
    // Output shorter than maxTagLen still flushes completely at end-of-stream.
    // This is the critical bug: early versions held back the last 7 chars
    // waiting to match a tag, and the tail was never flushed.
    var r = runStripping("untos");
    assertThat(r.accumulator).isEqualTo("untos");
    assertThat(r.forwarded).isEqualTo("untos");
  }

  @Test
  void single_char_tokens_flush_at_end() {
    // Character-by-character streaming also must flush the tail.
    var r = runStripping("a", "b", "c");
    assertThat(r.accumulator).isEqualTo("abc");
    assertThat(r.forwarded).isEqualTo("abc");
  }

  @Test
  void leading_junk_before_thinking_block_is_dropped() {
    // Small models sometimes emit a few junk chars (":", punctuation) before the
    // thinking block; within the leading-junk allowance the block is still recognised.
    var r = runStripping(":\n", "<think>", "hidden", "</think>", "\n\n", "Answer");
    assertThat(r.accumulator).isEqualTo("Answer");
    assertThat(r.forwarded).isEqualTo("Answer");
  }

  @Test
  void long_leading_content_disables_thinking_detection() {
    // Past the allowance the output is real content — a later literal <think> stays literal.
    var r = runStripping("This is a long normal answer mentioning ", "<think>", " literally");
    assertThat(r.forwarded).isEqualTo("This is a long normal answer mentioning <think> literally");
  }

  @Test
  void proper_thinking_block_is_stripped() {
    var r = runStripping("<think>", "reasoning", "</think>", "\n\n", "Answer");
    assertThat(r.accumulator).isEqualTo("Answer");
    assertThat(r.forwarded).isEqualTo("Answer");
  }

  @Test
  void thinking_block_split_across_tokens() {
    // Tokens split the open and close tags character by character.
    var r = runStripping("<", "think", ">", "hidden", "<", "/think", ">", "\n", "\n", "Visible");
    assertThat(r.accumulator).isEqualTo("Visible");
  }

  @Test
  void stray_close_tag_mid_stream_is_stripped() {
    // Model emits </think> without a matching <think> (Qwen failure mode).
    var r = runStripping("Hello ", "world", "</think>");
    assertThat(r.accumulator).isEqualTo("Hello world");
    assertThat(r.forwarded).isEqualTo("Hello world");
  }

  @Test
  void stray_close_tag_at_end_with_newlines() {
    // The actual Qwen3-0.6B failure mode observed in the field.
    var r = runStripping("Hello ", "world\n\n", "</think>");
    assertThat(r.accumulator).isEqualTo("Hello world\n\n");
  }

  @Test
  void text_after_stray_close_still_flows() {
    // Content after a stray </think> continues to flow (minus the leading
    // whitespace that typically follows it).
    var r = runStripping("Prefix", "</think>", "\n\nSuffix");
    assertThat(r.accumulator).isEqualTo("PrefixSuffix");
  }

  @Test
  void multiple_thinking_blocks_only_first_is_stripped() {
    // New semantics: only the first thinking block (at the start) is
    // stripped. A second <think> mid-stream is treated as literal text
    // because Qwen small models sometimes echo the tag in their answers.
    // The trailing </think> is stripped as a stray close (PASSTHROUGH
    // always strips </think>), so the output contains "<think>y" with no
    // close. This is the documented trade-off — stray </think> is
    // considered noise (Qwen emits them at end-of-generation), and the
    // rare cost of eating a legitimate literal </think> in prose is
    // acceptable.
    var r = runStripping("<think>", "x", "</think>\n\n", "A", "<think>", "y", "</think>", "C");
    assertThat(r.accumulator).isEqualTo("A<think>yC");
  }

  @Test
  void mid_stream_open_tag_is_literal_text() {
    // Production bug: Qwen echoes "<think>" in prose when describing its
    // own behaviour. With the old state machine this swallowed surrounding
    // content. New semantics: mid-stream <think> is kept as literal text.
    var r = runStripping(
      "I have enough. ",
      "The word <think>",
      " is not special here. ",
      "Respond directly with the answer."
    );
    assertThat(r.accumulator).isEqualTo(
      "I have enough. The word <think> is not special here. Respond directly with the answer."
    );
  }

  @Test
  void mid_stream_literal_think_pair_is_kept() {
    // Even a full <think>...</think> pair AFTER real content has started
    // is now treated as literal text. The strip only applies to a thinking
    // block at the very start of the output.
    var r = runStripping(
      "Content before. ",
      "<think>this should be literal</think>",
      " content after."
    );
    // </think> is still stripped (stray-close handling) but <think> is not.
    // So the output contains "<think>this should be literal" with the
    // close tag removed. This isn't perfect but it's the documented
    // trade-off: stray </think> is always stripped because Qwen emits them
    // at end-of-generation without matching opens.
    assertThat(r.accumulator).isEqualTo(
      "Content before. <think>this should be literalcontent after."
    );
  }

  @Test
  void unclosed_thinking_block_at_start_is_dropped_silently() {
    // Per contract: if <think> is opened AT THE START but never closed,
    // everything inside is dropped. This is the strip_thinking safety net
    // for a truncated model output.
    var r = runStripping("<think>", "unfinished");
    assertThat(r.accumulator).isEmpty();
  }

  @Test
  void unclosed_think_mid_stream_is_literal_text() {
    // New semantics: once real content has flowed, <think> is literal.
    // So a mid-stream <think> without close is just kept as-is.
    var r = runStripping("visible ", "<think>", "unfinished");
    assertThat(r.accumulator).isEqualTo("visible <think>unfinished");
  }

  @Test
  void reply_directly_without_explanation_finalise_prose() {
    // Exact failure from the field: classifier emitted a multi-line response
    // and the first 7-ish chars were lost. With the new state machine the
    // entire content must be preserved.
    var r = runStripping("Reply directly, without explanation.\n", "finalise_prose");
    assertThat(r.accumulator).isEqualTo("Reply directly, without explanation.\nfinalise_prose");
    assertThat(r.forwarded).isEqualTo("Reply directly, without explanation.\nfinalise_prose");
  }

  @Test
  void short_output_after_thinking_block() {
    // Output "untos" after a thinking block — the short 5-char tail must flush.
    var r = runStripping("<think>", "reason", "</think>\n\n", "untos");
    assertThat(r.accumulator).isEqualTo("untos");
  }

  @Test
  void trailing_partial_tag_prefix_dropped_at_end() {
    // If the output ENDS with a truncated tag (e.g. "<thi" with nothing
    // after), it can only be a reasoning tag cut off by max_tokens — under
    // strip_thinking it is never legitimate content and must be dropped.
    // (Field case: Qwen3-0.6B on French re-echoes think tags as plain text
    // and max_tokens truncates the close tag to "</thin".)
    var r = runStripping("Content<thi");
    assertThat(r.accumulator).isEqualTo("Content");
  }

  @Test
  void trailing_partial_close_tag_dropped_at_end() {
    // The exact field symptom: "...[ACCOUNT_ID]." followed by a truncated
    // "</thin" at end-of-stream must not leak to the client.
    var r = runStripping("Mon numéro de client est [ACCOUNT_ID].", "</thin");
    assertThat(r.accumulator).isEqualTo("Mon numéro de client est [ACCOUNT_ID].");
    assertThat(r.forwarded).isEqualTo("Mon numéro de client est [ACCOUNT_ID].");
  }

  @Test
  void trailing_almost_complete_close_tag_dropped_at_end() {
    // "</think" (everything but the final '>') at stream end is dropped.
    var r = runStripping("content", "</think");
    assertThat(r.accumulator).isEqualTo("content");
  }

  @Test
  void mid_stream_partial_tag_lookalike_still_flushes() {
    // A "<thi" that is followed by MORE non-tag content is genuine prose and
    // must be preserved — only a *terminal* tag prefix is dropped.
    var r = runStripping("a <thi", "ng happened");
    assertThat(r.accumulator).isEqualTo("a <thing happened");
  }

  @Test
  void empty_output() {
    var r = runStripping();
    assertThat(r.accumulator).isEmpty();
    assertThat(r.forwarded).isEmpty();
  }

  @Test
  void production_scenario_planner_with_thinking_then_short_tail() {
    // Actual production failure: planner emits a thinking block then a short
    // response like "Subtasks: [...]" but the accumulator ends up as just
    // "untos". Simulating what the token stream may look like.
    var r = runStripping(
      "<think>",
      "Let me decompose this task. The user wants to move a file.",
      "</think>",
      "\n\n",
      "Subtasks:",
      "\n",
      "1. Move the file to ./gol/"
    );
    assertThat(r.accumulator).isEqualTo("Subtasks:\n1. Move the file to ./gol/");
  }

  @Test
  void token_boundary_splits_close_tag_during_suppressing() {
    // When the close tag is split across multiple tokens while we're in
    // SUPPRESSING, the next real content (short) must still flush at end.
    var r = runStripping("<think>", "x", "</", "think", ">", "\n\n", "short");
    assertThat(r.accumulator).isEqualTo("short");
  }

  @Test
  void pending_buffer_fully_flushed_after_thinking_block() {
    // After exiting a thinking block, a short response shorter than maxTagLen
    // (8 chars) must flush completely at end-of-stream — this is where the
    // tail-buffer bug manifests.
    var r = runStripping("<think>", "stuff", "</think>", "\n", "untos");
    assertThat(r.accumulator).isEqualTo("untos");
  }

  @Test
  void only_tail_remaining_after_thinking_ends_on_partial_tag_prefix() {
    // After </think>, the real content starts and ends with what might look
    // like a tag prefix but isn't. Must flush everything.
    var r = runStripping("<think>", "reasoning", "</think>", "\n", "< 5 items");
    // The "<" at start of "< 5 items" must not trigger a false SUPPRESSING.
    assertThat(r.accumulator).isEqualTo("< 5 items");
  }

  @Test
  void single_char_stream_after_thinking() {
    // Mimic Qwen's actual streaming: one char per token after </think>.
    // The production failure was "Reply directly, without explanation.\nfinalise_prose"
    // becoming "cly, without explanation.\nfinalise_prose" (first ~7 chars lost).
    String text = "Reply directly, without explanation.\nfinalise_prose";
    String[] tokens = new String[text.length() + 4];
    tokens[0] = "<think>";
    tokens[1] = "stuff";
    tokens[2] = "</think>";
    tokens[3] = "\n";
    for (int i = 0; i < text.length(); i++) {
      tokens[i + 4] = String.valueOf(text.charAt(i));
    }
    var r = runStripping(tokens);
    assertThat(r.accumulator).isEqualTo(text);
  }

  @Test
  void words_as_tokens_after_thinking() {
    // Closer to real Qwen tokenization: subword tokens.
    var r = runStripping(
      "<think>",
      "stuff",
      "</think>",
      "\n\n",
      "Reply",
      " directly",
      ",",
      " without",
      " explanation",
      ".",
      "\n",
      "final",
      "ise",
      "_prose"
    );
    assertThat(r.accumulator).isEqualTo("Reply directly, without explanation.\nfinalise_prose");
  }

  // ── ROUTE mode: reasoning on a separate STEP_ROLE_THINKING flux ──────────

  @Test
  void route_think_block_to_thinking_flux() {
    var r = runRouting("<think>", "raisonnement interne", "</think>", "\n\n", "Bonjour !");
    // Wire: two separated fluxes, tag markers excluded.
    assertThat(r.thinkingFlux).isEqualTo("raisonnement interne");
    assertThat(r.outputFlux).isEqualTo("Bonjour !");
    // Data plane: raw text untouched (tags included) — step outputs and
    // CoT loops behave exactly as without any thinking handling.
    assertThat(r.accumulator).isEqualTo("<think>raisonnement interne</think>\n\nBonjour !");
  }

  @Test
  void route_tags_split_across_deltas() {
    var r = runRouting("<th", "ink>", "pense", "</th", "ink>", "réponse");
    assertThat(r.thinkingFlux).isEqualTo("pense");
    assertThat(r.outputFlux).isEqualTo("réponse");
    assertThat(r.accumulator).isEqualTo("<think>pense</think>réponse");
  }

  @Test
  void route_no_think_block_is_transparent() {
    var r = runRouting("Hello ", "world");
    assertThat(r.thinkingFlux).isEmpty();
    assertThat(r.outputFlux).isEqualTo("Hello world");
    assertThat(r.accumulator).isEqualTo("Hello world");
  }

  @Test
  void route_stray_close_tag_dropped_from_wire_kept_in_accumulator() {
    // Qwen sometimes emits a lone </think> after the empty-prefill bypass.
    // The wire output must not carry it; the raw accumulator keeps it.
    var r = runRouting("</think>", "\n\n", "answer");
    assertThat(r.thinkingFlux).isEmpty();
    assertThat(r.outputFlux).isEqualTo("answer");
    assertThat(r.accumulator).isEqualTo("</think>\n\nanswer");
  }

  @Test
  void route_unclosed_think_block_streams_as_thinking() {
    // max_tokens cut generation inside the think block: the reasoning that
    // did stream must have gone to the thinking flux, not output.
    var r = runRouting("<think>", "pense pense pense");
    assertThat(r.thinkingFlux).isEqualTo("pense pense pense");
    assertThat(r.outputFlux).isEmpty();
    assertThat(r.accumulator).isEqualTo("<think>pense pense pense");
  }

  @Test
  void route_truncated_trailing_close_tag_not_leaked() {
    // The French field case: think block whose close tag is cut to "</thin"
    // by max_tokens. The fragment must not leak into either flux.
    var r = runRouting("<think>", "pense", "</thin");
    assertThat(r.thinkingFlux).isEqualTo("pense");
    assertThat(r.outputFlux).isEmpty();
    assertThat(r.accumulator).isEqualTo("<think>pense</thin");
  }

  @Test
  void dead_downstream_never_prevents_completion() {
    // Client disconnected mid-stream: every downstream write throws. The
    // capture stream must swallow the failure, keep accumulating, and STILL
    // complete its emitter at end() — otherwise the step's Completable
    // never finishes and the server-side pipeline hangs forever.
    var accumulator = new StringBuilder();
    var deadDownstream = new WriteStream<InferResponse>() {
      @Override
      public Future<Void> write(InferResponse data) {
        throw new IllegalStateException("stream is closed");
      }

      @Override
      public Future<Void> end() {
        throw new IllegalStateException("stream is closed");
      }

      @Override
      public Future<Void> end(InferResponse data) {
        throw new IllegalStateException("stream is closed");
      }

      @Override
      public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> h) {
        return this;
      }

      @Override
      public WriteStream<InferResponse> setWriteQueueMaxSize(int i) {
        return this;
      }

      @Override
      public boolean writeQueueFull() {
        return false;
      }

      @Override
      public WriteStream<InferResponse> drainHandler(Handler<Void> h) {
        return this;
      }
    };

    var stream = new TokenCaptureStream[1];
    AtomicBoolean completed = new AtomicBoolean(false);
    Completable.create(emitter ->
      stream[0] = new TokenCaptureStream(
        accumulator,
        emitter,
        deadDownstream,
        true,
        StepRole.STEP_ROLE_OUTPUT,
        TokenCaptureStream.ThinkingMode.ROUTE,
        "<think>",
        "</think>"
      )
    ).subscribe(() -> completed.set(true), Throwable::printStackTrace);

    stream[0].write(deltaEvent("<think>"));
    stream[0].write(deltaEvent("pense"));
    stream[0].write(deltaEvent("</think>"));
    stream[0].write(deltaEvent("réponse"));
    stream[0].end(deltaEvent(" finale"));

    // The emitter completed despite the dead client, and the data plane
    // is intact for downstream pipeline steps.
    assertThat(completed).isTrue();
    assertThat(accumulator.toString()).isEqualTo("<think>pense</think>réponse finale");
  }

  @Test
  void route_long_think_block_streams_incrementally() {
    // Reasoning must reach the downstream while the block is still open —
    // not in a single burst at close-tag time.
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = new TokenCaptureStream[1];
    Completable.create(emitter ->
      stream[0] = new TokenCaptureStream(
        accumulator,
        emitter,
        downstream,
        true,
        StepRole.STEP_ROLE_OUTPUT,
        TokenCaptureStream.ThinkingMode.ROUTE,
        "<think>",
        "</think>"
      )
    ).subscribe(() -> {}, Throwable::printStackTrace);

    stream[0].write(deltaEvent("<think>"));
    stream[0].write(deltaEvent("a fairly long piece of reasoning text "));
    // Before the close tag arrives, most of the reasoning is already out
    // (only the closeTag-1 holdback may lag behind).
    assertThat(downstream.thinkingFlux.length()).isGreaterThan(20);
    stream[0].write(deltaEvent("</think>"));
    stream[0].end(deltaEvent("done"));
    assertThat(downstream.thinkingFlux.toString()).isEqualTo(
      "a fairly long piece of reasoning text "
    );
    assertThat(downstream.outputFlux.toString()).isEqualTo("done");
  }

  // ── Pre-stamped THINKING deltas (engine-side channel classification) ────

  /** Builds a delta already stamped STEP_ROLE_THINKING by the upstream writer. */
  private static InferResponse thinkingDeltaEvent(String text) {
    return deltaEvent(text).toBuilder().setStepRole(StepRole.STEP_ROLE_THINKING).build();
  }

  private static TokenCaptureStream newStream(
    StringBuilder accumulator,
    CapturingDownstream downstream,
    TokenCaptureStream.ThinkingMode mode
  ) {
    var stream = new TokenCaptureStream[1];
    Completable.create(emitter ->
      stream[0] = new TokenCaptureStream(
        accumulator,
        emitter,
        downstream,
        true,
        StepRole.STEP_ROLE_OUTPUT,
        mode,
        "<think>",
        "</think>"
      )
    ).subscribe(() -> {}, Throwable::printStackTrace);
    return stream[0];
  }

  @Test
  void route_prestamped_thinking_bypasses_tag_machine() {
    // Engine-classified reasoning arrives with STEP_ROLE_THINKING and NO
    // literal <think> tag (prefilled prompt): ROUTE must forward it on the
    // thinking flux verbatim and keep the answer on the output flux.
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(thinkingDeltaEvent("let me "));
    stream.write(thinkingDeltaEvent("reason"));
    stream.write(deltaEvent("Hello "));
    stream.end(deltaEvent("world"));

    assertThat(downstream.thinkingFlux.toString()).isEqualTo("let me reason");
    assertThat(downstream.outputFlux.toString()).isEqualTo("Hello world");
    // Data plane: raw text, reasoning included.
    assertThat(accumulator.toString()).isEqualTo("let me reasonHello world");
  }

  @Test
  void route_prestamped_thinking_with_literal_tag_text_is_not_rescanned() {
    // A stamped reasoning delta containing tag-lookalike text must be
    // forwarded verbatim on the thinking flux, never fed to the tag machine.
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(thinkingDeltaEvent("mentioning </think> literally"));
    stream.end(deltaEvent("answer"));

    assertThat(downstream.thinkingFlux.toString()).isEqualTo("mentioning </think> literally");
    assertThat(downstream.outputFlux.toString()).isEqualTo("answer");
  }

  @Test
  void strip_prestamped_thinking_is_dropped() {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.STRIP);

    stream.write(thinkingDeltaEvent("secret reasoning"));
    stream.write(deltaEvent("Hello"));
    stream.end(deltaEvent(" world"));

    assertThat(downstream.thinkingFlux.toString()).isEmpty();
    assertThat(downstream.outputFlux.toString()).isEqualTo("Hello world");
    assertThat(accumulator.toString()).isEqualTo("Hello world");
  }

  @Test
  void none_prestamped_thinking_forwarded_as_is() {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.NONE);

    stream.write(thinkingDeltaEvent("reasoning"));
    stream.write(deltaEvent("Hello"));
    stream.end();

    // NONE is fully transparent: the thinking delta keeps its role on the wire
    // and the accumulator gets everything verbatim.
    assertThat(downstream.thinkingFlux.toString()).isEqualTo("reasoning");
    assertThat(downstream.outputFlux.toString()).isEqualTo("Hello");
    assertThat(accumulator.toString()).isEqualTo("reasoningHello");
  }

  @Test
  void route_prestamped_thinking_via_end_is_routed() {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(deltaEvent("Hello"));
    stream.end(thinkingDeltaEvent("late reasoning"));

    assertThat(downstream.thinkingFlux.toString()).isEqualTo("late reasoning");
    assertThat(downstream.outputFlux.toString()).isEqualTo("Hello");
    assertThat(accumulator.toString()).isEqualTo("Hellolate reasoning");
  }

  // ── Pre-stamped TOOL deltas (engine-side tag suppression) ───────────────

  /** Builds a delta already stamped STEP_ROLE_TOOL by the upstream writer. */
  private static InferResponse toolDeltaEvent(String text) {
    return deltaEvent(text).toBuilder().setStepRole(StepRole.STEP_ROLE_TOOL).build();
  }

  @Test
  void route_prestamped_tool_bypasses_tag_machine_and_keeps_role() {
    // Bare tool payload arrives on the TOOL channel (markers suppressed): it must
    // be forwarded with the TOOL role preserved (never re-stamped to OUTPUT),
    // captured in toolOutput(), and kept OUT of the main accumulator.
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(thinkingDeltaEvent("reasoning"));
    stream.write(deltaEvent("calling now: "));
    stream.write(toolDeltaEvent("{\"name\":\"send_email\","));
    stream.write(toolDeltaEvent("\"arguments\":{}}"));
    stream.end();

    assertThat(downstream.thinkingFlux.toString()).isEqualTo("reasoning");
    assertThat(downstream.toolFlux.toString()).isEqualTo(
      "{\"name\":\"send_email\",\"arguments\":{}}"
    );
    assertThat(stream.toolOutput()).isEqualTo("{\"name\":\"send_email\",\"arguments\":{}}");
    assertThat(accumulator.toString()).isEqualTo("reasoningcalling now: ");
    assertThat(downstream.outputFlux.toString()).isEqualTo("calling now: ");
  }

  @Test
  void prestamped_tool_via_end_is_captured() {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(toolDeltaEvent("call:get_weather{city:"));
    stream.end(toolDeltaEvent("Paris}"));

    assertThat(stream.toolOutput()).isEqualTo("call:get_weather{city:Paris}");
    assertThat(downstream.toolFlux.toString()).isEqualTo("call:get_weather{city:Paris}");
    assertThat(accumulator.toString()).isEmpty();
  }

  @Test
  void none_mode_prestamped_tool_still_bypasses() {
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.NONE);

    stream.write(deltaEvent("Hello"));
    stream.write(toolDeltaEvent("{\"name\":\"f\"}"));
    stream.end();

    assertThat(downstream.toolFlux.toString()).isEqualTo("{\"name\":\"f\"}");
    assertThat(stream.toolOutput()).isEqualTo("{\"name\":\"f\"}");
    assertThat(accumulator.toString()).isEqualTo("Hello");
  }

  @Test
  void legacy_tagged_tool_text_flows_as_output_and_tool_output_is_empty() {
    // Engines that still emit literal markers: the tagged block stays plain
    // output text (parsed downstream by the HTTP layer's regexes).
    var accumulator = new StringBuilder();
    var downstream = new CapturingDownstream();
    var stream = newStream(accumulator, downstream, TokenCaptureStream.ThinkingMode.ROUTE);

    stream.write(deltaEvent("<tool_call>{\"name\":\"f\"}</tool_call>"));
    stream.end();

    assertThat(stream.toolOutput()).isEmpty();
    assertThat(accumulator.toString()).isEqualTo("<tool_call>{\"name\":\"f\"}</tool_call>");
  }

  @Test
  void route_unstamped_tag_fallback_still_works_alongside_bypass() {
    // Engines that don't classify still rely on the tag machine; the bypass
    // must not disturb it.
    var r = runRouting("<think>", "pense", "</think>", "réponse");
    assertThat(r.thinkingFlux).isEqualTo("pense");
    assertThat(r.outputFlux).isEqualTo("réponse");
  }
}
