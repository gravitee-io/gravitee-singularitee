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

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.pipeline.PipelineContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared Jinja2 rendering context builder for step executors.
 *
 * <p>Centralises the context composition that every executor needs when
 * resolving Jinja2 templates in step configuration (prompts, guard messages,
 * raw templates). Before this helper existed, three executors
 * ({@link InferStepExecutor}, {@link LlmGuardStepExecutor},
 * {@link GuardStepExecutor}) each copy-pasted their own partial version
 * of the same logic, with predictable drift: the LLM guard was missing
 * {@code messages} and the per-step {@code context:} Struct overlay,
 * causing Qwen3Guard templates to render empty prompts.
 *
 * <p>The standard Jinja2 context exposes:
 * <ul>
 *   <li>{@code prompt} — the raw user input (from {@link PipelineContext#KEY_PROMPT})</li>
 *   <li>{@code system} — the system message from the original request, if any</li>
 *   <li>{@code history} — the full conversation formatted as "role: content" lines</li>
 *   <li>{@code messages} — the original request turns as {@code [{role, content}]}</li>
 *   <li>{@code generated_messages} — assistant outputs from pipeline steps, in
 *       execution order, as {@code [{role, content, step}]}. Preserves every
 *       CoT loop iteration (unlike {@code step_id.output} which keeps only
 *       the latest).</li>
 *   <li>{@code verdicts} — guard verdicts, in execution order, as
 *       {@code [{verdict, details, step}]}. Kept separate from
 *       {@code generated_messages} so templates can distinguish
 *       conversation content from safety metadata.</li>
 *   <li>{@code step_id.field} — nested maps of every step output key
 *       (e.g. {@code generate.output}, {@code input_guard.verdict}).
 *       These are the latest values — a CoT loop that reruns a step
 *       overwrites the entry on each iteration.</li>
 * </ul>
 *
 * <p>Executors layer additional variables on top of this base as needed
 * (e.g. {@code bos_token}/{@code eos_token} and {@code tools} for
 * {@link InferStepExecutor}).
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class JinjaContextHelper {

  private static final Logger LOGGER = LoggerFactory.getLogger(JinjaContextHelper.class);

  private JinjaContextHelper() {}

  /**
   * Builds the common Jinja2 context variables derived from the pipeline
   * context: prompt, system, history, messages, generated_messages, verdicts,
   * and the nested {@code step_id.field} step-output maps.
   */
  public static Map<String, Object> buildBaseContext(PipelineContext pctx) {
    Map<String, Object> ctx = new LinkedHashMap<>();

    ctx.put("prompt", pctx.get(PipelineContext.KEY_PROMPT));
    ctx.put("system", resolveSystemFromMessages(pctx));
    ctx.put("history", resolveHistoryFromMessages(pctx));
    ctx.put("messages", buildMessages(pctx));
    ctx.put("generated_messages", buildGeneratedMessages(pctx));
    ctx.put("verdicts", buildVerdicts(pctx));

    buildStepOutputContext(pctx, ctx);

    return ctx;
  }

  /**
   * Returns a pretty-printed view of a rendering context map, truncating long
   * string values for log readability. Nested maps and lists are shown inline.
   */
  public static String dump(Map<String, Object> ctx, int maxValueChars) {
    StringBuilder sb = new StringBuilder(128);
    sb.append("{\n");
    for (var e : ctx.entrySet()) {
      sb.append("  ").append(e.getKey()).append(" = ");
      appendValue(sb, e.getValue(), maxValueChars);
      sb.append('\n');
    }
    sb.append('}');
    return sb.toString();
  }

  private static void appendValue(StringBuilder sb, Object v, int maxValueChars) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> sb.append('"').append(truncate(s, maxValueChars)).append('"');
      case List<?> l -> sb.append('[').append(l.size()).append(" items]");
      case Map<?, ?> m -> sb
        .append('{')
        .append(m.size())
        .append(" keys: ")
        .append(m.keySet())
        .append('}');
      default -> sb.append(truncate(String.valueOf(v), maxValueChars));
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "null";
    if (s.length() <= max) return s;
    return s.substring(0, max) + "…(+" + (s.length() - max) + " chars)";
  }

  /**
   * Returns the conversation turns as a list of Jinja-shaped maps
   * ({@code [{role, content}, ...]}), synthesized from
   * {@link PipelineContext#messages()} or — if no structured messages were
   * provided — from a bare prompt as a single user turn.
   */
  public static List<Map<String, Object>> buildMessages(PipelineContext pctx) {
    if (pctx.messages() != null && !pctx.messages().isEmpty()) {
      return pctx
        .messages()
        .stream()
        .map(t ->
          Map.<String, Object>of(
            "role",
            t.role().name().toLowerCase(),
            "content",
            t.content() != null ? t.content() : ""
          )
        )
        .toList();
    }
    String bare = pctx.get(PipelineContext.KEY_PROMPT);
    return List.of(Map.of("role", "user", "content", bare != null ? bare : ""));
  }

  /**
   * Returns every assistant output produced by pipeline steps so far, in
   * execution order. Each entry exposes its originating step id so templates
   * can identify which step produced which response (e.g. to render a CoT
   * reasoning chain with labels).
   *
   * <p>Thinking blocks ({@code <think>…</think>}) are stripped from the
   * content before adding it to the context. Reasoning tokens are internal
   * model state and must never leak into downstream template variables —
   * regardless of whether the originating step had {@code strip_thinking}
   * enabled. This is a framework-level guarantee: {@code generated_messages}
   * always exposes the clean answer, not the raw output.
   */
  public static List<Map<String, Object>> buildGeneratedMessages(PipelineContext pctx) {
    List<Map<String, Object>> out = new ArrayList<>(pctx.generatedMessages().size());
    for (var m : pctx.generatedMessages()) {
      out.add(
        Map.of(
          "role",
          "assistant",
          "content",
          stripThinking(m.content() != null ? m.content() : ""),
          "step",
          m.stepId()
        )
      );
    }
    return out;
  }

  /**
   * Strips all {@code <think>…</think>} blocks from the given text and trims
   * any leading/trailing whitespace that remains. Uses a simple iterative
   * approach (no regex backtracking) that is safe for arbitrarily large outputs.
   *
   * <p>This is applied to every entry in {@link #buildGeneratedMessages} so
   * that thinking tokens never escape into the Jinja2 rendering context
   * regardless of whether the originating step had {@code strip_thinking} set.
   */
  static String stripThinking(String text) {
    if (text == null || text.isEmpty()) return text;
    final String open = "<think>";
    final String close = "</think>";
    StringBuilder sb = new StringBuilder(text.length());
    int pos = 0;
    while (pos < text.length()) {
      int openIdx = text.indexOf(open, pos);
      if (openIdx < 0) {
        sb.append(text, pos, text.length());
        break;
      }
      sb.append(text, pos, openIdx);
      int closeIdx = text.indexOf(close, openIdx + open.length());
      if (closeIdx < 0) {
        // Unclosed <think> — drop everything from here to end.
        break;
      }
      pos = closeIdx + close.length();
    }
    return sb.toString().strip();
  }

  /**
   * Returns every guard verdict produced by the pipeline so far, in execution
   * order. {@code details} is the full verdict text for LLM guards or a
   * decimal score for classifier guards.
   */
  public static List<Map<String, Object>> buildVerdicts(PipelineContext pctx) {
    List<Map<String, Object>> out = new ArrayList<>(pctx.verdicts().size());
    for (var v : pctx.verdicts()) {
      out.add(
        Map.of(
          "verdict",
          v.verdict(),
          "details",
          v.details() != null ? v.details() : "",
          "step",
          v.stepId()
        )
      );
    }
    return out;
  }

  /**
   * Populates the {@code step_id.field} step-output maps as nested values
   * in the given context (e.g. {@code "generate.output"} → {@code generate = { output: "..." }}).
   * Step ids are used as-is — the YAML loader validates that they are legal
   * Jinja2 identifiers.
   */
  public static void buildStepOutputContext(PipelineContext pctx, Map<String, Object> ctx) {
    Map<String, Map<String, Object>> stepMaps = new LinkedHashMap<>();
    for (var entry : pctx.snapshot().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();

      if (
        key.equals(PipelineContext.KEY_PROMPT) ||
        key.equals("tools") ||
        key.equals("system") ||
        key.equals("history") ||
        key.startsWith("__")
      ) {
        continue;
      }

      int dotIdx = key.indexOf('.');
      if (dotIdx > 0) {
        String stepId = key.substring(0, dotIdx);
        String field = key.substring(dotIdx + 1);
        stepMaps.computeIfAbsent(stepId, k -> new LinkedHashMap<>()).put(field, value);
      }
    }
    ctx.putAll(stepMaps);
  }

  /**
   * Overlays a per-step {@code context:} Struct (from {@code InferStepConfig}
   * or {@code LlmGuardStepConfig}) onto an existing Jinja context map.
   *
   * <p>Warns loudly when a value arrives as the <em>string</em> {@code "true"}
   * or {@code "false"}: chat templates test such flags with the strict
   * boolean-identity {@code is true}/{@code is false} Jinja2 tests (e.g.
   * Qwen3's {@code enable_thinking is false} guard for the no-thinking
   * prefill), so a quoted YAML boolean silently disables the behavior with
   * no error anywhere else in the chain.
   */
  public static void mergeStepContext(Map<String, Object> ctx, Struct struct) {
    if (struct == null || struct.getFieldsCount() == 0) return;
    Map<String, Object> merged = structToMap(struct);
    for (var e : merged.entrySet()) {
      if (
        e.getValue() instanceof String s &&
        ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))
      ) {
        LOGGER.warn(
          "Step context variable '{}' is the STRING \"{}\", not a boolean — " +
            "Jinja2 'is true'/'is false' tests will not match it (e.g. Qwen3's " +
            "enable_thinking guard). Unquote the value in the workspace YAML.",
          e.getKey(),
          s
        );
      }
    }
    ctx.putAll(merged);
  }

  /**
   * Converts a protobuf {@link Struct} to a Java {@link Map}, recursively
   * handling nested structs and lists. Preserves field declaration order.
   */
  public static Map<String, Object> structToMap(Struct struct) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : struct.getFieldsMap().entrySet()) {
      result.put(entry.getKey(), protoValueToJava(entry.getValue()));
    }
    return result;
  }

  private static Object protoValueToJava(Value value) {
    return switch (value.getKindCase()) {
      case STRING_VALUE -> value.getStringValue();
      case NUMBER_VALUE -> value.getNumberValue();
      case BOOL_VALUE -> value.getBoolValue();
      case NULL_VALUE -> null;
      case STRUCT_VALUE -> structToMap(value.getStructValue());
      case LIST_VALUE -> value
        .getListValue()
        .getValuesList()
        .stream()
        .map(JinjaContextHelper::protoValueToJava)
        .toList();
      default -> null;
    };
  }

  /**
   * Converts a Java {@link Map} to a protobuf {@link Struct}, recursively
   * handling nested maps and lists. Inverse of {@link #structToMap}. Booleans
   * stay booleans — important for template flags like {@code enable_thinking}
   * whose Jinja2 {@code is false} test is a strict boolean-identity check.
   */
  public static Struct mapToStruct(Map<String, Object> map) {
    var builder = Struct.newBuilder();
    for (var entry : map.entrySet()) {
      builder.putFields(entry.getKey(), javaToProtoValue(entry.getValue()));
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static Value javaToProtoValue(Object obj) {
    return switch (obj) {
      case null -> Value.newBuilder().setNullValueValue(0).build();
      case Boolean b -> Value.newBuilder().setBoolValue(b).build();
      case Number n -> Value.newBuilder().setNumberValue(n.doubleValue()).build();
      case String s -> Value.newBuilder().setStringValue(s).build();
      case Map<?, ?> m -> Value.newBuilder()
        .setStructValue(mapToStruct((Map<String, Object>) m))
        .build();
      case List<?> l -> {
        var listBuilder = com.google.protobuf.ListValue.newBuilder();
        for (var item : l) {
          listBuilder.addValues(javaToProtoValue(item));
        }
        yield Value.newBuilder().setListValue(listBuilder.build()).build();
      }
      default -> Value.newBuilder().setStringValue(String.valueOf(obj)).build();
    };
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private static String resolveSystemFromMessages(PipelineContext pctx) {
    if (pctx.messages() == null) return "";
    return pctx
      .messages()
      .stream()
      .filter(t -> t.role() == ChatRole.SYSTEM)
      .findFirst()
      .map(t -> t.content() != null ? t.content() : "")
      .orElse("");
  }

  private static String resolveHistoryFromMessages(PipelineContext pctx) {
    if (pctx.messages() == null) return "";
    var sb = new StringBuilder();
    for (var turn : pctx.messages()) {
      sb
        .append(turn.role().name().toLowerCase())
        .append(": ")
        .append(turn.content() != null ? turn.content() : "")
        .append("\n");
    }
    int len = sb.length();
    if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
    return sb.toString();
  }
}
