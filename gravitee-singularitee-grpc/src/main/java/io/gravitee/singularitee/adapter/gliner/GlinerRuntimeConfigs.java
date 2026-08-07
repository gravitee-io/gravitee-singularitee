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
package io.gravitee.singularitee.adapter.gliner;

import io.gravitee.lab.gliner4j.runtime.ExecutionProvider;
import io.gravitee.lab.gliner4j.runtime.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Environment-driven ONNX threading knobs for the GLiNER runtimes (mirrors the
 * {@code GRAVITEE_GLINER_BATCH_*} convention used by the micro-batcher):
 *
 * <ul>
 *   <li>{@code GRAVITEE_GLINER_ENCODER_INTRA_OP_THREADS} — intra-op threads for the encoder and
 *       span_rep sessions (gliner4j default: all cores).</li>
 *   <li>{@code GRAVITEE_GLINER_ENCODER_INTER_OP_THREADS} — inter-op threads for the encoder and
 *       span_rep sessions (gliner4j default: cores/2, min 2).</li>
 *   <li>{@code GRAVITEE_GLINER_SCORING_INTRA_OP_THREADS} — intra-op threads for the
 *       scoring/classifier head sessions (gliner4j default: cores/4, min 2).</li>
 *   <li>{@code GRAVITEE_GLINER_SCORING_INTER_OP_THREADS} — inter-op threads for the
 *       scoring/classifier head sessions (gliner4j default: 1).</li>
 *   <li>{@code GRAVITEE_GLINER_ORT_PROFILING_DIR} — when set, every ONNX session writes a
 *       per-node Chrome-trace profiling JSON into this directory on session close (view in
 *       Perfetto). Diagnostic only; leave unset in normal operation.</li>
 *   <li>{@code GRAVITEE_GLINER_ORT_PROFILING_SECONDS} — flush the profiling traces this many
 *       seconds after model load instead of waiting for session close, so they can be pulled
 *       from a running instance (ephemeral filesystems don't survive shutdown).</li>
 *   <li>{@code GRAVITEE_GLINER_EXECUTION_PROVIDER} — overrides the execution provider
 *       ({@code cuda}, {@code cpu}, ...); unset keeps the factory default (auto-detect).</li>
 * </ul>
 *
 * <p>The gliner4j defaults are sized for CPU-only inference; on GPU deployments the CPU pools
 * only serve fallback ops and marshalling, so capping them (e.g. 2/1/2/1 on a 6-vCPU box)
 * avoids thread oversubscription across the encoder, span_rep, and scoring sessions.
 */
final class GlinerRuntimeConfigs {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlinerRuntimeConfigs.class);

  private GlinerRuntimeConfigs() {}

  /** Applies the env-var threading knobs to {@code builder}; unset vars keep gliner4j defaults. */
  static RuntimeConfig.RuntimeConfigBuilder applyEnvThreads(
    RuntimeConfig.RuntimeConfigBuilder builder
  ) {
    Integer encoderIntra = readPositiveInt("GRAVITEE_GLINER_ENCODER_INTRA_OP_THREADS");
    Integer encoderInter = readPositiveInt("GRAVITEE_GLINER_ENCODER_INTER_OP_THREADS");
    Integer scoringIntra = readPositiveInt("GRAVITEE_GLINER_SCORING_INTRA_OP_THREADS");
    Integer scoringInter = readPositiveInt("GRAVITEE_GLINER_SCORING_INTER_OP_THREADS");
    if (
      encoderIntra != null || encoderInter != null || scoringIntra != null || scoringInter != null
    ) {
      LOGGER.info(
        "GLiNER ONNX threading from env: encoder intra={} inter={}, scoring intra={} inter={} (null = gliner4j default)",
        encoderIntra,
        encoderInter,
        scoringIntra,
        scoringInter
      );
    }
    if (encoderIntra != null) builder.encoderIntraOpThreads(encoderIntra);
    if (encoderInter != null) builder.encoderInterOpThreads(encoderInter);
    if (scoringIntra != null) builder.scoringIntraOpThreads(scoringIntra);
    if (scoringInter != null) builder.scoringInterOpThreads(scoringInter);

    String profilingDir = System.getenv("GRAVITEE_GLINER_ORT_PROFILING_DIR");
    if (profilingDir != null && !profilingDir.isBlank()) {
      LOGGER.warn(
        "GLiNER ONNX per-node profiling enabled (GRAVITEE_GLINER_ORT_PROFILING_DIR={}) — diagnostic mode, do not leave on in production",
        profilingDir.trim()
      );
      builder.profilingDir(profilingDir.trim());
      Integer profilingSeconds = readPositiveInt("GRAVITEE_GLINER_ORT_PROFILING_SECONDS");
      if (profilingSeconds != null) {
        builder.profilingSeconds(profilingSeconds);
      }
    }

    String provider = System.getenv("GRAVITEE_GLINER_EXECUTION_PROVIDER");
    if (provider != null && !provider.isBlank()) {
      ExecutionProvider resolved = ExecutionProvider.fromString(provider);
      LOGGER.info(
        "GLiNER execution provider from env: {} (GRAVITEE_GLINER_EXECUTION_PROVIDER={})",
        resolved,
        provider.trim()
      );
      builder.executionProvider(resolved);
    }

    // GRAVITEE_GLINER_ALLOW_SPINNING=0/false stops ORT's intra/inter-op pools busy-waiting —
    // drops CPU from a pinned 100% to near the real work when the compute is GPU-bound (CUDA).
    String spinning = System.getenv("GRAVITEE_GLINER_ALLOW_SPINNING");
    if (spinning != null && !spinning.isBlank()) {
      boolean allow = !spinning.trim().equals("0") && !spinning.trim().equalsIgnoreCase("false");
      LOGGER.info(
        "GLiNER ORT intra-op spinning {} (GRAVITEE_GLINER_ALLOW_SPINNING={})",
        allow ? "enabled" : "disabled",
        spinning.trim()
      );
      builder.intraOpSpinning(allow);
    }

    return builder;
  }

  private static Integer readPositiveInt(String env) {
    String raw = System.getenv(env);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      if (value < 1) {
        LOGGER.warn("{}={} is below minimum 1; ignoring (gliner4j default applies)", env, value);
        return null;
      }
      return value;
    } catch (NumberFormatException e) {
      LOGGER.warn("{}={} is not a valid number; ignoring (gliner4j default applies)", env, raw);
      return null;
    }
  }
}
