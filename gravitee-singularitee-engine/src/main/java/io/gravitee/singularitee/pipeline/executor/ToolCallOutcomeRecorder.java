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

import io.gravitee.singularitee.engine.tools.ToolCallExtractor;
import io.gravitee.singularitee.engine.tools.ToolMarkerResidues;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.FinishReason;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.ToolCall;
import io.gravitee.singularitee.protocol.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Records the outcome of an INFER step's tool-call attempt into the pipeline
 * context: runs template-driven extraction over the captured tool span (or the
 * step output), publishes the tri-state {@code tool_parse_failed} /
 * {@code tool_parse_ok} signal fields that drive repair loops, flags leaked
 * tool-marker residue, and handles markerless dialects. Extraction is
 * fail-open — the raw text has already been forwarded/stored, so a failed
 * parse never fails the step.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
final class ToolCallOutcomeRecorder {

  private static final String DEFAULT_TOOL_OPEN_TAG = "<tool_call>";
  private static final String DEFAULT_TOOL_CLOSE_TAG = "</tool_call>";

  private static final Pattern LEADING_IDENTIFIER = Pattern.compile("^\\s*([\\w.-]+)");

  private ToolCallOutcomeRecorder() {}

  /**
   * Template-driven tool-call extraction: when the engine stamped a tool span (bare
   * TOOL-channel payload) or reported a tool_calls finish, render the step's extraction
   * template (or the built-in dialect templates in order) over the span and surface the
   * structured calls on the final ResponseCompleted event. Failure to extract is fail-open:
   * the raw text has already been forwarded/stored, clients treat it as plain content.
   *
   * @param metrics the metrics recorder, or {@code null} to disable metrics
   */
  static void recordOutcome(
    PipelineContext pctx,
    String stepId,
    InferStepConfig cfg,
    String stepOutput,
    String bareToolSpan,
    InferenceMetrics metrics
  ) {
    boolean toolCandidate =
      !bareToolSpan.isEmpty() ||
      pctx.lastEngineFinishReason() == FinishReason.FINISH_REASON_TOOL_CALLS;
    if (toolCandidate) {
      String extractionInput = !bareToolSpan.isEmpty() ? bareToolSpan : stepOutput;
      var extraction = ToolCallExtractor.extractResult(
        extractionInput,
        toolsData(pctx),
        cfg.getToolExtractionTemplate()
      );
      pctx.setExtractedToolCalls(toWireToolCalls(extraction.calls()));
      setToolSignalFields(pctx, stepId, extraction);
      // Tri-state disambiguation: tool_parse_failed is "true" ONLY when the model
      // attempted a call (span captured / tool_calls finish) and extraction came up
      // empty. A plain prose answer never attempts one and must not trip repair loops.
      pctx.set(stepId + ".tool_parse_failed", Boolean.toString(extraction.calls().isEmpty()));
      if (extraction.calls().isEmpty()) {
        // Name the offender: when the failure is an undeclared tool (a
        // hallucinated built-in), a repair loop can only converge if the
        // loopback message can tell the model WHICH name was wrong.
        recordAttemptedTool(pctx, stepId, extractionInput, metrics, cfg.getModelId());
      }
    } else if (hasToolMarkerResidue(stepOutput, cfg)) {
      // The model hallucinated tool-call syntax the tag machine could not
      // recognize — a form with no legal shape, so enumerating tag variants
      // cannot cover it. Leaked special tokens are never a valid answer:
      // flag a failed attempt so a heal/repair loop retries instead of the
      // raw markers leaking to the client as prose.
      pctx.set(stepId + ".tool_parse_failed", "true");
      pctx.set(stepId + ".parse_error", "unrecognized tool-call markers in output");
      recordAttemptedTool(pctx, stepId, stepOutput, metrics, cfg.getModelId());
    } else {
      pctx.set(stepId + ".tool_parse_failed", "false");
      maybeExtractMarkerlessToolCalls(pctx, stepId, cfg, stepOutput);
    }
  }

