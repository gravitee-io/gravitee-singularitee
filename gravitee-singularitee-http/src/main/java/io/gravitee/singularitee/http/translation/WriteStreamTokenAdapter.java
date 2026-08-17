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
package io.gravitee.singularitee.http.translation;

import io.gravitee.singularitee.protocol.InferResponse;
import io.gravitee.singularitee.protocol.InferencePerformance;
import io.gravitee.singularitee.protocol.ResponseCompleted;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.WriteStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a Vert.x {@link WriteStream}{@code <InferResponse>} (the type
 * {@code GraviteeInferenceServiceImpl.infer/inferPipeline} write to) into a reactive
 * {@code Flowable<TokenMessage>} consumed by {@link InferenceResponseFormatter}.
 *
 * <p>Each {@link InferResponse} carries a Responses-API event:
 * {@code CREATED} is skipped, {@code OUTPUT_TEXT_DELTA} becomes a content (or, when
 * {@code step_role == THINKING}, a reasoning) {@link TokenMessage}, and {@code COMPLETED}/
 * {@code FAILED} become a final {@link TokenMessage} carrying usage / finish reason / guard info.
 */
public final class WriteStreamTokenAdapter implements WriteStream<InferResponse> {

  private static final Logger log = LoggerFactory.getLogger(WriteStreamTokenAdapter.class);

  private final UnicastProcessor<TokenMessage> subject = UnicastProcessor.create();
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private Handler<Throwable> exceptionHandler;
  private Handler<Void> drainHandler;

  /** The token stream the response formatter subscribes to. */
  public Flowable<TokenMessage> asFlowable() {
    return subject;
  }

  // ── WriteStream<InferResponse> ──

