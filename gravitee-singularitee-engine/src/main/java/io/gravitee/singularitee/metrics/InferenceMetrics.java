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
package io.gravitee.singularitee.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed recorder for AI inference metrics.
 *
 * <p>Wraps a (possibly {@code null}) {@link MeterRegistry}. When the registry is
 * {@code null} — metrics disabled, or the engine is used outside the server (CLI,
 * tests) — every {@code record*} call is a cheap no-op. The Prometheus backend is
 * never referenced here; the container binds the live registry obtained from
 * {@code io.gravitee.node.monitoring.metrics.Metrics.getDefaultRegistry()}.
 *
 * <p>Meters (Prometheus exposition names):
 * <ul>
 *   <li>{@code ai_<op>_requests_total{model,status}} — RPC request counter (op = infer|classify|embed)</li>
 *   <li>{@code ai_<op>_latency_seconds{model}} — end-to-end RPC latency timer</li>
 *   <li>{@code ai_model_call_seconds{model,op}} — per model-engine call duration timer</li>
 *   <li>{@code ai_tokens_total{model,kind}} — token counter (kind = prompt|completion|reasoning|tool)</li>
 * </ul>
 *
 * Meter names use Micrometer's dotted convention; the Prometheus registry converts
 * {@code .}→{@code _} and appends {@code _total}/{@code _seconds} suffixes per meter type.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class InferenceMetrics {

  /** Status tag values shared across request/error reporting. */
  public static final String STATUS_SUCCESS = "success";
  public static final String STATUS_ERROR = "error";
  public static final String STATUS_NOT_FOUND = "not_found";
  /** Client disconnected mid-stream; generation was cancelled server-side. */
  public static final String STATUS_CANCELLED = "cancelled";

  private final MeterRegistry registry;

  /**
   * @param registry the live meter registry, or {@code null} to disable recording
   */
  public InferenceMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** @return {@code true} when a registry is bound and meters are recorded */
  public boolean isEnabled() {
    return registry != null;
  }

  /** Counts a top-level RPC request and its outcome — {@code ai_<op>_requests_total{model,status}}. */
  public void recordRequest(String op, String model, String status) {
    if (registry == null) return;
    Counter.builder("ai." + op + ".requests")
      .description("Number of " + op + " requests")
      .tag("model", safe(model))
      .tag("status", safe(status))
      .register(registry)
      .increment();
  }

  /** Records end-to-end RPC latency — {@code ai_<op>_latency_seconds{model}}. */
  public void recordLatency(String op, String model, long durationNanos) {
    if (registry == null) return;
    Timer.builder("ai." + op + ".latency")
      .description("End-to-end " + op + " latency")
      .tag("model", safe(model))
      .register(registry)
      .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Counts a pipeline (DAG) RPC and its outcome — {@code ai_pipeline_requests_total{pipeline,status}}.
   * Kept distinct from {@link #recordRequest} so the {@code model} dimension stays reserved for
   * actual model ids and is never conflated with pipeline ids.
   */
  public void recordPipelineRequest(String pipeline, String status) {
    if (registry == null) return;
    Counter.builder("ai.pipeline.requests")
      .description("Number of pipeline inference requests")
      .tag("pipeline", safe(pipeline))
      .tag("status", safe(status))
      .register(registry)
      .increment();
  }

  /** Records end-to-end pipeline latency — {@code ai_pipeline_latency_seconds{pipeline}}. */
  public void recordPipelineLatency(String pipeline, long durationNanos) {
    if (registry == null) return;
    Timer.builder("ai.pipeline.latency")
      .description("End-to-end pipeline inference latency")
      .tag("pipeline", safe(pipeline))
      .register(registry)
      .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  /** Records a single model-engine call duration — {@code ai_model_call_seconds{model,op}}. */
  public void recordModelCall(String op, String model, long durationNanos) {
    if (registry == null) return;
    Timer.builder("ai.model.call")
      .description("Model engine call duration")
      .tag("model", safe(model))
      .tag("op", safe(op))
      .register(registry)
      .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  /** Counts tokens by kind — {@code ai_tokens_total{model,kind}}. Non-positive counts are skipped. */
  public void recordTokens(String model, int prompt, int completion, int reasoning, int tool) {
    if (registry == null) return;
    incrementTokens(model, "prompt", prompt);
    incrementTokens(model, "completion", completion);
    incrementTokens(model, "reasoning", reasoning);
    incrementTokens(model, "tool", tool);
  }

  private void incrementTokens(String model, String kind, int count) {
    if (count <= 0) return;
    Counter.builder("ai.tokens")
      .description("Tokens processed by the inference engines")
      .baseUnit("tokens")
      .tag("model", safe(model))
      .tag("kind", kind)
      .register(registry)
      .increment(count);
  }

  /**
   * Counts an infer-step completion by finish reason — {@code ai_finish_reasons_total{model,reason}}.
   * Reasons are the context-field labels ({@code stop}, {@code length}, {@code tool_calls},
   * {@code stalled}, {@code cancelled}, …), so silent truncations become graphable.
   */
  public void recordFinishReason(String model, String reason) {
    if (registry == null) return;
    Counter.builder("ai.finish.reasons")
      .description("Infer completions by finish reason")
      .tag("model", safe(model))
      .tag("reason", safe(reason))
      .register(registry)
      .increment();
  }

  /**
   * Counts a detected failure signal — {@code ai_failure_signals_total{source,signal}}.
   * {@code source} is the nearest useful id: the model id for engine-level signals
   * ({@code tool_parse_failed}, {@code thinking_unclosed}), the step id for
   * {@code loop_max_iterations}, the pipeline id for {@code guard_blocked}.
   */
  public void recordFailureSignal(String source, String signal) {
    if (registry == null) return;
    Counter.builder("ai.failure.signals")
      .description("Detected model failure signals")
      .tag("source", safe(source))
      .tag("signal", safe(signal))
      .register(registry)
      .increment();
  }

  private static String safe(String value) {
    return (value == null || value.isBlank()) ? "unknown" : value;
  }
}