  private static void recordAttemptedTool(
    PipelineContext pctx,
    String stepId,
    String span,
    InferenceMetrics metrics,
    String modelId
  ) {
    String attempted = attemptedToolName(span);
    if (!attempted.isEmpty()) {
      pctx.set(stepId + ".attempted_tool", attempted);
    }
    if (metrics != null) {
      metrics.recordFailureSignal(modelId, "tool_parse_failed");
    }
  }

  /**
   * Narration must be pure prose: a mutated call span with no recognizable
   * open marker can share the answer channel with the model's words. Cut at
   * the first dialect special token and strip a trailing tool-name token
   * left behind.
   */
  static String sanitizeNarration(String answer, PipelineContext pctx) {
    if (answer == null) {
      return "";
    }
    String clean = answer;
    int marker = clean.indexOf("<|");
    if (marker >= 0) {
      clean = clean.substring(0, marker);
    }
    clean = clean.strip();
    for (ToolDefinition tool : pctx.tools()) {
      if (clean.endsWith(tool.getName())) {
        clean = clean.substring(0, clean.length() - tool.getName().length()).strip();
      }
    }
    for (ToolDefinition tool : pctx.serverTools()) {
      if (clean.endsWith(tool.getName())) {
        clean = clean.substring(0, clean.length() - tool.getName().length()).strip();
      }
    }
    return clean;
  }

  /**
   * Whether an UNCAPTURED generation contains tool-call marker debris. Only
   * meaningful for marker-based dialects (a configured {@code tool_open}).
   * Marker derivation is dialect-owned — see {@link ToolMarkerResidues}:
   * leaked dialect machinery in the final text means the model attempted a
   * call in a form the tags did not recognize, never a valid answer.
   */
  static boolean hasToolMarkerResidue(String stepOutput, InferStepConfig cfg) {
    boolean markerBased = cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank();
    return (
      markerBased &&
      ToolMarkerResidues.forTemplate(cfg.getToolExtractionTemplate()).isPresent(
        stepOutput,
        cfg.getToolCallTags()
      )
    );
  }

  /**
   * Markerless tool dialects (whole-message {@code name\n{json}} calls) never produce a tool
   * span or a {@code tool_calls} finish. When the request declared tools, the engine finished
   * with a plain {@code stop}, and the step explicitly configures an extraction template,
   * attempt extraction on the step's final output text: a non-empty result turns the response
   * into tool calls exactly as a captured span would (finish {@code tool_calls}); an empty
   * result leaves the response untouched. Built-ins are never tried speculatively here — only
   * an explicit template opts a step in.
   *
   * <p>A configured {@code tool_open} tag disqualifies the step outright. Having an explicit
   * extraction template does not make a dialect markerless — a marker-based dialect may declare
   * one because its captured span needs custom parsing. For a marker-based dialect the absence
   * of a span IS the answer: the model made no call. Running markerless extraction over its
   * prose instead lets a name-shaped regex manufacture a call from the first word of ordinary
   * text, and because a non-empty result nulls {@code content} downstream, that phantom
   * silently replaces the model's actual answer.
   */
  static void maybeExtractMarkerlessToolCalls(
    PipelineContext pctx,
    String stepId,
    InferStepConfig cfg,
    String stepOutput
  ) {
    boolean markerBased = cfg.hasToolCallTags() && !cfg.getToolCallTags().getOpenTag().isBlank();
    // Server-owned tools (the todo tools) count: a todo pipeline with no
    // caller-declared tools still expects markerless set_todos/complete_todo
    // calls to be extracted.
    boolean anyTools = !pctx.tools().isEmpty() || !pctx.serverTools().isEmpty();
    if (
      !anyTools ||
      cfg.getToolExtractionTemplate().isBlank() ||
      markerBased ||
      pctx.lastEngineFinishReason() != FinishReason.FINISH_REASON_STOP
    ) {
      return;
    }
    var extraction = ToolCallExtractor.extractResult(
      stepOutput,
      toolsData(pctx),
      cfg.getToolExtractionTemplate()
    );
    setToolSignalFields(pctx, stepId, extraction);
    if (!extraction.calls().isEmpty()) {
      pctx.setExtractedToolCalls(toWireToolCalls(extraction.calls()));
      pctx.setLastEngineFinishReason(FinishReason.FINISH_REASON_TOOL_CALLS);
      pctx.set(stepId + ".finish_reason", "tool_calls");
    }
  }