  @Override
  public WriteStream<InferResponse> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public Future<Void> write(InferResponse response) {
    try {
      var tokenMessage = toTokenMessage(response);
      if (tokenMessage != null) {
        subject.onNext(tokenMessage);
      }
    } catch (Exception e) {
      log.error("Error writing InferResponse to token stream", e);
      if (exceptionHandler != null) {
        exceptionHandler.handle(e);
      } else {
        subject.onError(e);
      }
    }
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> end() {
    if (terminated.compareAndSet(false, true)) {
      subject.onComplete();
    }
    return Future.succeededFuture();
  }

  @Override
  public Future<Void> end(InferResponse lastResponse) {
    try {
      var tokenMessage = toTokenMessage(lastResponse);
      if (tokenMessage != null) {
        subject.onNext(tokenMessage);
      }
    } catch (Exception e) {
      log.error("Error writing final InferResponse to token stream", e);
    }
    if (terminated.compareAndSet(false, true)) {
      subject.onComplete();
    }
    return Future.succeededFuture();
  }

  @Override
  public WriteStream<InferResponse> setWriteQueueMaxSize(int maxSize) {
    // No queue throttling needed — UnicastProcessor handles backpressure downstream.
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return false;
  }

  @Override
  public WriteStream<InferResponse> drainHandler(Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

  /** Propagates an error into the token stream (e.g. pipeline execution failure). */
  public void fail(Throwable cause) {
    if (terminated.compareAndSet(false, true)) {
      subject.onError(cause);
    }
  }

  /**
   * Invoked when the downstream HTTP client disconnects. Fires the engine's termination hook
   * (registered via {@link #exceptionHandler(Handler)}) so the running sequence / pipeline is
   * cancelled, then completes the token stream. One-shot.
   */
  public void onClientDisconnect() {
    if (exceptionHandler != null) {
      exceptionHandler.handle(new IOException("client disconnected"));
    }
    if (terminated.compareAndSet(false, true)) {
      subject.onComplete();
    }
  }

  // ── Proto → TokenMessage conversion ──

  private static TokenMessage toTokenMessage(InferResponse response) {
    return switch (response.getEventType()) {
      case RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA -> buildOutputTextDelta(response);
      case RESPONSE_EVENT_TYPE_COMPLETED -> buildCompleted(response);
      case RESPONSE_EVENT_TYPE_FAILED -> buildFailed(response);
      case RESPONSE_EVENT_TYPE_PROGRESS -> TokenMessage.progressUpdate(
        response.getResponseProgress()
      );
      default -> null;
    };
  }

  private static TokenMessage buildOutputTextDelta(InferResponse response) {
    String delta = response.getResponseOutputTextDelta().getDelta();
    if (delta.isEmpty()) {
      return null;
    }
    // THINKING deltas surface as OpenAI reasoning_content; TOOL deltas carry the bare
    // tool-call payload (tag markers suppressed engine-side) and are buffered/parsed by
    // the formatter; everything else is content.
    if (response.getStepRole() == StepRole.STEP_ROLE_THINKING) {
      return new TokenMessage(null, delta, 0, false, null, 0, 0, null, null, null, null);
    }
    if (response.getStepRole() == StepRole.STEP_ROLE_TOOL) {
      return TokenMessage.toolDelta(delta);
    }
    // Per-token logprobs ride only on content deltas — OpenAI logprobs cover
    // the answer, not reasoning or tool spans.
    var logprobs = response.getResponseOutputTextDelta().getLogprobsList();
    return new TokenMessage(
      delta,
      null,
      null,
      0,
      false,
      null,
      0,
      0,
      null,
      null,
      null,
      null,
      null,
      logprobs.isEmpty() ? null : logprobs
    );
  }

  private static TokenMessage buildFailed(InferResponse response) {
    var failed = response.getResponseFailed();
    String finishReason = "content_filter".equals(failed.getErrorCode())
      ? "content_filter"
      : "stop";
    String guardMessage = !failed.getErrorMessage().isBlank() ? failed.getErrorMessage() : null;
    return new TokenMessage(null, 0, true, finishReason, 0, 0, null, null, null, guardMessage);
  }

  private static TokenMessage buildCompleted(InferResponse response) {
    var completed = response.getResponseCompleted();
    var usage = completed.getUsage();
    var performance = getPerformanceMessage(completed);
    Integer reasoningTokens = usage.getReasoningTokens() > 0 ? usage.getReasoningTokens() : null;
    Integer toolTokens = usage.getToolTokens() > 0 ? usage.getToolTokens() : null;
    String finishReason = switch (completed.getFinishReason()) {
      case FINISH_REASON_LENGTH -> "length";
      case FINISH_REASON_TOOL_CALLS -> "tool_calls";
      case FINISH_REASON_GUARD_BLOCKED -> "content_filter";
      default -> "stop";
    };
    // Structured tool calls extracted engine-side (Jinja extraction template) ride on the
    // COMPLETED event; surface them so the formatter can skip client-side re-extraction.
    java.util.List<WireToolCall> wireToolCalls = completed.getToolCallsList().isEmpty()
      ? null
      : completed
        .getToolCallsList()
        .stream()
        .map(tc ->
          new WireToolCall(
            tc.getId(),
            tc.getName(),
            tc.getArgumentsJson(),
            tc.getCoercibleArgsList()
          )
        )
        .toList();
    return new TokenMessage(
      null,
      null,
      null,
      0,
      true,
      finishReason,
      usage.getPromptTokens(),
      usage.getCompletionTokens(),
      reasoningTokens,
      toolTokens,
      performance,
      null,
      wireToolCalls
    );
  }

  private static PerformanceMessage getPerformanceMessage(ResponseCompleted completed) {
    if (!completed.hasPerformance()) {
      return null;
    }
    var perf = completed.getPerformance();
    double promptEvalMs = perf.getPromptEvalTimeMs();
    double evalMs = perf.getEvalTimeMs();
    int promptTokens = perf.getPromptTokensEvaluated();
    return new PerformanceMessage(
      perf.getStartTimeMs(),
      perf.getLoadTimeMs(),
      promptEvalMs,
      perf.getEvalTimeMs(),
      promptTokens,
      perf.getTokensGenerated(),
      perf.getTokensReused(),
      perf.getSamplingTimeMs(),
      perf.getSampleCount(),
      getPromptTps(promptEvalMs, promptTokens),
      getGenTps(evalMs, perf.getTokensGenerated()),
      promptEvalMs + evalMs,
      getAverageSamplingTimeMs(perf)
    );
  }

  private static double getAverageSamplingTimeMs(InferencePerformance perf) {
    return perf.getSampleCount() > 0
      ? (double) perf.getSamplingTimeMs() / perf.getSampleCount()
      : 0.0;
  }

  private static double getPromptTps(double promptEvalMs, int promptTokens) {
    return (promptEvalMs > 0 && promptTokens > 0) ? (promptTokens / promptEvalMs) * 1000.0 : 0.0;
  }

  private static double getGenTps(double evalMs, int genTokens) {
    return (evalMs > 0 && genTokens > 0) ? (genTokens / evalMs) * 1000.0 : 0.0;
  }
}
