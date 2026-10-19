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
package io.gravitee.singularitee.plugin.decide;

import io.gravitee.singularitee.engine.api.DecisionEngine;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanNames;
import io.gravitee.singularitee.engine.api.pipeline.executor.SpanScribe;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateContextHelper;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.inference.api.decision.Answer;
import io.gravitee.singularitee.inference.api.decision.DecisionException;
import io.gravitee.singularitee.inference.api.decision.DecisionException.Reason;
import io.gravitee.singularitee.inference.api.decision.DecisionInput;
import io.gravitee.singularitee.inference.api.decision.DecisionRequest;
import io.gravitee.singularitee.inference.api.decision.DecisionResult;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a {@code decide} step: asks a decision model typed questions about a context field
 * and writes the answers to the context. Never branches; {@code route}, {@code break} and
 * {@code loop} act on what it writes.
 *
 * <p>For each question {@code q}, under the prefix {@code p} (the output field, or the step id):
 * <ul>
 *   <li>{@code p.q}: the choice key, the expected score level, or the probability of yes</li>
 *   <li>{@code p.q.confidence} (choice, score), {@code p.q.mode} (score)</li>
 *   <li>{@code p.q.probabilities}: a JSON object of key to probability (choice, score)</li>
 * </ul>
 * The typed answers are also kept on the context, so a decision call that targets the pipeline
 * returns them.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DecideStepExecutor
  extends ModelBoundStepExecutor<DecideStepConfig, DecisionEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DecideStepExecutor.class);

  /** The {@code input_field} value that passes the chat messages as the state. */
  static final String MESSAGES = "messages";

  private final TemplateRenderer renderer;

  public DecideStepExecutor(StepExecutionContext execContext, TemplateRenderer renderer) {
    super(execContext);
    this.renderer = renderer;
  }

  @Override
  protected String getModelId(DecideStepConfig config) {
    return config.modelId();
  }

  @Override
  protected Class<DecisionEngine> engineType() {
    return DecisionEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    DecideStepConfig cfg,
    DecisionEngine engine,
    StepContext ctx
  ) {
    PipelineContext pc = ctx.pipelineContext();
    DecisionRequest caller = pc.decisionRequest();
    DecisionInput input;
    try {
      input = input(stepId, cfg, ctx, caller);
    } catch (DecisionException e) {
      pc.setDecisionFailure(e);
      return Maybe.error(e);
    }
    if (input == null) return ctx.rxNextStep(stepId);

    final SpanScribe stepScribe = ctx.stepScribe();
    String prefix = resolveOutputField(cfg.outputField(), stepId, "");
    boolean includeRaw = caller != null && caller.includeRawScores();
    return engine
      .rxDecide(new DecisionRequest(input, includeRaw))
      .doOnError(e -> {
        if (e instanceof RuntimeException failure) pc.setDecisionFailure(failure);
      })
      .flatMapMaybe(result -> {
        write(pc, prefix, result);
        pc.setDecisionResult(result);
        stepScribe
          .set(SpanNames.key("decision.questions"), (long) result.answers().size())
          .set(SpanNames.key("decision.input_tokens"), (long) result.inputTokens());
        LOGGER.debug("DecideStep '{}': {} answer(s)", stepId, result.answers().size());
        return ctx.rxNextStep(stepId);
      });
  }

  /**
   * What to ask, or {@code null} to skip the step: an empty state outside a decision call. A
   * decision call with an empty state fails, as it would against a model.
   */
  private DecisionInput input(
    String stepId,
    DecideStepConfig cfg,
    StepContext ctx,
    DecisionRequest caller
  ) {
    if (cfg.questionsFromRequest()) {
      if (caller == null) {
        throw new DecisionException(
          Reason.INVALID_REQUEST,
          "step '" +
            stepId +
            "' answers the caller's questions: call this pipeline through the decision API"
        );
      }
      return switch (caller.input()) {
        case DecisionInput.Templated t -> {
          if (t.questions().isEmpty()) {
            throw new DecisionException(Reason.INVALID_REQUEST, "\"questions\" must not be empty");
          }
          Object state = callerState(stepId, cfg, ctx);
          yield state == null ? null : new DecisionInput.Templated(state, t.questions());
        }
        // Guards only check the pipeline's prompt, which caller-rendered prompts never become.
        case DecisionInput.Raw r -> throw rawRefused();
      };
    }
    if (caller != null && sentQuestions(caller)) {
      throw new DecisionException(
        Reason.INVALID_REQUEST,
        "this pipeline declares its own questions; send the state only"
      );
    }
    if (!cfg.prompts().isEmpty()) {
      Map<String, Object> vars = TemplateContextHelper.buildBaseContext(ctx.pipelineContext());
      List<DecisionInput.RawPrompt> prompts = cfg
        .prompts()
        .stream()
        .map(p ->
          new DecisionInput.RawPrompt(
            p.id(),
            p.kind(),
            p.optionKeys(),
            renderer.render(p.template(), "<decide:" + stepId + ">", vars),
            null
          )
        )
        .toList();
      return new DecisionInput.Raw(prompts);
    }
    Object state = caller == null ? state(stepId, cfg, ctx) : callerState(stepId, cfg, ctx);
    return state == null ? null : new DecisionInput.Templated(state, cfg.questions());
  }

  /**
   * The state of a step inside a decision call. The caller must send it when the step reads it
   * (the prompt or the messages): a model would refuse an empty one too.
   */
  private Object callerState(String stepId, DecideStepConfig cfg, StepContext ctx) {
    Object state = state(stepId, cfg, ctx);
    String field = cfg.inputField();
    boolean callerSent =
      field == null ||
      field.isBlank() ||
      PipelineContext.KEY_PROMPT.equals(field) ||
      MESSAGES.equals(field);
    if (state == null && callerSent) {
      throw new DecisionException(Reason.INVALID_REQUEST, "\"state\" must be provided");
    }
    return state;
  }

  /** The refusal of caller-rendered prompts sent to a pipeline. */
  private static DecisionException rawRefused() {
    return new DecisionException(
      Reason.UNSUPPORTED_INPUT,
      "a pipeline renders its prompts from the state its guards check: send a state and questions, not raw prompts"
    );
  }

  private Object state(String stepId, DecideStepConfig cfg, StepContext ctx) {
    if (MESSAGES.equals(cfg.inputField())) {
      return TemplateContextHelper.buildMessages(ctx.pipelineContext());
    }
    return resolveInputText(stepId, cfg.inputField(), ctx);
  }

  private static boolean sentQuestions(DecisionRequest caller) {
    return switch (caller.input()) {
      case DecisionInput.Templated t -> !t.questions().isEmpty();
      case DecisionInput.Raw r -> !r.prompts().isEmpty();
    };
  }

  private static void write(PipelineContext pc, String prefix, DecisionResult result) {
    for (Answer answer : result.answers()) {
      String key = prefix + "." + answer.questionId();
      switch (answer) {
        case Answer.Choice c -> {
          pc.set(key, c.choice());
          pc.set(key + ".confidence", String.valueOf(c.confidence()));
          pc.set(key + ".probabilities", json(c.probabilities()));
        }
        case Answer.Score s -> {
          pc.set(key, String.valueOf(s.score()));
          pc.set(key + ".mode", String.valueOf(s.mode()));
          pc.set(key + ".confidence", String.valueOf(s.confidence()));
          pc.set(key + ".probabilities", json(s.probabilities()));
        }
        case Answer.Noul n -> pc.set(key, String.valueOf(n.probabilityTrue()));
      }
    }
  }

  private static String json(List<Answer.OptionProbability> probabilities) {
    var out = new StringBuilder("{");
    for (Answer.OptionProbability p : probabilities) {
      if (out.length() > 1) out.append(',');
      out.append('"').append(escape(p.key())).append("\":").append(p.probability());
    }
    return out.append('}').toString();
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
