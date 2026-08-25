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
package io.gravitee.singularitee.plugin.guard;

import io.gravitee.singularitee.engine.api.ChatRole;
import io.gravitee.singularitee.engine.api.ChatTurn;
import io.gravitee.singularitee.engine.api.ClassifierEngine;
import io.gravitee.singularitee.engine.api.ClassifyRequest;
import io.gravitee.singularitee.engine.api.ClassifyResponse;
import io.gravitee.singularitee.engine.api.ClassifyResult;
import io.gravitee.singularitee.engine.api.ModelTasks;
import io.gravitee.singularitee.engine.api.pipeline.PipelineContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.ModelBoundStepExecutor;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutionContext;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateContextHelper;
import io.gravitee.singularitee.engine.api.pipeline.executor.TemplateRenderer;
import io.gravitee.singularitee.engine.api.pipeline.model.GuardAction;
import io.gravitee.singularitee.plugin.guard.GuardStepConfig.GuardTrigger;
import io.gravitee.singularitee.protocol.FinishReason;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a GUARD step: classifies input and applies configured action
 * (REJECT, WARN, or REDACT).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class GuardStepExecutor
  extends ModelBoundStepExecutor<GuardStepConfig, ClassifierEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GuardStepExecutor.class);

  private final TemplateRenderer jinjaRenderer;

  public GuardStepExecutor(StepExecutionContext execContext, TemplateRenderer jinjaRenderer) {
    super(execContext);
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  protected String getModelId(GuardStepConfig config) {
    return config.modelId();
  }

  @Override
  protected Class<ClassifierEngine> engineType() {
    return ClassifierEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    GuardStepConfig cfg,
    ClassifierEngine engine,
    StepContext ctx
  ) {
    String text = resolveInputText(stepId, cfg.inputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    return engine
      .rxClassify(new ClassifyRequest(text))
      .flatMapMaybe(result -> {
        List<GuardTrigger> triggers = cfg.triggers();

        LOGGER.info(
          "GuardStep '{}': classification results={}, watching triggers={}",
          stepId,
          result.allScores(),
          triggers
            .stream()
            .map(t -> t.label() + ">=" + t.score())
            .toList()
        );

        float minThreshold = triggers.isEmpty()
          ? 0f
          : (float) triggers.stream().mapToDouble(GuardTrigger::score).min().orElse(0);

        // Span-bearing results are entity spans only for token-classification engines (NER, regex,
        // stop-words). A sequence classifier whose long input was split also carries spans (the
        // chunk ranges) but those are not entities; gate on the engine's task so they aren't
        // treated as redactable entities or used to trigger the guard.
        boolean tokenClassification = ModelTasks.TOKEN_CLASSIFICATION.equals(engine.task());
        boolean hasTokenEntities =
          tokenClassification &&
          result.results() != null &&
          result
            .results()
            .stream()
            .anyMatch(
              r ->
                r.start() != null &&
                r.end() != null &&
                !r.label().equals("O") &&
                !r.label().startsWith("O-") &&
                r.score() >= minThreshold
            );

        List<MatchedTrigger> matchedTriggers = new ArrayList<>();
        for (GuardTrigger t : triggers) {
          Float score = result.allScores().get(t.label());
          if (score != null && score >= t.score()) {
            matchedTriggers.add(new MatchedTrigger(t.label(), score));
          }
        }
        matchedTriggers.sort(Comparator.comparingDouble(MatchedTrigger::score).reversed());

        boolean triggered = hasTokenEntities || !matchedTriggers.isEmpty();

        if (!triggered) {
          if (cfg.action() == GuardAction.REDACT) {
            String outputField = resolveOutputField(cfg.outputField(), stepId, ".redacted");
            ctx.pipelineContext().set(outputField, text);
          }
          return ctx.rxNextStep(stepId);
        }

        LOGGER.info(
          "GuardStep '{}': triggered, {} match(es): [{}], action={}",
          stepId,
          matchedTriggers.size(),
          matchedTriggers.stream().map(MatchedTrigger::toString).collect(Collectors.joining(", ")),
          cfg.action()
        );

        applyAction(
          cfg.action(),
          stepId,
          cfg,
          text,
          result,
          hasTokenEntities,
          minThreshold,
          matchedTriggers,
          ctx
        );
        return ctx.rxNextStep(stepId);
      });
  }

  private record MatchedTrigger(String label, float score) {
    @Override
    public String toString() {
      return label + "=" + String.format("%.4f", score);
    }
  }

  private static void publishTriggerVariables(
    String stepId,
    List<MatchedTrigger> matchedTriggers,
    PipelineContext pctx
  ) {
    if (matchedTriggers.isEmpty()) return;

    MatchedTrigger top = matchedTriggers.getFirst();
    pctx.set(stepId + ".label", top.label());
    pctx.set(stepId + ".score", String.format("%.4f", top.score()));

    String labels = matchedTriggers
      .stream()
      .map(MatchedTrigger::label)
      .collect(Collectors.joining(", "));
    String scores = matchedTriggers
      .stream()
      .map(t -> String.format("%.4f", t.score()))
      .collect(Collectors.joining(", "));
    String details = matchedTriggers
      .stream()
      .map(t -> t.label() + ": " + String.format("%.4f", t.score()))
      .collect(Collectors.joining(", "));

    pctx.set(stepId + ".labels", labels);
    pctx.set(stepId + ".scores", scores);

    // Append to verdicts log, kept separate from generated_messages so
    // downstream steps can distinguish safety metadata from assistant turns.
    pctx.addVerdict(stepId, top.label(), details);
    pctx.set(stepId + ".details", details);
  }

  private void applyAction(
    GuardAction action,
    String stepId,
    GuardStepConfig cfg,
    String text,
    ClassifyResponse result,
    boolean hasTokenEntities,
    float threshold,
    List<MatchedTrigger> matchedTriggers,
    StepContext ctx
  ) {
    var pctx = ctx.pipelineContext();
    publishTriggerVariables(stepId, matchedTriggers, pctx);

    switch (action) {
      case REJECT -> {
        if (!cfg.message().isBlank()) {
          pctx.setHaltMessage(resolveGuardMessage(cfg.message(), pctx));
        }
        pctx.signalHalt(cfg.inputField(), FinishReason.FINISH_REASON_GUARD_BLOCKED);
      }
      case WARN -> {
        pctx.set(PipelineContext.KEY_GUARD_TRIGGERED, stepId);
        LOGGER.warn(
          "GuardStep '{}': warning, matched triggers: [{}]",
          stepId,
          matchedTriggers.stream().map(MatchedTrigger::toString).collect(Collectors.joining(", "))
        );
      }
      case REDACT -> {
        String outputField = resolveOutputField(cfg.outputField(), stepId, ".redacted");
        String redacted = hasTokenEntities
          ? redactSpans(text, result.results(), threshold, cfg.redactWithEntityType())
          : "************";
        pctx.set(outputField, redacted);

        if (pctx.messages() != null) {
          var redactedMessages = pctx
            .messages()
            .stream()
            .map(m -> {
              if (m.role() == ChatRole.USER && m.content().equals(text)) {
                return new ChatTurn(m.role(), redacted, m.media());
              }
              return m;
            })
            .toList();
          pctx.setMessages(redactedMessages);
        }

        // Keep KEY_PROMPT in sync: if the guard was operating on the prompt field,
        // {{ prompt }} in downstream templates must reflect the redacted value,
        // not the original unredacted input. Without this, {{ prompt }} leaks
        // PII even after a successful redaction pass.
        String inputField = cfg.inputField();
        if (inputField.isBlank() || inputField.equals(PipelineContext.KEY_PROMPT)) {
          pctx.set(PipelineContext.KEY_PROMPT, redacted);
        }
      }
    }
  }

  private static String redactSpans(
    String text,
    List<ClassifyResult> results,
    float threshold,
    boolean useEntityType
  ) {
    if (results == null || results.isEmpty()) return text;

    var spans = results
      .stream()
      .filter(
        r ->
          r.start() != null &&
          r.end() != null &&
          !r.label().equals("O") &&
          !r.label().startsWith("O-") &&
          r.score() >= threshold
      )
      .sorted((a, b) -> Integer.compare(a.start(), b.start()))
      .toList();

    if (spans.isEmpty()) return text;

    var merged = new ArrayList<int[]>();
    var mergedLabels = new ArrayList<String>();
    int[] current = { spans.getFirst().start(), spans.getFirst().end() };
    String currentLabel = spans.getFirst().label();
    float currentScore = spans.getFirst().score();
    for (int i = 1; i < spans.size(); i++) {
      int nextStart = spans.get(i).start();
      int nextEnd = spans.get(i).end();
      if (nextStart <= current[1] + 1) {
        current[1] = Math.max(current[1], nextEnd);
        if (spans.get(i).score() > currentScore) {
          currentLabel = spans.get(i).label();
          currentScore = spans.get(i).score();
        }
      } else {
        merged.add(current);
        mergedLabels.add(currentLabel);
        current = new int[] { nextStart, nextEnd };
        currentLabel = spans.get(i).label();
        currentScore = spans.get(i).score();
      }
    }
    merged.add(current);
    mergedLabels.add(currentLabel);

    var sb = new StringBuilder(text);
    for (int i = merged.size() - 1; i >= 0; i--) {
      int start = Math.max(0, merged.get(i)[0]);
      int end = Math.min(sb.length(), merged.get(i)[1]);
      if (start < end) {
        String replacement = useEntityType
          ? "[" + mergedLabels.get(i).toUpperCase() + "]"
          : "************";
        sb.replace(start, end, replacement);
      }
    }
    return sb.toString();
  }

  /**
   * Resolves a guard message template using the shared {@link TemplateRenderer}.
   * Supports expressions like {{ toxicity_guard.label }}, {{ toxicity_guard.score }},
   * {{ prompt }}, {% for v in verdicts %} etc.
   */
  private String resolveGuardMessage(String template, PipelineContext pctx) {
    if (template == null || template.isBlank()) return "";
    var ctx = TemplateContextHelper.buildBaseContext(pctx);
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace(
        "Guard reject-message render, context:\n{}",
        TemplateContextHelper.dump(ctx, 200)
      );
    }
    return jinjaRenderer.render(template, "<guard_msg>", ctx);
  }
}
