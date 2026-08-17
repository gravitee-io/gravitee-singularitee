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

import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.pipeline.evaluator.BreakStepEvaluator;
import io.gravitee.singularitee.protocol.LoopStepConfig;
import io.gravitee.singularitee.protocol.MessageDef;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a LOOP step: evaluates exit condition and loops or proceeds.
 *
 * <p>When the exit condition is not met and the loop has not reached
 * {@code max_iterations}, execution branches back to
 * {@link LoopStepConfig#getTargetStepId()}. If the config carries a
 * {@link LoopStepConfig#hasLoopbackMessage() loopback_message}, it is
 * rendered through Jinja2 against the full pipeline context and appended
 * to {@link PipelineContext#messages()} as a new conversation turn —
 * enabling downstream inference steps to see the feedback as real chat
 * context (conversational refinement, CoT).
 *
 * <p>The loopback message is <b>not</b> injected on condition-met exit
 * (happy path) or when {@code max_iterations} is reached and the loop
 * branches to {@code fallback_step}. Only on the retry edge.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LoopStepExecutor implements StepExecutor<LoopStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(LoopStepExecutor.class);

  private final JinjaRenderer jinjaRenderer;

  public LoopStepExecutor(JinjaRenderer jinjaRenderer) {
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  public LoopStepConfig extractConfig(PipelineStep step) {
    return step.getLoopConfig();
  }

  @Override
  public Maybe<String> execute(String stepId, LoopStepConfig cfg, StepContext ctx) {
    var pctx = ctx.pipelineContext();

    String inputField = cfg.getInputField();
    boolean shouldExit = BreakStepEvaluator.evaluateLoopExit(cfg, pctx);
    // The field value can be a whole generation (a verify verdict, a step
    // output) — logging it verbatim floods the line. The verdict is what
    // matters; the raw value stays available at DEBUG.
    LOGGER.info(
      "LoopStep '{}': condition={} on '{}' (match_value='{}') -> {}, next_step='{}', loopback='{}'",
      stepId,
      cfg.getCondition(),
      inputField,
      cfg.getMatchValue(),
      shouldExit ? "PASSED" : "NOT MET",
      cfg.getNextStepId(),
      cfg.getTargetStepId()
    );
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("LoopStep '{}': value='{}'", stepId, pctx.get(inputField));
    }

    if (shouldExit) {
      pctx.setRetrySamplingParams(null);
      LOGGER.info(
        "LoopStep '{}': exit condition met, proceeding to '{}'",
        stepId,
        cfg.getNextStepId()
      );
      return Maybe.just(cfg.getNextStepId());
    }

    int currentIteration = pctx.incrementIteration(stepId);
    int maxIterations = cfg.getMaxIterations();
    pctx.set(stepId + ".iterations", Integer.toString(currentIteration));

    if (maxIterations > 0 && currentIteration >= maxIterations) {
      pctx.set(stepId + ".max_iterations_reached", "true");
      if (ctx.metrics() != null) {
        ctx.metrics().recordFailureSignal(stepId, "loop_max_iterations");
      }
      LOGGER.warn(
        "LoopStep '{}': max iterations ({}) reached without condition being met, branching to fallback",
        stepId,
        maxIterations
      );
      pctx.setRetrySamplingParams(null);
      String fallback = cfg.getFallbackStepId();
      return Maybe.just((fallback != null && !fallback.isBlank()) ? fallback : cfg.getNextStepId());
    }

    // We're looping back — inject the configured feedback message (if any)
    // into the conversation so the next iteration of target_step_id sees it
    // as a real chat turn, and install the retry sampling override (if any)
    // so the retry runs tighter than the first attempt.
    if (cfg.hasLoopbackMessage()) {
      injectLoopbackMessage(stepId, cfg.getLoopbackMessage(), pctx);
    }
    if (cfg.hasRetrySamplingParams()) {
      pctx.setRetrySamplingParams(cfg.getRetrySamplingParams());
      LOGGER.info(
        "LoopStep '{}': retry sampling override installed (temperature={})",
        stepId,
        cfg.getRetrySamplingParams().getTemperature()
      );
    }

    LOGGER.debug(
      "LoopStep '{}': iteration {}/{}, looping back to '{}'",
      stepId,
      currentIteration,
      maxIterations,
      cfg.getTargetStepId()
    );
    return Maybe.just(cfg.getTargetStepId());
  }

  /**
   * Renders {@code msg.content} through Jinja2 against the full pipeline
   * context and appends the result to {@link PipelineContext#messages()}
   * as a chat turn with the configured role (default {@code user}).
   *
   * <p>Failures are non-fatal: if the template fails to render or the role
   * is malformed we log a warning and skip the injection rather than aborting
   * the loop — loss of a refinement cue is always preferable to a pipeline
   * crash.
   */
  private void injectLoopbackMessage(String stepId, MessageDef msg, PipelineContext pctx) {
    String content = msg.getContent();
    if (content == null || content.isBlank()) {
      LOGGER.debug("LoopStep '{}': loopback_message content is empty — skipping injection", stepId);
      return;
    }

    Map<String, Object> jinjaCtx = JinjaContextHelper.buildBaseContext(pctx);
    String rendered;
    try {
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace(
          "LoopStep '{}': loopback_message render — context:\n{}",
          stepId,
          JinjaContextHelper.dump(jinjaCtx, 200)
        );
      }
      rendered = jinjaRenderer.render(content, "<loopback>", jinjaCtx);
    } catch (RuntimeException e) {
      LOGGER.warn(
        "LoopStep '{}': failed to render loopback_message — skipping injection: {}",
        stepId,
        e.getMessage()
      );
      return;
    }

    if (rendered == null || rendered.isBlank()) {
      LOGGER.debug(
        "LoopStep '{}': loopback_message rendered to empty string — skipping injection",
        stepId
      );
      return;
    }

    ChatRole role = resolveRole(msg.getRole());
    int before = pctx.messages() != null ? pctx.messages().size() : 0;
    pctx.appendMessage(new ChatTurn(role, rendered));
    int after = pctx.messages() != null ? pctx.messages().size() : 0;

    LOGGER.info(
      "LoopStep '{}': injected loopback_message (role={}, {} chars) — messages grew from {} to {}",
      stepId,
      role,
      rendered.length(),
      before,
      after
    );
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("LoopStep '{}': loopback_message content ->\n{}", stepId, rendered);
    }
  }

  /**
   * Resolves the string role declared in YAML to a {@link ChatRole}, defaulting
   * to {@link ChatRole#USER} when unset or unknown. Matches the permissive
   * parsing used elsewhere for YAML-authored roles.
   */
  private static ChatRole resolveRole(String roleString) {
    if (roleString == null || roleString.isBlank()) return ChatRole.USER;
    return switch (roleString.toLowerCase()) {
      case "system" -> ChatRole.SYSTEM;
      case "assistant" -> ChatRole.ASSISTANT;
      case "user" -> ChatRole.USER;
      default -> {
        LOGGER.warn(
          "LoopStep: unknown loopback_message role '{}' — defaulting to USER",
          roleString
        );
        yield ChatRole.USER;
      }
    };
  }
}
