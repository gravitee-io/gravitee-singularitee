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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.gravitee.singularitee.engine.ChatRole;
import io.gravitee.singularitee.engine.ChatTurn;
import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.engine.TextGenRequest;
import io.gravitee.singularitee.pipeline.PipelineContext;
import io.gravitee.singularitee.protocol.InferStepConfig;
import io.gravitee.singularitee.protocol.MessageDef;
import io.gravitee.singularitee.protocol.TagConfig;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InferStepExecutor}'s prompt construction: chat
 * template rendering with per-step context (the Qwen3 thinking bypass) and
 * the structured-messages fallback when the engine has no chat template yet
 * (lazy remote metadata not fetched).
 */
class InferStepExecutorTest {

  private static final String THINK_PREFILL = "<|im_start|>assistant\n<think>\n\n</think>\n\n";

  private InferStepExecutor executor;
  private StepExecutionContext execContext;

  @BeforeEach
  void setUp() {
    execContext = mock(StepExecutionContext.class);
    var streamRegistry = mock(StreamRegistry.class);
    when(streamRegistry.streamsForModel(anyString())).thenReturn(new ConcurrentHashMap<>());
    when(execContext.streamRegistry()).thenReturn(streamRegistry);
    when(execContext.lookupModel(anyString())).thenReturn(java.util.Optional.empty());
    executor = new InferStepExecutor(execContext, new JinjaRenderer());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** The real Qwen3-0.6B chat_template, vendored from tokenizer_config.json. */
  private static String qwen3Template() {
    try (
      var in = InferStepExecutorTest.class.getResourceAsStream(
        "/templates/qwen3-chat-template.jinja"
      )
    ) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Engine stub that captures the {@link TextGenRequest} it receives. */
  private static class CapturingEngine implements TextGenEngine {

    final AtomicReference<TextGenRequest> captured = new AtomicReference<>();
    private final String template;

    CapturingEngine(String template) {
      this.template = template;
    }

    @Override
    public String chatTemplateString() {
      return template;
    }

    @Override
    public void start(Consumer<ModelEngineToken> tokenConsumer) {}

    @Override
    public io.reactivex.rxjava3.core.Flowable<ModelEngineToken> rxStream(int seqId) {
      // These tests assert on the captured request only; no tokens need to flow.
      return io.reactivex.rxjava3.core.Flowable.never();
    }

    @Override
    public Completable rxAddSequence(int seqId, TextGenRequest request) {
      captured.set(request);
      return Completable.never();
    }

    @Override
    public void close() {}
  }

  private static StepContext stepContext(PipelineContext pctx) {
    var ctx = mock(StepContext.class);
    when(ctx.pipelineContext()).thenReturn(pctx);
    when(ctx.rxNextStep(anyString())).thenReturn(Maybe.just("next"));
    return ctx;
  }

  private static InferStepConfig configWithThinkingDisabled() {
    return InferStepConfig.newBuilder()
      .setModelId("qwen3")
      .addMessages(MessageDef.newBuilder().setRole("system").setContent("Rewrite as-is:"))
      .addMessages(MessageDef.newBuilder().setRole("user").setContent("{{ prompt }}"))
      .setContext(
        Struct.newBuilder().putFields(
          "enable_thinking",
          Value.newBuilder().setBoolValue(false).build()
        )
      )
      .build();
  }

  private TextGenRequest execute(InferStepConfig cfg, TextGenEngine engine) {
    return execute(cfg, engine, null);
  }

  private TextGenRequest execute(
    InferStepConfig cfg,
    TextGenEngine engine,
    Map<String, String> seed
  ) {
    var pctx = new PipelineContext(
      "hello world",
      List.of(new ChatTurn(ChatRole.USER, "hello world")),
      null,
      List.of(),
      seed
    );
    executor.rxExecuteWithEngine("generate", cfg, engine, stepContext(pctx)).test();
    return ((CapturingEngine) engine).captured.get();
  }

  // ── Chat template present: client-side render with context ────────────────

  @Nested
  class TemplateRender {

    @Test
    void enable_thinking_false_renders_empty_think_prefill() {
      var engine = new CapturingEngine(qwen3Template());

      TextGenRequest req = execute(configWithThinkingDisabled(), engine);

      assertThat(req).isNotNull();
      assertThat(req.prompt()).isNotNull().endsWith(THINK_PREFILL);
    }

    @Test
    void step_context_is_carried_as_template_context() {
      var engine = new CapturingEngine(qwen3Template());

      TextGenRequest req = execute(configWithThinkingDisabled(), engine);

      assertThat(req.templateContext()).isNotNull().containsEntry("enable_thinking", Boolean.FALSE);
    }
  }

  // ── Hallucinated tool-syntax residue detection ─────────────────────────────

  @Test
  void hallucinated_marker_residue_is_flagged_only_for_marker_dialects() {
    var harmony = InferStepConfig.newBuilder()
      .setModelId("llm")
      .setToolExtractionTemplate("harmony")
      .setToolCallTags(
        TagConfig.newBuilder()
          .setOpenTag("<|end|><|start|>assistant<|channel|>commentary to=functions.")
          .setCloseTag("<|call|>")
      )
      .build();
    var plain = InferStepConfig.newBuilder().setModelId("llm").build();

    // The live leak: invented channel form containing dialect special tokens.
    String leak =
      "<|channel|>functions.set_todos<|channel|>commentary json<|message|>{\"todos\":[]}";
    assertThat(InferStepExecutor.hasToolMarkerResidue(leak, harmony)).isTrue();
    // A stray to=functions. routing prefix (derived from the configured tag) is residue too.
    assertThat(
      InferStepExecutor.hasToolMarkerResidue("x to=functions.bash {\"c\":1}", harmony)
    ).isTrue();
    // Plain prose never trips it; markerless dialects never trip it.
    assertThat(InferStepExecutor.hasToolMarkerResidue("The capital is Paris.", harmony)).isFalse();
    assertThat(InferStepExecutor.hasToolMarkerResidue(leak, plain)).isFalse();

    // Dialect-agnostic: a chatml-tagged step flags ITS leaked tag, not Harmony's.
    var chatml = InferStepConfig.newBuilder()
      .setModelId("llm")
      .setToolCallTags(TagConfig.newBuilder().setOpenTag("<tool_call>").setCloseTag("</tool_call>"))
      .build();
    assertThat(
      InferStepExecutor.hasToolMarkerResidue("oops <tool_call>{\"n\":1}", chatml)
    ).isTrue();
    assertThat(InferStepExecutor.hasToolMarkerResidue(leak, chatml)).isFalse();
  }

  // ── Step system prompt combines with the caller's ─────────────────────────

  @Nested
  class SystemPromptCombination {

    @Test
    void step_system_merges_after_caller_system() {
      var engine = new CapturingEngine(qwen3Template());
      var pctx = new PipelineContext(
        null,
        List.of(
          new ChatTurn(ChatRole.SYSTEM, "You are pi, a coding agent."),
          new ChatTurn(ChatRole.USER, "hello")
        ),
        null,
        List.of(),
        null
      );
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setSystemPrompt("Call the set_todos tool with the plan.")
        .build();

      executor.rxExecuteWithEngine("plan", cfg, engine, stepContext(pctx)).test();

      String prompt = ((CapturingEngine) engine).captured.get().prompt();
      // Caller identity first, step steering appended — neither is dropped.
      assertThat(prompt).contains("You are pi, a coding agent.");
      assertThat(prompt).contains("Call the set_todos tool with the plan.");
      assertThat(prompt.indexOf("You are pi")).isLessThan(prompt.indexOf("Call the set_todos"));
    }

    @Test
    void step_system_prepended_when_caller_has_none() {
      var engine = new CapturingEngine(qwen3Template());
      var pctx = new PipelineContext(
        "hello",
        List.of(new ChatTurn(ChatRole.USER, "hello")),
        null,
        List.of(),
        null
      );
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setSystemPrompt("Call the set_todos tool with the plan.")
        .build();

      executor.rxExecuteWithEngine("plan", cfg, engine, stepContext(pctx)).test();

      assertThat(((CapturingEngine) engine).captured.get().prompt()).contains(
        "Call the set_todos tool with the plan."
      );
    }
  }

  // ── Sampling precedence: request > loop-retry > step ──────────────────────

  @Nested
  class RetrySamplingPrecedence {

    private TextGenRequest run(
      io.gravitee.singularitee.protocol.SamplingParams requestSp,
      io.gravitee.singularitee.protocol.SamplingParams retrySp
    ) {
      var engine = new CapturingEngine(qwen3Template());
      var pctx = new PipelineContext(
        "hello",
        List.of(new ChatTurn(ChatRole.USER, "hello")),
        requestSp,
        List.of(),
        null
      );
      if (retrySp != null) {
        pctx.setRetrySamplingParams(retrySp);
      }
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setSamplingParams(
          io.gravitee.singularitee.protocol.SamplingParams.newBuilder().setTemperature(0.9f)
        )
        .build();
      executor.rxExecuteWithEngine("generate", cfg, engine, stepContext(pctx)).test();
      return engine.captured.get();
    }

    private static io.gravitee.singularitee.protocol.SamplingParams temp(float t) {
      return io.gravitee.singularitee.protocol.SamplingParams.newBuilder()
        .setTemperature(t)
        .build();
    }

    @Test
    void step_params_apply_when_no_overrides() {
      assertThat(run(null, null).temperature()).isEqualTo(0.9f);
    }

    @Test
    void retry_override_beats_step_params() {
      assertThat(run(null, temp(0.2f)).temperature()).isEqualTo(0.2f);
    }

    @Test
    void request_override_beats_retry_override() {
      assertThat(run(temp(0.5f), temp(0.2f)).temperature()).isEqualTo(0.5f);
    }
  }

  // ── Per-step chat_template override ───────────────────────────────────────

  @Nested
  class ChatTemplateOverride {

    private static final String OVERRIDE_TEMPLATE =
      "{% for message in messages %}<|{{ message.role }}|>{{ message.content }}{% endfor %}";

    private static InferStepConfig configWithOverride(String template) {
      return InferStepConfig.newBuilder().setModelId("qwen3").setChatTemplate(template).build();
    }

    @Test
    void override_replaces_engine_gguf_template() {
      var engine = new CapturingEngine(qwen3Template());

      TextGenRequest req = execute(configWithOverride(OVERRIDE_TEMPLATE), engine);

      assertThat(req.prompt()).isEqualTo("<|user|>hello world");
      assertThat(req.prompt()).doesNotContain("<|im_start|>");
    }

    @Test
    void absent_override_uses_engine_gguf_template() {
      var engine = new CapturingEngine(qwen3Template());

      TextGenRequest req = execute(
        InferStepConfig.newBuilder().setModelId("qwen3").build(),
        engine
      );

      assertThat(req.prompt()).startsWith("<|im_start|>");
    }

    @Test
    void blank_override_uses_engine_gguf_template() {
      var engine = new CapturingEngine(qwen3Template());

      TextGenRequest req = execute(configWithOverride("  "), engine);

      assertThat(req.prompt()).startsWith("<|im_start|>");
    }

    @Test
    void broken_override_fails_fast_with_clear_error() {
      var engine = new CapturingEngine(qwen3Template());
      var cfg = configWithOverride("{% for message in messages %}oops{% endfo %}");

      assertThatThrownBy(() -> execute(cfg, engine))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chat_template override")
        .hasMessageContaining("generate");
    }
  }

  // ── No chat template: structured-messages fallback ────────────────────────

  @Nested
  class NoTemplateFallback {

    @Test
    void sends_structured_messages_instead_of_concatenated_prompt() {
      var engine = new CapturingEngine(null);

      TextGenRequest req = execute(configWithThinkingDisabled(), engine);

      assertThat(req).isNotNull();
      // No pre-rendered prompt — and especially no "role: content" junk.
      assertThat(req.prompt()).isNull();
      assertThat(req.messages())
        .extracting(ChatTurn::role, ChatTurn::content)
        .containsExactly(
          org.assertj.core.groups.Tuple.tuple(ChatRole.SYSTEM, "Rewrite as-is:"),
          org.assertj.core.groups.Tuple.tuple(ChatRole.USER, "hello world")
        );
    }

    @Test
    void template_context_still_forwarded_for_engine_side_render() {
      var engine = new CapturingEngine(null);

      TextGenRequest req = execute(configWithThinkingDisabled(), engine);

      assertThat(req.templateContext()).containsEntry("enable_thinking", Boolean.FALSE);
    }

    @Test
    void disposing_the_step_disposes_the_engine_subscription() {
      // Client disconnect cancels the pipeline chain; disposing the step's
      // Maybe must dispose the engine's rxAddSequence subscription — that is
      // the hook engines use to cancel native generation (doOnDispose →
      // cancelSequence) or to cancel the upstream gRPC call (remote).
      var disposed = new java.util.concurrent.atomic.AtomicBoolean(false);
      var engine = new CapturingEngine(null) {
        @Override
        public Completable rxAddSequence(int seqId, TextGenRequest request) {
          captured.set(request);
          return Completable.never().doOnDispose(() -> disposed.set(true));
        }
      };
      var pctx = new PipelineContext(
        "hello world",
        List.of(new ChatTurn(ChatRole.USER, "hello world")),
        null,
        List.of(),
        null
      );

      var observer = executor
        .rxExecuteWithEngine("generate", configWithThinkingDisabled(), engine, stepContext(pctx))
        .test();

      assertThat(disposed).isFalse();
      observer.dispose();
      assertThat(disposed).isTrue();
    }

    @Test
    void passthrough_without_yaml_messages_keeps_original_turns() {
      // No YAML message override — the caller's original ChatTurns must be
      // forwarded as-is (preserving any multimodal media), not rebuilt.
      var engine = new CapturingEngine(null);
      var cfg = InferStepConfig.newBuilder().setModelId("qwen3").build();

      TextGenRequest req = execute(cfg, engine);

      assertThat(req.prompt()).isNull();
      assertThat(req.messages())
        .extracting(ChatTurn::role, ChatTurn::content)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(ChatRole.USER, "hello world"));
    }
  }

  // ── Request-level reasoning_effort ────────────────────────────────────────

  @Nested
  class ReasoningEffort {

    private static final String EFFORT_TEMPLATE =
      "effort={{ reasoning_effort }}|{% for message in messages %}{{ message.content }}{% endfor %}";

    @Test
    void request_reasoning_effort_reaches_template_context() {
      var engine = new CapturingEngine(null);
      var cfg = InferStepConfig.newBuilder().setModelId("qwen3").build();

      TextGenRequest req = execute(cfg, engine, Map.of("reasoning_effort", "high"));

      assertThat(req.templateContext()).containsEntry("reasoning_effort", "high");
    }

    @Test
    void request_reasoning_effort_overrides_step_config_default() {
      var engine = new CapturingEngine(null);
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setContext(
          Struct.newBuilder().putFields(
            "reasoning_effort",
            Value.newBuilder().setStringValue("low").build()
          )
        )
        .build();

      TextGenRequest req = execute(cfg, engine, Map.of("reasoning_effort", "high"));

      assertThat(req.templateContext()).containsEntry("reasoning_effort", "high");
    }

    @Test
    void absent_reasoning_effort_keeps_step_config_value() {
      var engine = new CapturingEngine(null);
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setContext(
          Struct.newBuilder().putFields(
            "reasoning_effort",
            Value.newBuilder().setStringValue("low").build()
          )
        )
        .build();

      TextGenRequest req = execute(cfg, engine, null);

      assertThat(req.templateContext()).containsEntry("reasoning_effort", "low");
    }

    @Test
    void reasoning_effort_is_rendered_into_the_prompt() {
      var engine = new CapturingEngine(qwen3Template());
      var cfg = InferStepConfig.newBuilder()
        .setModelId("qwen3")
        .setChatTemplate(EFFORT_TEMPLATE)
        .build();

      TextGenRequest req = execute(cfg, engine, Map.of("reasoning_effort", "medium"));

      assertThat(req.prompt()).isEqualTo("effort=medium|hello world");
    }
  }

  @org.junit.jupiter.api.Nested
  class ToolPayloadReWrapping {

    @Test
    void empty_tool_payload_returns_text_untouched() {
      var cfg = InferStepConfig.newBuilder().build();
      assertThat(InferStepExecutor.withReWrappedToolCalls("answer", "", cfg)).isEqualTo("answer");
      assertThat(InferStepExecutor.withReWrappedToolCalls("answer", null, cfg)).isEqualTo("answer");
    }

    @Test
    void bare_payload_is_rewrapped_with_default_tags() {
      var cfg = InferStepConfig.newBuilder().build();
      assertThat(
        InferStepExecutor.withReWrappedToolCalls("Let me check.", "{\"name\":\"f\"}", cfg)
      ).isEqualTo("Let me check.\n<tool_call>{\"name\":\"f\"}</tool_call>");
    }

    @Test
    void bare_payload_uses_configured_tool_tags() {
      var cfg = InferStepConfig.newBuilder()
        .setToolCallTags(
          TagConfig.newBuilder().setOpenTag("<|tool_call>").setCloseTag("<tool_call|>")
        )
        .build();
      assertThat(InferStepExecutor.withReWrappedToolCalls("", "call:f{x:1}", cfg)).isEqualTo(
        "<|tool_call>call:f{x:1}<tool_call|>"
      );
    }
  }

  // ── Markerless tool extraction attempt on plain stop ───────────────────────

  @Nested
  class MarkerlessStopExtraction {

    private static final String GLM_CALL = "send_email\n{\"to\":\"a@b.com\"}";

    private static PipelineContext contextWithTools() {
      var pctx = new PipelineContext(
        "mail bob",
        List.of(new ChatTurn(ChatRole.USER, "mail bob")),
        null,
        List.of(
          io.gravitee.singularitee.protocol.ToolDefinition.newBuilder()
            .setName("send_email")
            .setDescription("Send an email")
            .build()
        ),
        null
      );
      pctx.setLastEngineFinishReason(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_STOP
      );
      return pctx;
    }

    private static InferStepConfig configWithTemplate(String template) {
      var b = InferStepConfig.newBuilder().setModelId("glm");
      if (template != null) {
        b.setToolExtractionTemplate(template);
      }
      return b.build();
    }

    @Test
    void configured_template_with_tools_turns_stop_into_tool_calls() {
      var pctx = contextWithTools();

      InferStepExecutor.maybeExtractMarkerlessToolCalls(
        pctx,
        "generate",
        configWithTemplate("glm-name-json"),
        GLM_CALL
      );

      assertThat(pctx.extractedToolCalls()).hasSize(1);
      assertThat(pctx.extractedToolCalls().getFirst().getName()).isEqualTo("send_email");
      assertThat(pctx.extractedToolCalls().getFirst().getArgumentsJson()).isEqualTo(
        "{\"to\":\"a@b.com\"}"
      );
      assertThat(pctx.lastEngineFinishReason()).isEqualTo(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_TOOL_CALLS
      );
      assertThat(pctx.get("generate.tool_parse_ok")).isEqualTo("true");
      assertThat(pctx.get("generate.tool_call_count")).isEqualTo("1");
      assertThat(pctx.get("generate.finish_reason")).isEqualTo("tool_calls");
    }

    @Test
    void no_template_configured_leaves_response_untouched() {
      var pctx = contextWithTools();

      InferStepExecutor.maybeExtractMarkerlessToolCalls(
        pctx,
        "generate",
        configWithTemplate(null),
        GLM_CALL
      );

      assertThat(pctx.extractedToolCalls()).isEmpty();
      assertThat(pctx.lastEngineFinishReason()).isEqualTo(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_STOP
      );
    }

    @Test
    void plain_prose_answer_leaves_response_untouched() {
      var pctx = contextWithTools();

      InferStepExecutor.maybeExtractMarkerlessToolCalls(
        pctx,
        "generate",
        configWithTemplate("glm-name-json"),
        "Sure, I emailed bob for you.\nAnything else?"
      );

      assertThat(pctx.extractedToolCalls()).isEmpty();
      assertThat(pctx.get("generate.tool_parse_ok")).isEqualTo("false");
      assertThat(pctx.get("generate.tool_call_count")).isEqualTo("0");
      assertThat(pctx.lastEngineFinishReason()).isEqualTo(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_STOP
      );
    }

    @Test
    void no_declared_tools_skips_extraction() {
      var pctx = new PipelineContext(
        "mail bob",
        List.of(new ChatTurn(ChatRole.USER, "mail bob")),
        null,
        List.of(),
        null
      );
      pctx.setLastEngineFinishReason(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_STOP
      );

      InferStepExecutor.maybeExtractMarkerlessToolCalls(
        pctx,
        "generate",
        configWithTemplate("glm-name-json"),
        GLM_CALL
      );

      assertThat(pctx.extractedToolCalls()).isEmpty();
    }

    @Test
    void attempted_tool_name_strips_namespace_and_handles_junk() {
      assertThat(
        InferStepExecutor.attemptedToolName("apply_patch code<|message|>{\"patch\":\"x\"}")
      ).isEqualTo("apply_patch");
      assertThat(
        InferStepExecutor.attemptedToolName("functions.apply_patch code<|message|>{}")
      ).isEqualTo("apply_patch");
      assertThat(InferStepExecutor.attemptedToolName("  send_email {\"to\":\"a\"}")).isEqualTo(
        "send_email"
      );
      assertThat(InferStepExecutor.attemptedToolName("<|weird|>")).isEmpty();
      assertThat(InferStepExecutor.attemptedToolName(null)).isEmpty();
    }

    @Test
    void non_stop_finish_skips_extraction() {
      var pctx = contextWithTools();
      pctx.setLastEngineFinishReason(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_LENGTH
      );

      InferStepExecutor.maybeExtractMarkerlessToolCalls(
        pctx,
        "generate",
        configWithTemplate("glm-name-json"),
        GLM_CALL
      );

      assertThat(pctx.extractedToolCalls()).isEmpty();
      assertThat(pctx.lastEngineFinishReason()).isEqualTo(
        io.gravitee.singularitee.protocol.FinishReason.FINISH_REASON_LENGTH
      );
    }
  }
}
