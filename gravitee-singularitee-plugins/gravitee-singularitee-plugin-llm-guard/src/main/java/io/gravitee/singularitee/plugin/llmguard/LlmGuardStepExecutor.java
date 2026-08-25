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
package io.gravitee.singularitee.plugin.llmguard;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.TextGenEngine;
import io.gravitee.singularitee.engine.api.TextGenRequest;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateContextHelper;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.pipeline.executor.TokenCaptureStream;
import io.gravitee.singularitee.engine.api.pipeline.executor.TokenStreamWriter;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry.ModelEntry;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.SamplingParams;
import io.gravitee.singularitee.protocol.StepRole;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes an LLM_GUARD step: asks a judge LLM for a verdict on the content
 * and applies the configured action (REJECT or WARN) based on the verdict.
 *
 * <p>Fully reactive, no {@code CountDownLatch} or blocking. Chains on the
 * {@link Completable} from {@link TextGenEngine#rxAddSequence}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlmGuardStepExecutor
  extends ModelBoundStepExecutor<LlmGuardStepConfig, TextGenEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmGuardStepExecutor.class);

  private static final String DEFAULT_SAFE_TOKEN = "safe";
  private static final int DEFAULT_MAX_TOKENS = 64;

  private final TemplateRenderer jinjaRenderer;

  public LlmGuardStepExecutor(StepExecutionContext execContext, TemplateRenderer jinjaRenderer) {
    super(execContext);
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  protected String getModelId(LlmGuardStepConfig config) {
    return config.modelId();
  }

  @Override
  protected Class<TextGenEngine> engineType() {
    return TextGenEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    LlmGuardStepConfig cfg,
    TextGenEngine engine,
    StepContext ctx
  ) {
    var pctx = ctx.pipelineContext();

    // Build Jinja4j context for expression resolution
    Map<String, Object> jinjaCtx = buildGuardJinjaContext(pctx, cfg);

    // Build request: raw_template path vs. messages path
    String prompt = null;
    List<ChatTurn> messages = null;

    if (!cfg.rawTemplate().isBlank()) {
      prompt = resolveJinja(cfg.rawTemplate(), jinjaCtx);
    } else if (!cfg.messages().isEmpty()) {
      messages = cfg
        .messages()
        .stream()
        .map(md -> new ChatTurn(toChatRole(md.role()), resolveJinja(md.content(), jinjaCtx)))
        .toList();
    } else {
      LOGGER.warn("LlmGuardStep '{}': no messages or raw_template configured, skipping", stepId);
      return ctx.rxNextStep(stepId);
    }

    if (LOGGER.isTraceEnabled() && prompt != null) {
      LOGGER.trace(
        "LlmGuardStep '{}': rendered prompt ({} chars) ->\n{}",
        stepId,
        prompt.length(),
        prompt
      );
    }

    var textGenReq = buildTextGenRequest(cfg, prompt, messages);

    int stepSeqId = execContext
      .lookupModel(cfg.modelId())
      .map(ModelEntry::nextSequenceId)
      .orElse(0);

    var accumulator = new StringBuilder();

    return Completable.create(emitter -> {
      // Never stream guard verdicts to the client.
      // Always strip thinking so that reasoning tokens emitted by the guard
      // model never contaminate verdict_full or the isSafe check.
      var captureStream = new TokenCaptureStream(
        accumulator,
        emitter,
        ctx.response(),
        TokenCaptureStream.CaptureConfig.capturing(
          StepRole.STEP_ROLE_INTERNAL,
          TokenCaptureStream.ThinkingMode.STRIP
        )
      );
      // Stream the guard model's tokens into the (non-forwarding) capture stream via the
      // engine's per-sequence reactive surface, consistent with InferStepExecutor.
      var handle = TokenStreamWriter.subscribe(
        engine,
        stepSeqId,
        captureStream,
        ctx.callerContext(),
        "",
        cfg.modelId()
      );

      Completable addSeq = engine.rxAddSequence(stepSeqId, textGenReq);
      Runnable submit = () -> {
        Disposable d = addSeq.subscribe(
          () -> {},
          err -> {
            handle.cancel();
            emitter.tryOnError(err);
          }
        );
        emitter.setCancellable(() -> {
          handle.cancel();
          d.dispose();
        });
      };

      if (ctx.callerContext() != null) {
        ctx.callerContext().runOnContext(v -> submit.run());
      } else {
        submit.run();
      }
    }).andThen(
      Maybe.defer(() -> {
        String verdict = accumulator.toString().strip();
        LOGGER.debug("LlmGuardStep '{}': raw output:\n{}", stepId, verdict);

        String safeToken = cfg.safeToken().isBlank() ? DEFAULT_SAFE_TOKEN : cfg.safeToken();
        boolean isSafe = verdict
          .toLowerCase(Locale.ROOT)
          .startsWith(safeToken.toLowerCase(Locale.ROOT));

        String verdictFirstLine = verdict.lines().findFirst().orElse(verdict).strip();
        // Flat dot-keys are split by TemplateContextHelper#buildStepOutputContext on
        // the FIRST dot only to form nested `stepId.field` maps. A key like
        // `input_guard.verdict.full` would therefore produce
        // `input_guard = { "verdict.full": ... }`, unreachable via Jinja's
        // `.` navigation. Use `verdict_full` so `{{ input_guard.verdict_full }}`
        // resolves as `input_guard["verdict_full"]`.
        pctx.set(stepId + ".verdict", verdictFirstLine);
        pctx.set(stepId + ".verdict_full", verdict);
        // Append to the verdicts log, preserved in execution order and
        // kept separate from generated_messages so templates can
        // distinguish conversation content from safety metadata.
        pctx.addVerdict(stepId, verdictFirstLine, verdict);

        LOGGER.info(
          "LlmGuardStep '{}': verdict='{}' safe={} (safe_token='{}')",
          stepId,
          verdictFirstLine,
          isSafe,
          safeToken
        );

        if (!isSafe) {
          LOGGER.info(
            "LlmGuardStep '{}': triggered (verdict='{}', action={})",
            stepId,
            verdict,
            cfg.action()
          );
          applyAction(cfg.action(), stepId, cfg, verdict, pctx, buildGuardJinjaContext(pctx, cfg));
        }

        return ctx.rxNextStep(stepId);
      })
    );
  }

  // -----------------------------------------------------------------------
  // Action handling
  // -----------------------------------------------------------------------

  private void applyAction(
    GuardAction action,
    String stepId,
    LlmGuardStepConfig cfg,
    String verdict,
    PipelineContext pctx,
    Map<String, Object> jinjaCtx
  ) {
    switch (action) {
      case REJECT -> {
        if (!cfg.message().isBlank()) {
          pctx.setHaltMessage(resolveJinja(cfg.message(), jinjaCtx));
        }
        pctx.signalHalt(stepId, FinishReason.FINISH_REASON_GUARD_BLOCKED);
      }
      case WARN -> {
        pctx.set(PipelineContext.KEY_GUARD_TRIGGERED, stepId);
        LOGGER.warn("LlmGuardStep '{}': warning, verdict='{}'", stepId, verdict);
      }
      case REDACT -> {
        LOGGER.warn(
          "LlmGuardStep '{}': REDACT is not supported for LLM guards, falling back to WARN",
          stepId
        );
        pctx.set(PipelineContext.KEY_GUARD_TRIGGERED, stepId);
      }
    }
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private static ChatRole toChatRole(String role) {
    if (role == null) return ChatRole.USER;
    return switch (role.toLowerCase(Locale.ROOT)) {
      case "system" -> ChatRole.SYSTEM;
      case "assistant" -> ChatRole.ASSISTANT;
      default -> ChatRole.USER;
    };
  }

  private static TextGenRequest buildTextGenRequest(
    LlmGuardStepConfig cfg,
    String prompt,
    List<ChatTurn> messages
  ) {
    SamplingParams sp = cfg.samplingParams();

    int maxTokens = sp.getMaxTokens() > 0 ? sp.getMaxTokens() : DEFAULT_MAX_TOKENS;
    Float temperature = sp.getTemperature() > 0 ? sp.getTemperature() : null;
    Float topP = sp.getTopP() > 0 ? sp.getTopP() : null;

    return new TextGenRequest(
      messages != null ? null : prompt,
      messages,
      maxTokens,
      temperature,
      topP,
      null, // presencePenalty
      null, // frequencyPenalty
      null, // stop
      null, // seed
      null, // reasoningTags
      null, // toolCallTags
      null, // loraName
      null // loraPath
    );
  }

  // Jinja4j helpers

  /**
   * Builds the Jinja rendering context used to evaluate both the guard's
   * prompt template (raw_template or messages) and the rejection message.
   *
   * <p>Delegates to {@link TemplateContextHelper} for the common base (prompt,
   * system, history, messages, generated_messages, verdicts, step outputs).
   * Then overlays the per-step {@code context:} variables so that templates can
   * reference variables declared in the YAML (e.g.
   * {@code {% for cat in categories %}}).
   */
  private static Map<String, Object> buildGuardJinjaContext(
    PipelineContext pctx,
    LlmGuardStepConfig cfg
  ) {
    Map<String, Object> ctx = TemplateContextHelper.buildBaseContext(pctx);
    if (!cfg.context().isEmpty()) {
      TemplateContextHelper.mergeStepContext(ctx, TemplateContextHelper.mapToStruct(cfg.context()));
    }
    return ctx;
  }

  private String resolveJinja(String templateString, Map<String, Object> context) {
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace(
        "LlmGuard template render, context:\n{}",
        TemplateContextHelper.dump(context, 200)
      );
    }
    return jinjaRenderer.render(templateString, "<guard>", context);
  }
}
