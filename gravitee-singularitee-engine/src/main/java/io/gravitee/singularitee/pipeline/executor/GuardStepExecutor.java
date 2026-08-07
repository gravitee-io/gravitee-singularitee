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
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.engine.ClassifyResponse;
import io.gravitee.singularitee.engine.ModelTasks;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.GuardAction;
import io.gravitee.singularitee.protocol.GuardStepConfig;
import io.gravitee.singularitee.protocol.GuardTrigger;
import io.gravitee.singularitee.protocol.PipelineStep;
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

  private final JinjaRenderer jinjaRenderer;

  public GuardStepExecutor(StepExecutionContext execContext, JinjaRenderer jinjaRenderer) {
    super(execContext);
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  public GuardStepConfig extractConfig(PipelineStep step) {
    return step.getGuardConfig();
  }

  @Override
  protected String getModelId(GuardStepConfig config) {
    return config.getModelId();
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
    String text = resolveInputText(stepId, cfg.getInputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    return engine
      .rxClassify(new ClassifyRequest(text))
      .flatMapMaybe(result -> {
        // Resolve effective triggers
        List<GuardTrigger> triggers = cfg.getTriggersList();
        if (triggers.isEmpty() && !cfg.getTriggerLabel().isBlank()) {
          triggers = List.of(
            GuardTrigger.newBuilder()
              .setLabel(cfg.getTriggerLabel())
              .setScore(cfg.getTriggerScore())
              .build()
          );
        }

        LOGGER.info(
          "GuardStep '{}': classification results={}, watching triggers={}",
          stepId,
          result.allScores(),
          triggers
            .stream()
            .map(t -> t.getLabel() + ">=" + t.getScore())
            .toList()
        );

        float minThreshold = triggers.isEmpty()
          ? 0f
          : (float) triggers.stream().mapToDouble(GuardTrigger::getScore).min().orElse(0);

        // Span-bearing results are entity spans only for token-classification engines (NER, regex,
        // stop-words). A sequence classifier whose long input was split also carries spans (the
        // chunk ranges) but those are not entities — gate on the engine's task so they aren't
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
          Float score = result.allScores().get(t.getLabel());
          if (score != null && score >= t.getScore()) {
            matchedTriggers.add(new MatchedTrigger(t.getLabel(), score));
          }
        }
        matchedTriggers.sort(Comparator.comparingDouble(MatchedTrigger::score).reversed());

        boolean triggered = hasTokenEntities || !matchedTriggers.isEmpty();

        if (!triggered) {
          if (cfg.getAction() == GuardAction.GUARD_ACTION_REDACT) {
            String outputField = resolveOutputField(cfg.getOutputField(), stepId, ".redacted");
            ctx.pipelineContext().set(outputField, text);
          }
          return ctx.rxNextStep(stepId);
        }

        LOGGER.info(
          "GuardStep '{}': triggered — {} match(es): [{}], action={}",
          stepId,
          matchedTriggers.size(),
          matchedTriggers.stream().map(MatchedTrigger::toString).collect(Collectors.joining(", ")),
          cfg.getAction()
        );

        applyAction(
          cfg.getAction(),
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

    // Append to verdicts log — kept separate from generated_messages so
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

    if (action == GuardAction.GUARD_ACTION_REJECT) {
      if (cfg.hasMessage() && !cfg.getMessage().isBlank()) {
        pctx.setHaltMessage(resolveGuardMessage(cfg.getMessage(), pctx));
      }
      pctx.signalHalt(cfg.getInputField(), FinishReason.FINISH_REASON_GUARD_BLOCKED);
    } else if (action == GuardAction.GUARD_ACTION_WARN) {
      pctx.set(PipelineContext.KEY_GUARD_TRIGGERED, stepId);
      LOGGER.warn(
        "GuardStep '{}': warning — matched triggers: [{}]",
        stepId,
        matchedTriggers.stream().map(MatchedTrigger::toString).collect(Collectors.joining(", "))
      );
    } else if (action == GuardAction.GUARD_ACTION_REDACT) {
      String outputField = resolveOutputField(cfg.getOutputField(), stepId, ".redacted");
      String redacted = hasTokenEntities
        ? redactSpans(text, result.results(), threshold, cfg.getRedactWithEntityType())
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
      String inputField = cfg.getInputField();
      if (
        inputField == null || inputField.isBlank() || inputField.equals(PipelineContext.KEY_PROMPT)
      ) {
        pctx.set(PipelineContext.KEY_PROMPT, redacted);
      }
    }
  }

  private static String redactSpans(
    String text,
    List<io.gravitee.singularitee.engine.ClassifyResult> results,
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

    var merged = new java.util.ArrayList<int[]>();
    var mergedLabels = new java.util.ArrayList<String>();
    int[] current = { spans.get(0).start(), spans.get(0).end() };
    String currentLabel = spans.get(0).label();
    float currentScore = spans.get(0).score();
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
   * Resolves a guard message template using the shared {@link JinjaRenderer}.
   * Supports expressions like {{ toxicity_guard.label }}, {{ toxicity_guard.score }},
   * {{ prompt }}, {% for v in verdicts %} etc.
   */
  private String resolveGuardMessage(String template, PipelineContext pctx) {
    if (template == null || template.isBlank()) return "";
    var ctx = JinjaContextHelper.buildBaseContext(pctx);
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Guard reject-message render — context:\n{}", JinjaContextHelper.dump(ctx, 200));
    }
    return jinjaRenderer.render(template, "<guard_msg>", ctx);
  }
}