  /**
   * Publishes the outcome of a tool-call extraction attempt as pipeline context fields, so
   * {@code loop}/{@code break} conditions and {@code loopback_message} templates can drive a
   * repair loop off a malformed call — the extraction itself stays fail-open.
   */
  static void setToolSignalFields(
    PipelineContext pctx,
    String stepId,
    ToolCallExtractor.ExtractionResult extraction
  ) {
    pctx.set(stepId + ".tool_parse_ok", Boolean.toString(!extraction.calls().isEmpty()));
    pctx.set(stepId + ".tool_call_count", Integer.toString(extraction.calls().size()));
    if (extraction.error() != null) {
      pctx.set(stepId + ".parse_error", extraction.error());
    }
  }

  /**
   * The tool name the model tried to call: the leading identifier of the captured span
   * (most dialects start the span with the function name), minus a
   * {@code functions.} namespace prefix. Empty when no identifier leads the span.
   */
  static String attemptedToolName(String span) {
    if (span == null) {
      return "";
    }
    var m = LEADING_IDENTIFIER.matcher(span);
    if (!m.find()) {
      return "";
    }
    String name = m.group(1);
    return name.startsWith("functions.") ? name.substring("functions.".length()) : name;
  }

  /**
   * Appends the bare TOOL-channel payload (captured separately when the engine
   * suppresses tag markers) to the step's text output, re-wrapped in the step's
   * configured tool tags ({@code tool_call_tags}, defaulting to
   * {@code <tool_call>...</tool_call>}). This keeps the step output / appended
   * assistant turn identical to what tag-emitting engines produce, so chat
   * templates re-render prior tool calls correctly on later turns of a tool loop.
   * Empty tool payload (legacy tagged-text engines) returns {@code text} untouched.
   */
  static String withReWrappedToolCalls(String text, String toolPayload, InferStepConfig cfg) {
    if (toolPayload == null || toolPayload.isEmpty()) {
      return text;
    }
    String open = DEFAULT_TOOL_OPEN_TAG;
    String close = DEFAULT_TOOL_CLOSE_TAG;
    if (cfg != null && cfg.hasToolCallTags()) {
      var t = cfg.getToolCallTags();
      if (!t.getOpenTag().isBlank()) {
        open = t.getOpenTag();
        close = t.getCloseTag();
      }
    }
    StringBuilder sb = new StringBuilder(text);
    if (!text.isEmpty() && !text.endsWith("\n")) {
      sb.append('\n');
    }
    sb.append(open).append(toolPayload).append(close);
    return sb.toString();
  }

  /** The request's tools as template data (name/description) for extraction templates. */
  private static List<Map<String, Object>> toolsData(PipelineContext pctx) {
    // Request tools PLUS server-owned tools (todo tools): extraction templates
    // validate the called name against this list (phantom-call guard), so a
    // server tool missing here would be rejected as a hallucination.
    return Stream.concat(
      pctx.tools().stream(),
      PromptAssembler.undeclaredServerTools(pctx).stream()
    )
      .map(t -> Map.<String, Object>of("name", t.getName(), "description", t.getDescription()))
      .toList();
  }

  /** Maps extracted calls to wire {@link ToolCall}s. */
  private static List<ToolCall> toWireToolCalls(
    List<ToolCallExtractor.ExtractedToolCall> extracted
  ) {
    return extracted
      .stream()
      .map(c ->
        ToolCall.newBuilder()
          // The id is born HERE, server-side: the client answers with this id
          // in function_call_output, and the stored conversation must replay
          // the call under the SAME id — a differing id pairs the tool result
          // with the wrong call on the next turn.
          .setId("call_" + UUID.randomUUID().toString().replace("-", ""))
          .setName(c.name())
          .setArgumentsJson(c.argumentsJson())
          .addAllCoercibleArgs(c.coercibleArgs())
          .build()
      )
      .toList();
  }
}
