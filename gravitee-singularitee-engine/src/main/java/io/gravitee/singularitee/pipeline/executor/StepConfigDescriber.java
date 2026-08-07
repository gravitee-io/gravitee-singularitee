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

import com.google.protobuf.TextFormat;
import io.gravitee.singularitee.protocol.BreakStepConfig;
import io.gravitee.singularitee.protocol.ClassifyStepConfig;
import io.gravitee.singularitee.protocol.EmbedStepConfig;
import io.gravitee.singularitee.protocol.GuardStepConfig;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.LlmGuardStepConfig;
import io.gravitee.singularitee.protocol.LoopStepConfig;
import io.gravitee.singularitee.protocol.RouteStepConfig;
import io.gravitee.singularitee.protocol.SubPipelineStepConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces human-readable summaries of step configuration objects for
 * observability. Two granularities:
 *
 * <ul>
 *   <li>{@link #describe(Object)}: one-line, stripped-down summary with the
 *       identifying fields only (model_id, action, output_field, sizes).
 *       Safe for DEBUG — won't dump multi-KB raw templates.</li>
 *   <li>{@link #describeFull(Object)}: complete protobuf TextFormat dump
 *       with a single-line compaction, suitable for TRACE — shows every
 *       field including raw_template bodies and message lists.</li>
 * </ul>
 *
 * <p>Unknown config types fall back to {@code toString()}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class StepConfigDescriber {

  private static final Logger LOGGER = LoggerFactory.getLogger(StepConfigDescriber.class);

  private StepConfigDescriber() {}

  /**
   * Returns a compact one-line summary of a step config. Truncates long text
   * fields, reports collection sizes rather than contents.
   */
  static String describe(Object config) {
    if (config == null) return "null";
    try {
      return switch (config) {
        case InferStepConfig c -> describeInfer(c);
        case LlmGuardStepConfig c -> describeLlmGuard(c);
        case GuardStepConfig c -> describeGuard(c);
        case RouteStepConfig c -> describeRoute(c);
        case ClassifyStepConfig c -> describeClassify(c);
        case EmbedStepConfig c -> describeEmbed(c);
        case LoopStepConfig c -> describeLoop(c);
        case BreakStepConfig c -> describeBreak(c);
        case SubPipelineStepConfig c -> describeSubPipeline(c);
        default -> config.getClass().getSimpleName() + "{…}";
      };
    } catch (RuntimeException e) {
      LOGGER.debug(
        "Failed to describe config {}: {}",
        config.getClass().getSimpleName(),
        e.getMessage()
      );
      return config.getClass().getSimpleName() + "{?}";
    }
  }

  /**
   * Returns the complete protobuf field set as a single-line TextFormat dump.
   * Used at TRACE level when you need to see every configured value including
   * raw templates and message bodies.
   */
  static String describeFull(Object config) {
    if (config == null) return "null";
    if (config instanceof com.google.protobuf.Message msg) {
      return TextFormat.printer().shortDebugString(msg);
    }
    return config.toString();
  }

  // ---------------------------------------------------------------------------
  // Per-type compact describers
  // ---------------------------------------------------------------------------

  private static String describeInfer(InferStepConfig c) {
    StringBuilder sb = new StringBuilder("InferStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    if (!c.getRawTemplate().isBlank()) appendKv(sb, "raw_template", sizeHint(c.getRawTemplate()));
    if (c.getMessagesCount() > 0) appendKv(sb, "messages", c.getMessagesCount() + " defs");
    if (c.hasSamplingParams()) appendKv(sb, "sampling", "overridden");
    if (c.hasReasoningTags()) {
      appendKv(
        sb,
        "reasoning_tags",
        c.getReasoningTags().getOpenTag() + "/" + c.getReasoningTags().getCloseTag()
      );
    }
    if (c.hasToolCallTags()) {
      appendKv(
        sb,
        "tool_call_tags",
        c.getToolCallTags().getOpenTag() + "/" + c.getToolCallTags().getCloseTag()
      );
    }
    if (c.hasContext()) appendKv(
      sb,
      "context_keys",
      c.getContext().getFieldsMap().keySet().toString()
    );
    return sb.append('}').toString();
  }

  private static String describeLlmGuard(LlmGuardStepConfig c) {
    StringBuilder sb = new StringBuilder("LlmGuardStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    appendKv(sb, "action", c.getAction().name());
    if (!c.getSafeToken().isBlank()) appendKv(sb, "safe_token", "\"" + c.getSafeToken() + "\"");
    if (!c.getRawTemplate().isBlank()) appendKv(sb, "raw_template", sizeHint(c.getRawTemplate()));
    if (c.getMessagesCount() > 0) appendKv(sb, "messages", c.getMessagesCount() + " defs");
    if (c.hasSamplingParams()) appendKv(sb, "sampling", "overridden");
    if (c.hasContext()) appendKv(
      sb,
      "context_keys",
      c.getContext().getFieldsMap().keySet().toString()
    );
    if (c.hasMessage()) appendKv(sb, "reject_message", "set");
    return sb.append('}').toString();
  }

  private static String describeGuard(GuardStepConfig c) {
    StringBuilder sb = new StringBuilder("GuardStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    appendKv(sb, "action", c.getAction().name());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    if (c.getTriggersCount() > 0) {
      appendKv(sb, "triggers", c.getTriggersCount() + "");
    } else if (!c.getTriggerLabel().isBlank()) {
      appendKv(sb, "trigger", c.getTriggerLabel() + "≥" + c.getTriggerScore());
    }
    if (c.getRedactWithEntityType()) appendKv(sb, "redact_with_entity_type", "true");
    return sb.append('}').toString();
  }

  private static String describeRoute(RouteStepConfig c) {
    StringBuilder sb = new StringBuilder("RouteStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    appendKv(sb, "strategy", c.getStrategy().name());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    appendKv(sb, "rules", c.getRulesCount() + "");
    if (!c.getDefaultStepId().isBlank()) appendKv(sb, "default", c.getDefaultStepId());
    return sb.append('}').toString();
  }

  private static String describeClassify(ClassifyStepConfig c) {
    StringBuilder sb = new StringBuilder("ClassifyStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    if (c.getThreshold() > 0) appendKv(sb, "threshold", Float.toString(c.getThreshold()));
    return sb.append('}').toString();
  }

  private static String describeEmbed(EmbedStepConfig c) {
    StringBuilder sb = new StringBuilder("EmbedStepConfig{");
    appendKv(sb, "model_id", c.getModelId());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    return sb.append('}').toString();
  }

  private static String describeLoop(LoopStepConfig c) {
    StringBuilder sb = new StringBuilder("LoopStepConfig{");
    if (!c.getTargetStepId().isBlank()) appendKv(sb, "target", c.getTargetStepId());
    if (!c.getNextStepId().isBlank()) appendKv(sb, "next", c.getNextStepId());
    appendKv(sb, "condition", c.getCondition().name());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    if (c.getMaxIterations() > 0) appendKv(
      sb,
      "max_iterations",
      Integer.toString(c.getMaxIterations())
    );
    return sb.append('}').toString();
  }

  private static String describeBreak(BreakStepConfig c) {
    StringBuilder sb = new StringBuilder("BreakStepConfig{");
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    appendKv(sb, "condition", c.getCondition().name());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    return sb.append('}').toString();
  }

  private static String describeSubPipeline(SubPipelineStepConfig c) {
    StringBuilder sb = new StringBuilder("SubPipelineStepConfig{");
    appendKv(sb, "pipeline_id", c.getPipelineId());
    if (!c.getInputField().isBlank()) appendKv(sb, "input_field", c.getInputField());
    if (!c.getOutputField().isBlank()) appendKv(sb, "output_field", c.getOutputField());
    if (!c.getRemoteId().isBlank()) appendKv(sb, "remote", c.getRemoteId());
    if (c.getForwardMessages()) appendKv(sb, "forward_messages", "true");
    return sb.append('}').toString();
  }

  // ---------------------------------------------------------------------------
  // Formatting helpers
  // ---------------------------------------------------------------------------

  private static void appendKv(StringBuilder sb, String key, String value) {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(", ");
    sb.append(key).append('=').append(value);
  }

  private static String sizeHint(String s) {
    if (s == null) return "null";
    int len = s.length();
    if (len < 1024) return len + " chars";
    return String.format("%.1fKB", len / 1024.0);
  }
}
