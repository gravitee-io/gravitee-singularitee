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

import io.gravitee.singularitee.engine.ModelEnginePerformance;
import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.InferencePerformance;
import io.gravitee.singularitee.protocol.ResponseCompleted;
import io.gravitee.singularitee.protocol.ResponseCreated;
import io.gravitee.singularitee.protocol.ResponseEventType;
import io.gravitee.singularitee.protocol.ResponseFailed;
import io.gravitee.singularitee.protocol.ResponseOutputTextDelta;
import io.gravitee.singularitee.protocol.StepRole;
import io.gravitee.singularitee.protocol.TokenUsage;
import io.reactivex.rxjava3.core.FlowableSubscriber;
import io.vertx.core.Context;
import io.vertx.core.streams.WriteStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a {@link TextGenEngine}'s per-sequence reactive token stream
 * ({@link TextGenEngine#rxStream}) to a Vert.x {@link WriteStream} of
 * {@link InferResponse} events.
 *
 * <p>Shared by the direct {@code Infer} path (the gRPC client response stream) and the
 * pipeline path (a {@code TokenCaptureStream}). The subscriber writes CREATED / DELTA /
 * COMPLETED events on the stream's Vert.x context and applies real write-queue
 * backpressure — it pulls the next token only once the queue drains, so a slow client
 * backs up only its own bounded per-sequence buffer (never the engine's decode loop).
 * On buffer overflow or engine error it cancels the sequence and emits a terminal FAILED.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class TokenStreamWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TokenStreamWriter.class);

  private TokenStreamWriter() {}

  /** Handle for a token-stream subscription; lets the caller cancel or fail it. */
  public interface TokenStreamHandle {
    /** Cancel the stream (client disconnect): cancels native generation. */
    void cancel();

    /** Fail the stream before any token (e.g. submission error): writes FAILED and ends. */
    void fail(Throwable error);
  }

  /**
   * Subscribes {@code response} to the engine's per-sequence reactive token stream.
   * Must be called before {@code rxAddSequence} so the processor exists when the first
   * token arrives.
   */
  public static TokenStreamHandle subscribe(
    TextGenEngine tge,
    int seqId,
    WriteStream<InferResponse> response,
    Context context,
    String requestId,
    String modelId
  ) {
    var subscriber = new WriteStreamSubscriber(tge, seqId, response, context, requestId, modelId);
    tge.rxStream(seqId).subscribe(subscriber);
    return subscriber;
  }

  // Package-private (not private) so the streaming behaviour can be unit-tested directly.
  static final class WriteStreamSubscriber
    implements FlowableSubscriber<ModelEngineToken>, TokenStreamHandle {

    private final TextGenEngine tge;
    private final int seqId;
    private final WriteStream<InferResponse> response;
    private final Context context;
    private final String requestId;
    private final String modelId;
    private final AtomicBoolean createdEmitted = new AtomicBoolean(false);
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile Subscription subscription;

    WriteStreamSubscriber(
      TextGenEngine tge,
      int seqId,
      WriteStream<InferResponse> response,
      Context context,
      String requestId,
      String modelId
    ) {
      this.tge = tge;
      this.seqId = seqId;
      this.response = response;
      this.context = context;
      this.requestId = requestId;
      this.modelId = modelId;
    }

    @Override
    public void onSubscribe(Subscription s) {
      this.subscription = s;
      s.request(1);
    }

    @Override
    public void onNext(ModelEngineToken token) {
      // Hop to the stream's event loop (when there is one): a Vert.x WriteStream is not
      // safe to touch from the engine worker thread, and this is where write-queue
      // backpressure is observed. A null context (non-Vert.x caller) runs inline.
      onContext(() -> writeAndPull(token));
    }

    /** Runs {@code action} on the stream's Vert.x context, or inline if there is none. */
    private void onContext(Runnable action) {
      if (context != null) {
        context.runOnContext(v -> action.run());
      } else {
        action.run();
      }
    }

    private void writeAndPull(ModelEngineToken token) {
      if (terminated.get()) {
        return;
      }
      try {
        if (createdEmitted.compareAndSet(false, true)) {
          response.write(created(requestId, modelId));
        }
        if (token.isFinal()) {
          if (terminated.compareAndSet(false, true)) {
            response.end(completed(token));
          }
          return;
        }
        if (token.token() != null && !token.token().isEmpty()) {
          response.write(delta(token.token(), token.channel(), token.logprobs()));
        }
      } catch (Exception e) {
        LOGGER.error("Error writing token for seq {}: {}", seqId, e.getMessage());
        abort();
        return;
      }
      // Pull the next token only when the write queue has room — real backpressure
      // from the socket back to the per-sequence buffer.
      if (response.writeQueueFull()) {
        response.drainHandler(d -> subscription.request(1));
      } else {
        subscription.request(1);
      }
    }

    @Override
    public void onError(Throwable t) {
      // Buffer overflow (slow client) or engine error: stop generation and signal the
      // client with a terminal FAILED event instead of a silent hang.
      onContext(() -> {
        if (!terminated.compareAndSet(false, true)) {
          return;
        }
        LOGGER.warn(
          "Token stream for seq {} failed: {} — cancelling sequence",
          seqId,
          t.getMessage()
        );
        try {
          tge.cancelSequence(seqId);
        } catch (RuntimeException ignore) {
          // idempotent; best effort
        }
        try {
          response.end(failed("stream_overflow", t.getMessage()));
        } catch (Exception ignore) {
          // client already gone
        }
      });
    }

    @Override
    public void onComplete() {
      // The final token already ended the stream in writeAndPull; nothing to do.
    }

    @Override
    public void cancel() {
      Subscription s = subscription;
      if (s != null) {
        s.cancel(); // → rxStream doOnCancel → cancelSequence
      }
    }

    @Override
    public void fail(Throwable error) {
      onContext(() -> {
        if (!terminated.compareAndSet(false, true)) {
          return;
        }
        Subscription s = subscription;
        if (s != null) {
          s.cancel();
        }
        try {
          response.end(failed("server_error", error.getMessage()));
        } catch (Exception ignore) {
          // client already gone
        }
      });
    }

    private void abort() {
      if (!terminated.compareAndSet(false, true)) {
        return;
      }
      Subscription s = subscription;
      if (s != null) {
        s.cancel();
      }
      try {
        tge.cancelSequence(seqId);
      } catch (RuntimeException ignore) {
        // best effort
      }
    }
  }

  // ---- InferResponse builders -------------------------------------------------

  static InferResponse created(String requestId, String modelId) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_CREATED)
      .setResponseCreated(
        ResponseCreated.newBuilder()
          .setResponseId(requestId != null ? requestId : "")
          .setModel(modelId != null ? modelId : "")
          .build()
      )
      .build();
  }

  static InferResponse delta(String text) {
    return delta(text, null);
  }

  /**
   * Builds an OUTPUT_TEXT_DELTA event, stamped {@code STEP_ROLE_THINKING} when the
   * engine classified the token as {@link TokenChannel#REASONING} and
   * {@code STEP_ROLE_TOOL} for {@link TokenChannel#TOOL}. With engine-side tag
   * suppression the tool-span text is the BARE payload — the role stamp is the only
   * signal downstream tool parsing has.
   */
  static InferResponse delta(String text, TokenChannel channel) {
    return delta(text, channel, null);
  }

  static InferResponse delta(
    String text,
    TokenChannel channel,
    io.gravitee.singularitee.inference.api.textgen.PositionLogprobs logprobs
  ) {
    var deltaBuilder = ResponseOutputTextDelta.newBuilder().setDelta(text);
    if (logprobs != null) {
      deltaBuilder.addLogprobs(toProtoLogprobs(logprobs));
    }
    var builder = InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
      .setResponseOutputTextDelta(deltaBuilder.build());
    if (channel == TokenChannel.REASONING) {
      builder.setStepRole(StepRole.STEP_ROLE_THINKING);
    } else if (channel == TokenChannel.TOOL) {
      builder.setStepRole(StepRole.STEP_ROLE_TOOL);
    }
    return builder.build();
  }

  private static io.gravitee.singularitee.protocol.PositionLogprobs toProtoLogprobs(
    io.gravitee.singularitee.inference.api.textgen.PositionLogprobs logprobs
  ) {
    var builder = io.gravitee.singularitee.protocol.PositionLogprobs.newBuilder().setChosen(
      toProtoLogprob(logprobs.chosen())
    );
    for (var t : logprobs.top()) {
      builder.addTop(toProtoLogprob(t));
    }
    return builder.build();
  }

  private static io.gravitee.singularitee.protocol.TokenLogprob toProtoLogprob(
    io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry t
  ) {
    byte[] raw = new byte[t.bytes().size()];
    for (int i = 0; i < raw.length; i++) {
      raw[i] = (byte) (t.bytes().get(i) & 0xFF);
    }
    return io.gravitee.singularitee.protocol.TokenLogprob.newBuilder()
      .setToken(t.token() == null ? "" : t.token())
      .setTokenId(t.tokenId())
      .setLogprob((float) t.logprob())
      .setRawBytes(com.google.protobuf.ByteString.copyFrom(raw))
      .build();
  }

  static InferResponse completed(ModelEngineToken token) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)
      .setResponseCompleted(
        ResponseCompleted.newBuilder()
          .setUsage(
            TokenUsage.newBuilder()
              .setPromptTokens(token.promptTokens())
              .setCompletionTokens(token.completionTokens())
              .setReasoningTokens(token.reasoningTokens())
              .setToolTokens(token.toolTokens())
              .build()
          )
          .setFinishReason(toProtoFinishReason(token.finishReason()))
          .setPerformance(
            token.performance() != null
              ? toProtoPerformance(token.performance())
              : InferencePerformance.getDefaultInstance()
          )
          .build()
      )
      .build();
  }

  static InferResponse failed(String code, String message) {
    return InferResponse.newBuilder()
      .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_FAILED)
      .setResponseFailed(
        ResponseFailed.newBuilder()
          .setErrorCode(code)
          .setErrorMessage(message != null ? message : "Inference failed")
          .build()
      )
      .build();
  }

  private static FinishReason toProtoFinishReason(String reason) {
    if (reason == null) return FinishReason.FINISH_REASON_UNSPECIFIED;
    return switch (reason) {
      case "stop" -> FinishReason.FINISH_REASON_STOP;
      case "length" -> FinishReason.FINISH_REASON_LENGTH;
      case "tool_calls" -> FinishReason.FINISH_REASON_TOOL_CALLS;
      default -> FinishReason.FINISH_REASON_UNSPECIFIED;
    };
  }

  private static InferencePerformance toProtoPerformance(ModelEnginePerformance perf) {
    return InferencePerformance.newBuilder()
      .setStartTimeMs(perf.startTimeMs())
      .setLoadTimeMs(perf.loadTimeMs())
      .setPromptEvalTimeMs(perf.promptEvalTimeMs())
      .setEvalTimeMs(perf.evalTimeMs())
      .setPromptTokensEvaluated(perf.promptTokensEvaluated())
      .setTokensGenerated(perf.tokensGenerated())
      .setTokensReused(perf.tokensReused())
      .setSamplingTimeMs(perf.samplingTimeMs())
      .setSampleCount(perf.sampleCount())
      .build();
  }
}
