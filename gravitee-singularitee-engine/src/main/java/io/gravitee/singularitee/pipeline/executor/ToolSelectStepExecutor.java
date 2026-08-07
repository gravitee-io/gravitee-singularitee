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

import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.ClassifierEngine;
import io.gravitee.singularitee.engine.ClassifierEngine.ClassifyLabel;
import io.gravitee.singularitee.engine.ClassifyRequest;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.PipelineStep;
import io.gravitee.singularitee.protocol.ToolDefinition;
import io.gravitee.singularitee.protocol.ToolSelectStepConfig;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a TOOL_SELECT step: shortlists the caller's tools with a zero-shot
 * GLiNER classifier so that the downstream infer step only injects the tool
 * schemas that are relevant to the LAST USER MESSAGE.
 *
 * <p>The classifier is called with per-request label overrides (one
 * {@link ClassifyLabel} per tool, description condensed) in batches of
 * {@code batch_size} tools, each batch augmented with a synthetic
 * {@code none_of_these} label. A tool is selected iff its score is at least
 * {@code threshold} AND beats the {@code none_of_these} score. A failed
 * classify call fails OPEN: that batch's tools are all included.
 *
 * <p>{@code always_include} semantics: those names are unioned into the
 * shortlist ONLY when the shortlist is non-empty. When ALL batches elected
 * {@code none_of_these} (a purely conversational turn), the shortlist stays
 * EMPTY and {@code always_include} is NOT added — the infer step then injects
 * no tools at all.
 *
 * <p>Linear, non-streaming step: writes
 * {@link PipelineContext#KEY_SELECTED_TOOLS} and follows {@code next_step}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ToolSelectStepExecutor
  extends ModelBoundStepExecutor<ToolSelectStepConfig, ClassifierEngine> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolSelectStepExecutor.class);

  /** GLiNER2's 512-token window must fit input + labels — cap the input text. */
  static final int MAX_INPUT_CHARS = 1500;

  static final int DEFAULT_BATCH_SIZE = 4;
  static final float DEFAULT_THRESHOLD = 0.3f;
  static final int MAX_LABEL_DESC_CHARS = 160;

  static final String NONE_LABEL = "none_of_these";
  static final String NONE_DESCRIPTION = "The user request does not require any of these tools.";

  private final JinjaRenderer jinjaRenderer;

  public ToolSelectStepExecutor(StepExecutionContext execContext, JinjaRenderer jinjaRenderer) {
    super(execContext);
    this.jinjaRenderer = jinjaRenderer;
  }

  @Override
  public ToolSelectStepConfig extractConfig(PipelineStep step) {
    return step.getToolSelectConfig();
  }

  @Override
  protected String getModelId(ToolSelectStepConfig config) {
    return config.getModelId();
  }

  @Override
  protected Class<ClassifierEngine> engineType() {
    return ClassifierEngine.class;
  }

  @Override
  protected Maybe<String> rxExecuteWithEngine(
    String stepId,
    ToolSelectStepConfig cfg,
    ClassifierEngine engine,
    StepContext ctx
  ) {
    PipelineContext pctx = ctx.pipelineContext();
    List<ToolDefinition> tools = pctx.tools();
    if (tools == null || tools.isEmpty()) {
      LOGGER.debug("ToolSelectStep '{}': no tools on the request — nothing to select", stepId);
      return ctx.rxNextStep(stepId);
    }

    String text = resolveInput(cfg, pctx);
    if (text == null || text.isBlank()) {
      LOGGER.debug("ToolSelectStep '{}': empty input text — skipping selection", stepId);
      return ctx.rxNextStep(stepId);
    }
    final String input = truncate(text, MAX_INPUT_CHARS);

    int batchSize = cfg.getBatchSize() > 0 ? cfg.getBatchSize() : DEFAULT_BATCH_SIZE;
    float threshold = cfg.getThreshold() > 0 ? cfg.getThreshold() : DEFAULT_THRESHOLD;

    List<List<ToolDefinition>> batches = partition(tools, batchSize);

    return Flowable.fromIterable(batches)
      .concatMapSingle(batch -> classifyBatch(stepId, cfg, engine, input, batch, threshold))
      .toList()
      .flatMapMaybe(perBatch -> {
        LinkedHashSet<String> shortlist = new LinkedHashSet<>();
        perBatch.forEach(shortlist::addAll);

        // always_include is only unioned into a NON-EMPTY shortlist: when every
        // batch elected none_of_these the turn is conversational — inject nothing.
        if (!shortlist.isEmpty()) {
          for (String name : cfg.getAlwaysIncludeList()) {
            if (tools.stream().anyMatch(t -> t.getName().equals(name))) {
              shortlist.add(name);
            }
          }
        }

        List<String> selected = List.copyOf(shortlist);
        pctx.setSelectedTools(selected);
        if (cfg.getTrimDescriptions()) {
          pctx.setCondensedToolDescriptions(condenseSelectedDescriptions(cfg, tools, selected));
        }
        LOGGER.info(
          "tool_select: {}/{} tools selected {} (batches={})",
          selected.size(),
          tools.size(),
          selected,
          batches.size()
        );
        return ctx.rxNextStep(stepId);
      });
  }

  // ---------------------------------------------------------------------------
  // Per-batch classification
  // ---------------------------------------------------------------------------

  private Single<List<String>> classifyBatch(
    String stepId,
    ToolSelectStepConfig cfg,
    ClassifierEngine engine,
    String input,
    List<ToolDefinition> batch,
    float threshold
  ) {
    List<ClassifyLabel> labels = new ArrayList<>(batch.size() + 1);
    for (ToolDefinition tool : batch) {
      labels.add(new ClassifyLabel(tool.getName(), condenseDescription(cfg, tool)));
    }
    labels.add(new ClassifyLabel(NONE_LABEL, NONE_DESCRIPTION));

    List<String> batchNames = batch.stream().map(ToolDefinition::getName).toList();

    return engine
      .rxClassify(new ClassifyRequest(input), labels)
      .map(resp -> {
        Map<String, Float> scores = resp.allScores();
        float noneScore = scores != null ? scores.getOrDefault(NONE_LABEL, 0f) : 0f;
        List<String> selected = new ArrayList<>();
        for (String name : batchNames) {
          float score = scores != null ? scores.getOrDefault(name, 0f) : 0f;
          if (score >= threshold && score > noneScore) {
            selected.add(name);
          }
        }
        return selected;
      })
      // Fail OPEN: on classify failure the batch's tools are all included so a
      // flaky classifier never hides tools from the model.
      .onErrorReturn(err -> {
        LOGGER.warn(
          "ToolSelectStep '{}': classify call failed for batch {} — failing open: {}",
          stepId,
          batchNames,
          err.toString()
        );
        return batchNames;
      });
  }

  // ---------------------------------------------------------------------------
  // Input & label helpers
  // ---------------------------------------------------------------------------

  /**
   * Input text: {@code input_field} from context when set, else the LAST USER
   * message from the conversation, falling back to the flat prompt.
   */
  private static String resolveInput(ToolSelectStepConfig cfg, PipelineContext pctx) {
    if (!cfg.getInputField().isBlank()) {
      return pctx.get(cfg.getInputField());
    }
    if (pctx.messages() != null) {
      var last = ChatTurn.lastUserContent(pctx.messages());
      if (last.isPresent() && !last.get().isBlank()) return last.get();
    }
    return pctx.get(PipelineContext.KEY_PROMPT);
  }

  /**
   * Condenses a tool description into a short classifier label description:
   * either through the configured Jinja2 {@code label_template} (context:
   * {@code tool} map with name/description), or the built-in default — the
   * first sentence of the description, trimmed and capped at 160 chars.
   */
  String condenseDescription(ToolSelectStepConfig cfg, ToolDefinition tool) {
    if (!cfg.getLabelTemplate().isBlank()) {
      try {
        return jinjaRenderer
          .render(
            cfg.getLabelTemplate(),
            "<tool_select>",
            Map.of("tool", Map.of("name", tool.getName(), "description", tool.getDescription()))
          )
          .strip();
      } catch (RuntimeException e) {
        LOGGER.warn(
          "tool_select: label_template render failed for tool '{}' — using default: {}",
          tool.getName(),
          e.toString()
        );
      }
    }
    return defaultCondense(tool.getDescription());
  }

  /**
   * Builds the condensed injection descriptions for the SELECTED tools:
   * {@code description_template} (Jinja2, context {@code tool} map with
   * name/description) when set, else the built-in default condenser. An empty
   * shortlist yields an empty map (no tools injected anyway).
   */
  Map<String, String> condenseSelectedDescriptions(
    ToolSelectStepConfig cfg,
    List<ToolDefinition> tools,
    List<String> selected
  ) {
    Map<String, String> condensed = new java.util.LinkedHashMap<>();
    for (ToolDefinition tool : tools) {
      if (!selected.contains(tool.getName())) continue;
      condensed.put(tool.getName(), condenseForInjection(cfg, tool));
    }
    return condensed;
  }

  /**
   * Condenses a tool description for prompt injection: either through the
   * configured Jinja2 {@code description_template} (context: {@code tool} map
   * with name/description), or the built-in default condenser when blank.
   */
  String condenseForInjection(ToolSelectStepConfig cfg, ToolDefinition tool) {
    if (!cfg.getDescriptionTemplate().isBlank()) {
      try {
        return jinjaRenderer
          .render(
            cfg.getDescriptionTemplate(),
            "<tool_select>",
            Map.of("tool", Map.of("name", tool.getName(), "description", tool.getDescription()))
          )
          .strip();
      } catch (RuntimeException e) {
        LOGGER.warn(
          "tool_select: description_template render failed for tool '{}' — using default: {}",
          tool.getName(),
          e.toString()
        );
      }
    }
    return defaultCondense(tool.getDescription());
  }

  /** Default condensing: first sentence (split on ". " / newline), capped at 160 chars. */
  static String defaultCondense(String description) {
    if (description == null) return "";
    String s = description.strip();
    int nl = s.indexOf('\n');
    if (nl >= 0) s = s.substring(0, nl);
    int dot = s.indexOf(". ");
    if (dot >= 0) s = s.substring(0, dot + 1);
    s = s.strip();
    return truncate(s, MAX_LABEL_DESC_CHARS);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static List<List<ToolDefinition>> partition(List<ToolDefinition> tools, int size) {
    List<List<ToolDefinition>> batches = new ArrayList<>();
    for (int i = 0; i < tools.size(); i += size) {
      batches.add(tools.subList(i, Math.min(i + size, tools.size())));
    }
    return batches;
  }
}
