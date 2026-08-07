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
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.GuardAction;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.RegexEntityDef;
import io.gravitee.singularitee.protocol.RegexGuardStepConfig;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a REGEX_GUARD step: pattern-based guard driven by a unified list of
 * named {@link RegexEntityDef} entries. Each entry carries a free-form
 * {@code name} (label) and a Java {@code pattern}. The configured action
 * determines the behaviour:
 *
 * <h3>REJECT / WARN</h3>
 * <p>The executor combines all patterns into a single alternation at
 * compile-time by automatically wrapping each pattern with a positional named
 * group:
 * <pre>(?&lt;P0&gt;pat0)|(?&lt;P1&gt;pat1)|…</pre>
 * When a match fires, the executor scans the groups {@code P0}, {@code P1}, …
 * to find the matching index, then maps it back to the corresponding entry's
 * {@code name}. Users never write named-group syntax in their patterns and
 * there are no Java identifier restrictions on the entry names — spaces,
 * dashes, slashes, and other characters are all valid labels.
 *
 * <p>Context variables written on match:
 * <ul>
 *   <li>{@code <stepId>.triggered}   — {@code "true"}</li>
 *   <li>{@code <stepId>.match}       — the first matched substring</li>
 *   <li>{@code <stepId>.pattern}     — the first matched pattern string</li>
 *   <li>{@code <stepId>.entity_type} — the name of the first matched entry</li>
 * </ul>
 *
 * <h3>REDACT</h3>
 * <p>Each pattern is applied individually via {@link Matcher#find()} to
 * collect all {@code (start, end, name)} spans. Overlapping spans are merged
 * (same algorithm as {@link GuardStepExecutor#redactSpans}). Each span is
 * replaced with {@code [NAME]} (upper-cased) when
 * {@code redact_with_entity_type = true}, or {@code [REDACTED]} otherwise.
 * {@code pctx.messages()} and {@link PipelineContext#KEY_PROMPT} are updated
 * so that {@code {{ prompt }}}, {@code {{ messages }}}, and
 * {@code {{ history }}} always reflect the redacted text downstream.
 *
 * <p>Context variables written on match:
 * <ul>
 *   <li>{@code <stepId>.triggered}    — {@code "true"}</li>
 *   <li>{@code <stepId>.entity_types} — CSV of matched entry names</li>
 * </ul>
 *
 * <h3>Compiled-pattern cache</h3>
 * <p>For REJECT/WARN the combined alternation is keyed by the full list of
 * patterns so recompilation only occurs when the pattern list changes.
 * Individual patterns for REDACT are also cached separately.
 *
 * @deprecated Prefer declaring a {@code regex} model in the workspace and
 * using a generic {@code type: guard} step that references it. The
 * {@link io.gravitee.singularitee.engine.classifier.RegexClassifierEngine}
 * exposes the same matching logic as a {@code ClassifierEngine} and composes
 * naturally with {@link io.gravitee.singularitee.engine.classifier.CompositeClassifierEngine}.
 * This dedicated step type is kept for backward compatibility only.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@Deprecated(since = "next", forRemoval = false)
public final class RegexGuardStepExecutor implements StepExecutor<RegexGuardStepConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RegexGuardStepExecutor.class);

  /**
   * Cache for the combined alternation used in REJECT/WARN mode.
   * Key: concatenation of all pattern strings separated by NUL.
   */
  private final Map<String, Pattern> combinedPatternCache = new ConcurrentHashMap<>();

  /** Cache for individual patterns used in REDACT span-collection mode. */
  private final Map<String, Pattern> singlePatternCache = new ConcurrentHashMap<>();

  private final JinjaRenderer jinjaRenderer;

  public RegexGuardStepExecutor(JinjaRenderer jinjaRenderer) {
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  public RegexGuardStepConfig extractConfig(PipelineStep step) {
    return step.getRegexGuardConfig();
  }

  @Override
  public Maybe<String> execute(String stepId, RegexGuardStepConfig cfg, StepContext ctx) {
    if (cfg.getPatternsCount() == 0) {
      LOGGER.debug("RegexGuard '{}': no patterns configured — skipping", stepId);
      return ctx.rxNextStep(stepId);
    }

    String text = resolveInputText(stepId, cfg.getInputField(), ctx);
    if (text == null) return ctx.rxNextStep(stepId);

    if (cfg.getAction() == GuardAction.GUARD_ACTION_REDACT) {
      return executeRedact(stepId, cfg, text, ctx);
    } else {
      return executeTrigger(stepId, cfg, text, ctx);
    }
  }

  // ── REJECT / WARN — single combined alternation with positional groups ────

  private Maybe<String> executeTrigger(
    String stepId,
    RegexGuardStepConfig cfg,
    String text,
    StepContext ctx
  ) {
    List<RegexEntityDef> entries = cfg.getPatternsList();
    Pattern combined = buildCombinedPattern(entries);
    Matcher m = combined.matcher(text);

    if (!m.find()) {
      return ctx.rxNextStep(stepId);
    }

    // Find which P{i} group matched — the first non-null group is the winner
    int matchedIdx = -1;
    for (int i = 0; i < entries.size(); i++) {
      if (m.group("P" + i) != null) {
        matchedIdx = i;
        break;
      }
    }

    if (matchedIdx < 0) {
      // Should not happen, but safe fallback
      return ctx.rxNextStep(stepId);
    }

    RegexEntityDef matched = entries.get(matchedIdx);
    String matchedText = m.group("P" + matchedIdx);

    LOGGER.info(
      "RegexGuard '{}': triggered — name='{}', match='{}', action={}",
      stepId,
      matched.getName(),
      matchedText,
      cfg.getAction()
    );

    var pctx = ctx.pipelineContext();
    pctx.set(stepId + ".triggered", "true");
    pctx.set(stepId + ".match", matchedText);
    pctx.set(stepId + ".pattern", matched.getPattern());
    pctx.set(stepId + ".entity_type", matched.getName());

    switch (cfg.getAction()) {
      case GUARD_ACTION_REJECT -> {
        if (cfg.hasMessage() && !cfg.getMessage().isBlank()) {
          pctx.setHaltMessage(resolveMessage(cfg.getMessage(), pctx));
        }
        pctx.signalHalt(cfg.getInputField(), FinishReason.FINISH_REASON_GUARD_BLOCKED);
      }
      case GUARD_ACTION_WARN -> {
        pctx.set(PipelineContext.KEY_GUARD_TRIGGERED, stepId);
        LOGGER.warn(
          "RegexGuard '{}': warning — name='{}', match='{}'",
          stepId,
          matched.getName(),
          matchedText
        );
      }
      default -> LOGGER.warn(
        "RegexGuard '{}': action {} is not applicable in trigger mode",
        stepId,
        cfg.getAction()
      );
    }

    return ctx.rxNextStep(stepId);
  }

  // ── REDACT — per-entry span collection ───────────────────────────────────

  private Maybe<String> executeRedact(
    String stepId,
    RegexGuardStepConfig cfg,
    String text,
    StepContext ctx
  ) {
    record Span(int start, int end, String name) {}
    List<Span> allSpans = new ArrayList<>();

    for (RegexEntityDef entry : cfg.getPatternsList()) {
      if (entry.getPattern().isBlank()) continue;
      Pattern p = compileSingle(entry.getPattern());
      Matcher m = p.matcher(text);
      while (m.find()) {
        allSpans.add(new Span(m.start(), m.end(), entry.getName()));
      }
    }

    if (allSpans.isEmpty()) {
      String outputField = resolveOutputField(cfg.getOutputField(), stepId);
      ctx.pipelineContext().set(outputField, text);
      return ctx.rxNextStep(stepId);
    }

    // Sort by start, then merge overlapping / adjacent spans
    allSpans.sort((a, b) -> Integer.compare(a.start(), b.start()));
    List<int[]> mergedBounds = new ArrayList<>();
    List<String> mergedNames = new ArrayList<>();

    int[] cur = { allSpans.get(0).start(), allSpans.get(0).end() };
    String curName = allSpans.get(0).name();
    for (int i = 1; i < allSpans.size(); i++) {
      Span s = allSpans.get(i);
      if (s.start() <= cur[1] + 1) {
        cur[1] = Math.max(cur[1], s.end());
      } else {
        mergedBounds.add(new int[] { cur[0], cur[1] });
        mergedNames.add(curName);
        cur = new int[] { s.start(), s.end() };
        curName = s.name();
      }
    }
    mergedBounds.add(cur);
    mergedNames.add(curName);

    LinkedHashSet<String> matchedNames = new LinkedHashSet<>(mergedNames);
    String entityTypesCsv = String.join(", ", matchedNames);

    LOGGER.info(
      "RegexGuard '{}': redacting {} span(s) — types=[{}]",
      stepId,
      mergedBounds.size(),
      entityTypesCsv
    );

    // Replace in reverse order to preserve earlier offsets
    var sb = new StringBuilder(text);
    for (int i = mergedBounds.size() - 1; i >= 0; i--) {
      int start = Math.max(0, mergedBounds.get(i)[0]);
      int end = Math.min(sb.length(), mergedBounds.get(i)[1]);
      if (start < end) {
        String replacement = cfg.getRedactWithEntityType()
          ? "[" + mergedNames.get(i).toUpperCase() + "]"
          : "[REDACTED]";
        sb.replace(start, end, replacement);
      }
    }
    String redacted = sb.toString();

    var pctx = ctx.pipelineContext();
    pctx.set(stepId + ".triggered", "true");
    pctx.set(stepId + ".entity_types", entityTypesCsv);

    String outputField = resolveOutputField(cfg.getOutputField(), stepId);
    pctx.set(outputField, redacted);

    // Rewrite messages so {{ messages }} / {{ history }} reflect the redaction
    if (pctx.messages() != null) {
      var redactedMessages = pctx
        .messages()
        .stream()
        .map(msg -> {
          if (msg.role() == ChatRole.USER && msg.content().equals(text)) {
            return new ChatTurn(msg.role(), redacted, msg.media());
          }
          return msg;
        })
        .toList();
      pctx.setMessages(redactedMessages);
    }

    // Sync KEY_PROMPT so {{ prompt }} reflects the redaction
    String inputField = cfg.getInputField();
    if (
      inputField == null || inputField.isBlank() || inputField.equals(PipelineContext.KEY_PROMPT)
    ) {
      pctx.set(PipelineContext.KEY_PROMPT, redacted);
    }

    return ctx.rxNextStep(stepId);
  }

  // ── Pattern compilation helpers ───────────────────────────────────────────

  /**
   * Builds (or retrieves from cache) the combined alternation pattern for
   * REJECT/WARN mode.
   *
   * <p>Each entry's pattern is wrapped with a positional named group
   * {@code (?<P{i}>…)} so that the matching index can be recovered without
   * requiring users to write named groups in their patterns. Group names
   * {@code P0}, {@code P1}, … are valid Java identifiers and will never
   * conflict with user content since users don't write them.
   *
   * <p>The cache key is the NUL-joined concatenation of all pattern strings
   * in order, so the pattern is recompiled only when the list changes.
   */
  private Pattern buildCombinedPattern(List<RegexEntityDef> entries) {
    String cacheKey = entries
      .stream()
      .map(RegexEntityDef::getPattern)
      .collect(Collectors.joining("\u0000"));

    return combinedPatternCache.computeIfAbsent(cacheKey, k -> {
      String alternation = IntStream.range(0, entries.size())
        .mapToObj(i -> "(?<P" + i + ">" + entries.get(i).getPattern() + ")")
        .collect(Collectors.joining("|"));
      return Pattern.compile(alternation);
    });
  }

  /** Compiles (or retrieves from cache) a single pattern for REDACT span collection. */
  private Pattern compileSingle(String patternStr) {
    return singlePatternCache.computeIfAbsent(patternStr, Pattern::compile);
  }

  // ── Shared helpers ─────────────────────────────────────────────────────────

  /** Reads the input field, falling back to {@code KEY_PROMPT}. Returns {@code null} on miss. */
  private static String resolveInputText(String stepId, String inputField, StepContext ctx) {
    String field = (inputField == null || inputField.isBlank())
      ? PipelineContext.KEY_PROMPT
      : inputField;
    String value = ctx.pipelineContext().get(field);
    if (value == null || value.isBlank()) {
      LOGGER.debug("RegexGuard '{}': input field '{}' is empty — skipping", stepId, field);
      return null;
    }
    return value;
  }

  /** Resolves the output field name, defaulting to {@code <stepId>.output}. */
  private static String resolveOutputField(String provided, String stepId) {
    return (provided == null || provided.isBlank()) ? stepId + ".output" : provided;
  }

  /** Renders the guard reject-message template using the shared {@link JinjaRenderer}. */
  private String resolveMessage(String template, PipelineContext pctx) {
    if (template == null || template.isBlank()) return "";
    return jinjaRenderer.render(
      template,
      "<regex_guard_msg>",
      JinjaContextHelper.buildBaseContext(pctx)
    );
  }
}
