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
package io.gravitee.singularitee.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Jackson-deserialisable POJO tree for a Singularitee workspace YAML file.
 *
 * <p>The workspace defines models and pipelines to publish at server startup.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceDefinition(@JsonProperty("workspace") WorkspaceRoot workspace) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WorkspaceRoot(
    @JsonProperty("name") String name,
    @JsonProperty("remote") RemoteConfig remote,
    @JsonProperty("models") List<ModelDefinition> models,
    @JsonProperty("pipelines") List<PipelineDefinition> pipelines,
    @JsonProperty("templates") List<TemplateDefinition> templates,
    // Named, reusable tag sets: steps reference an entry by writing the id as
    // the whole tags value (tags: harmony) instead of repeating the block.
    @JsonProperty("tags") List<TagsDef> tags,
    @JsonProperty("includes") IncludesDef includes
  ) {}

  /**
   * Typed include directives for workspace composition.
   *
   * <p>Each sub-list contains file paths (or glob patterns) relative to the
   * workspace file's parent directory. Only the section matching the key is
   * extracted from each included file — a file listed under {@code models:}
   * contributes only its {@code workspace.models} list, and so on.
   *
   * <p>Glob patterns (e.g. {@code models/*.yaml}) are expanded alphabetically.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncludesDef(
    @JsonProperty("models") List<String> models,
    @JsonProperty("pipelines") List<String> pipelines,
    @JsonProperty("templates") List<String> templates
  ) {}

  // ── Remote config ──────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteConfig(
    @JsonProperty("default") RemoteEndpoint defaultEndpoint,
    @JsonProperty("servers") List<RemoteEndpoint> servers
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteEndpoint(
    @JsonProperty("id") String id,
    @JsonProperty("host") String host,
    @JsonProperty("port") int port,
    /**
     * HTTP/2 keep-alive ping interval in seconds.
     *
     * <p>When set to {@code -1}, keep-alive is always on — no timeout is applied
     * and connections are kept alive indefinitely (equivalent to Vert.x's default
     * behaviour of never sending a GOAWAY due to inactivity).
     *
     * <p>Any other positive value is forwarded to
     * {@link io.vertx.core.http.HttpClientOptions#setHttp2KeepAliveTimeout(int)}
     * and controls how long an idle HTTP/2 connection is held open before it is
     * closed by the client.
     *
     * <p>Defaults to {@code -1} (always keep alive).
     */
    @JsonProperty("http2_keep_alive_timeout") Integer http2KeepAliveTimeout,
    /**
     * Optional HTTP Basic auth username sent as gRPC metadata to this endpoint.
     * When {@code null}, the client connects without authentication.
     */
    @JsonProperty("username") String username,
    /** Optional HTTP Basic auth password — paired with {@link #username()}. */
    @JsonProperty("password") String password,
    /**
     * Whether to reach this endpoint over TLS. Server certificates are validated
     * against the JVM's default trust store and ALPN negotiates HTTP/2, so this
     * covers a Singularitee with {@code grpc.secured} on, or a TLS-terminating edge
     * in front of it. Client certificates (mTLS) are not supported here.
     *
     * <p>Defaults to {@code false} — plaintext, which is why Basic credentials on a
     * non-loopback endpoint should be paired with {@code ssl: true}.
     */
    @JsonProperty("ssl") Boolean ssl
  ) {
    public static final int DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT = -1;

    /** Backward-compatible constructor without keep-alive or credentials. */
    public RemoteEndpoint(String id, String host, int port) {
      this(id, host, port, null, null, null, null);
    }

    /** Backward-compatible constructor without credentials. */
    public RemoteEndpoint(String id, String host, int port, Integer http2KeepAliveTimeout) {
      this(id, host, port, http2KeepAliveTimeout, null, null, null);
    }

    /** Backward-compatible constructor without TLS. */
    public RemoteEndpoint(
      String id,
      String host,
      int port,
      Integer http2KeepAliveTimeout,
      String username,
      String password
    ) {
      this(id, host, port, http2KeepAliveTimeout, username, password, null);
    }

    /** Returns the effective keep-alive timeout, falling back to the default when not set. */
    public int effectiveHttp2KeepAliveTimeout() {
      return http2KeepAliveTimeout != null
        ? http2KeepAliveTimeout
        : DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT;
    }

    /** Whether Basic auth credentials are configured for this endpoint. */
    public boolean hasCredentials() {
      return username != null && !username.isBlank();
    }

    /** Whether to connect over TLS; {@code false} when unset. */
    public boolean effectiveSsl() {
      return ssl != null && ssl;
    }
  }

  // ── Template ──────────────────────────────────────────────────────────────

  /**
   * A named, reusable Jinja2 template declared at workspace level under {@code templates:}.
   *
   * <p>Steps reference a template by ID via {@code prompt.template_id:}. The loader resolves
   * the reference at load time — the same moment {@code template_file:} is resolved — so the
   * engine always receives a fully materialised raw template string and never sees the ID.
   *
   * <p>Only one of {@code content} or {@code file} may be set; setting both is a load-time
   * error. {@code file} is resolved relative to the workspace YAML file's parent directory,
   * identical to {@code template_file:} on a step.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TemplateDefinition(
    @JsonProperty("id") String id,
    // Inline Jinja2 content.
    @JsonProperty("content") String content,
    // Path to a .jinja2 file — resolved relative to the workspace file's parent directory.
    // Mutually exclusive with content:.
    @JsonProperty("file") String file
  ) {}

  // ── Model ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModelDefinition(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("server") String server,
    @JsonProperty("memory_check") String memoryCheck,
    @JsonProperty("download") DownloadDef download,
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    @JsonProperty("vllm") VllmDef vllm,
    @JsonProperty("onnx_classifier") OnnxClassifierDef onnxClassifier,
    @JsonProperty("onnx_embedding") OnnxEmbeddingDef onnxEmbedding,
    @JsonProperty("gliner_classifier") GlinerClassifierDef glinerClassifier,
    @JsonProperty("gliner_ner") GlinerNerDef glinerNer,
    @JsonProperty("regex") RegexDef regex,
    @JsonProperty("composite_classifier") CompositeClassifierDef compositeClassifier,
    @JsonProperty("onnx_reranker") OnnxRerankerDef onnxReranker,
    @JsonProperty("llama_cpp_embedding") LlamaCppEmbeddingDef llamaCppEmbedding,
    @JsonProperty("llama_cpp_reranker") LlamaCppRerankerDef llamaCppReranker
  ) {}

  /**
   * Narrows what gets pulled from HuggingFace when a model is downloaded.
   *
   * <p>Repositories routinely ship several copies of the same weights — an ONNX
   * export, a GGUF for llama.cpp, Meta's {@code original/} checkpoint beside the
   * safetensors — and each resolver already drops the formats its engine cannot
   * read. {@code exclude} is for what those built-in rules cannot know: a
   * duplicate the repo ships in the *same* format, a variant you do not want, a
   * multi-gigabyte extra you would rather not transfer.
   *
   * <p>Patterns are globs matched against the repository-relative path:
   * {@code *} within a path segment, {@code **} across segments, {@code ?} for a
   * single character. A pattern with no {@code /} also matches on the file name
   * alone, so {@code "*.pth"} catches {@code original/consolidated.00.pth}.
   * Matching is case-insensitive.
   *
   * <p>Excludes only ever *narrow* the built-in selection — they never re-admit a
   * file the engine's own rules rejected. They apply where a resolver picks a
   * set of files out of a repository listing (vLLM, GLiNER, and ONNX tokenizer
   * directories); a file named outright in the model definition — a GGUF
   * {@code path:}, an ONNX {@code model_path:} — is always fetched, since
   * excluding it could only turn a working config into a failed load.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DownloadDef(@JsonProperty("exclude") List<String> exclude) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppDef(
    @JsonProperty("path") String path,
    @JsonProperty("n_ctx") int nCtx,
    @JsonProperty("n_batch") int nBatch,
    @JsonProperty("n_ubatch") int nUbatch,
    @JsonProperty("n_seq_max") int nSeqMax,
    @JsonProperty("n_gpu_layers") int nGpuLayers,
    @JsonProperty("pooling_type") String poolingType,
    @JsonProperty("attention_type") String attentionType,
    @JsonProperty("flash_attn_type") String flashAttnType,
    @JsonProperty("offload_kqv") Boolean offloadKqv,
    @JsonProperty("lora_path") String loraPath,
    @JsonProperty("mmproj_path") String mmprojPath,
    @JsonProperty("media_marker") String mediaMarker,
    @JsonProperty("mtp") boolean mtp,
    @JsonProperty("speculative") SpeculativeDef speculative,
    @JsonProperty("use_mlock") Boolean useMlock,
    @JsonProperty("cache_type_k") String cacheTypeK,
    @JsonProperty("cache_type_v") String cacheTypeV,
    @JsonProperty("prompt_cache") Boolean promptCache,
    @JsonProperty("prompt_cache_min_tokens") int promptCacheMinTokens,
    @JsonProperty("eog_ramp_start") Float eogRampStart,
    @JsonProperty("eog_ramp_max_bias") Float eogRampMaxBias,
    @JsonProperty("draft_model") String draftModel,
    @JsonProperty("draft_path") String draftPath,
    @JsonProperty("eagle3_model") String eagle3Model,
    @JsonProperty("eagle3_path") String eagle3Path
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SpeculativeDef(
    @JsonProperty("n_draft") int nDraft,
    @JsonProperty("draft_min") int draftMin,
    @JsonProperty("p_min") float pMin,
    @JsonProperty("temperature") Float temperature,
    @JsonProperty("top_k") int topK,
    @JsonProperty("top_p") Float topP,
    @JsonProperty("seed") Long seed
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VllmDef(
    @JsonProperty("dtype") String dtype,
    @JsonProperty("max_model_len") int maxModelLen,
    @JsonProperty("max_num_seqs") int maxNumSeqs,
    @JsonProperty("gpu_memory_utilization") double gpuMemoryUtilization,
    @JsonProperty("max_num_batched_tokens") int maxNumBatchedTokens,
    @JsonProperty("enforce_eager") boolean enforceEager,
    @JsonProperty("trust_remote_code") boolean trustRemoteCode,
    @JsonProperty("quantization") String quantization,
    @JsonProperty("seed") int seed,
    // Boxed: an omitted key must stay distinguishable from an explicit false, or the
    // engine can only ever be told to turn prefix caching on.
    @JsonProperty("enable_prefix_caching") Boolean enablePrefixCaching,
    @JsonProperty("enable_chunked_prefill") boolean enableChunkedPrefill,
    @JsonProperty("kv_cache_dtype") String kvCacheDtype,
    @JsonProperty("enable_lora") boolean enableLora,
    @JsonProperty("max_loras") int maxLoras,
    @JsonProperty("max_lora_rank") int maxLoraRank,
    @JsonProperty("enable_sleep_mode") Boolean enableSleepMode,
    // Multi-GPU. Left at 0 the server falls back to its own defaults
    // (ai.vllm.* in gravitee.yml / GRAVITEE_* env), then to vLLM's.
    @JsonProperty("tensor_parallel_size") int tensorParallelSize,
    @JsonProperty("pipeline_parallel_size") int pipelineParallelSize,
    @JsonProperty("distributed_executor_backend") String distributedExecutorBackend,
    // Unified alias for enable_prefix_caching — either field enables vLLM's native prefix cache.
    @JsonProperty("prompt_cache") Boolean promptCache
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxClassifierDef(
    @JsonProperty("model_path") String modelPath,
    @JsonProperty("tokenizer_path") String tokenizerPath,
    @JsonProperty("config_json_path") String configJsonPath,
    @JsonProperty("labels") List<String> labels,
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    @JsonProperty("classifier_mode") String classifierMode
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxEmbeddingDef(
    @JsonProperty("model_path") String modelPath,
    @JsonProperty("tokenizer_path") String tokenizerPath,
    @JsonProperty("config_json_path") String configJsonPath,
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    @JsonProperty("pooling_mode") String poolingMode,
    @JsonProperty("normalize") boolean normalize
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxRerankerDef(
    @JsonProperty("model_path") String modelPath,
    @JsonProperty("tokenizer_path") String tokenizerPath,
    @JsonProperty("config_json_path") String configJsonPath,
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    @JsonProperty("scoring") String scoring
  ) {}

  /**
   * YAML configuration block for a llama.cpp-backed embedding model.
   *
   * <p>Uses the shared {@link LlamaCppDef} block for engine parameters plus an
   * optional {@code embedding_template} for instruction-aware models such as
   * Qwen3-Embedding.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppEmbeddingDef(
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    // Optional prompt template for instruction-aware models.
    // Wrap the raw text before tokenisation, e.g.:
    //   "Instruct: Given a query, retrieve relevant passages.\nQuery: {text}"
    // Leave empty for models that accept plain text (BGE-M3, nomic-embed, …).
    @JsonProperty("embedding_template") String embeddingTemplate
  ) {}

  /**
   * YAML configuration block for a llama.cpp-backed cross-encoder reranker model.
   *
   * <p>The GGUF must export a classifier head and must be loaded with
   * {@code pooling_type: RANK} in the nested {@code llama_cpp} block.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppRerankerDef(
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    // Scoring transformation: SIGMOID (default for 1-logit heads), SOFTMAX
    // (2-class heads), LOGIT (raw monotonic score). Leave empty to auto-detect.
    @JsonProperty("scoring") String scoring,
    // Optional prompt template for chat-style rerankers (e.g. Qwen3-Reranker).
    // Supports {query} and {document} placeholders.
    // Leave empty for BERT-family models (plain concatenation).
    @JsonProperty("rerank_template") String rerankTemplate
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerLabelDef(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerClassifierDef(
    @JsonProperty("model_dir") String modelDir,
    @JsonProperty("labels") List<GlinerLabelDef> labels,
    @JsonProperty("threshold") float threshold,
    @JsonProperty("variant") String variant,
    @JsonProperty("token_cap") int tokenCap
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerEntityDef(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerNerDef(
    @JsonProperty("model_dir") String modelDir,
    @JsonProperty("entities") List<GlinerEntityDef> entities,
    @JsonProperty("threshold") float threshold,
    @JsonProperty("variant") String variant,
    @JsonProperty("token_cap") int tokenCap
  ) {}

  // ── Client-local model defs (pure Java, no proto) ─────────────────────────

  /**
   * A single regex pattern paired with its entity type label. The entity
   * type surfaces as the {@code label} on every {@code ClassifyResult}
   * emitted by the engine and is used by the guard step's trigger matching
   * and REDACT replacement (e.g. {@code [SSN]}, {@code [EMAIL]}).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexPatternDef(
    @JsonProperty("pattern") String pattern,
    @JsonProperty("entity_type") String entityType
  ) {}

  /** Configuration for a client-local regex classifier model. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexDef(@JsonProperty("patterns") List<RegexPatternDef> patterns) {}

  /**
   * Configuration for a client-local composite classifier model.
   *
   * <p>The {@code models} list contains the IDs of other models declared in
   * the workspace. Each referenced model must implement {@code ClassifierEngine}
   * (typically {@code regex} or another {@code composite_classifier},
   * but remote/ONNX/GLiNER classifiers are also permitted).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CompositeClassifierDef(@JsonProperty("models") List<String> models) {}

  // ── Pipeline ──────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PipelineDefinition(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("server") String server,
    @JsonProperty("entry") String entry,
    @JsonProperty("steps") List<StepDefinition> steps,
    @JsonProperty("remote") RemoteProxyDef remote
  ) {
    /** Returns {@code true} if this pipeline is a remote reference (no local steps). */
    public boolean isRemote() {
      return server != null && !server.isBlank();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteProxyDef(
    @JsonProperty("system_prompt") String systemPrompt,
    @JsonProperty("forward_messages") boolean forwardMessages
  ) {}

  // ── Step ──────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StepDefinition(
    @JsonProperty("id") String id,
    @JsonProperty("type") String type,
    @JsonProperty("role") String role,
    @JsonProperty("next_step") String nextStep,
    @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
      property = "type"
    ) @JsonSubTypes(
      {
        @JsonSubTypes.Type(value = InferConfig.class, name = "infer"),
        @JsonSubTypes.Type(value = ClassifyConfig.class, name = "classify"),
        @JsonSubTypes.Type(value = EmbedConfig.class, name = "embed"),
        @JsonSubTypes.Type(value = RouteConfig.class, name = "route"),
        @JsonSubTypes.Type(value = GuardConfig.class, name = "guard"),
        @JsonSubTypes.Type(value = LlmGuardConfig.class, name = "llm_guard"),
        @JsonSubTypes.Type(value = BreakConfig.class, name = "break"),
        @JsonSubTypes.Type(value = LoopConfig.class, name = "loop"),
        @JsonSubTypes.Type(value = SubPipelineConfig.class, name = "sub_pipeline"),
        @JsonSubTypes.Type(value = RegexGuardConfig.class, name = "regex_guard"),
        @JsonSubTypes.Type(value = ToolSelectConfig.class, name = "tool_select"),
        @JsonSubTypes.Type(value = TodoConfig.class, name = "todo"),
      }
    ) @JsonProperty("config") StepConfig config
  ) {}

  // ── Step config sealed hierarchy ──────────────────────────────────────────

  public sealed interface StepConfig
    permits
      InferConfig,
      ClassifyConfig,
      EmbedConfig,
      RouteConfig,
      GuardConfig,
      LlmGuardConfig,
      BreakConfig,
      LoopConfig,
      SubPipelineConfig,
      RegexGuardConfig,
      ToolSelectConfig,
      TodoConfig {}

  // ── Infer ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InferConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("prompt") PromptDef prompt,
    @JsonProperty("sampling") SamplingDef sampling,
    @JsonProperty("tags") TagsDef tags,
    @JsonProperty("context") java.util.Map<String, Object> context,
    @JsonProperty("inject_tools") Boolean injectTools,
    // Whether server-owned tools (todo tools) are injected into this step's
    // tool list. Omitted/true = injected; false for prose-only steps.
    @JsonProperty("server_tools") Boolean serverTools,
    @JsonProperty("strip_thinking") Boolean stripThinking,
    // Forward THINKING deltas to the client even for role: internal steps
    // (content/tool deltas stay suppressed). Omitted/false = internal steps
    // stream nothing.
    @JsonProperty("stream_thinking") Boolean streamThinking,
    @JsonProperty("system") String system,
    // Context-window history trimming toggle. Omitted/true = enabled (older
    // turns are dropped to fit the model window); false disables trimming.
    @JsonProperty("trim_history") Boolean trimHistory,
    // Jinja template extracting tool calls from the generated tool span: a
    // built-in name ("chatml-json" | "xml-function" | "gemma-call" |
    // "glm-name-json" | "harmony") or inline
    // Jinja source. Omitted = try the built-ins in order (legacy behavior).
    @JsonProperty("tool_extraction_template") String toolExtractionTemplate,
    // Per-step chat-template override: either the id of a workspace
    // `templates:` entry or inline Jinja source. Omitted = use the model's
    // GGUF-metadata chat template.
    @JsonProperty("chat_template") String chatTemplate
  ) implements StepConfig {}

  // A single message entry in the structured prompt.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MessageEntry(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PromptDef(
    @JsonProperty("messages") List<MessageEntry> messages,
    @JsonProperty("template") String template,
    // Path to a file whose content is used as the template string.
    // Resolved relative to the workspace YAML file's parent directory.
    // Mutually exclusive with template: and template_id:.
    @JsonProperty("template_file") String templateFile,
    // ID of a template declared under templates: in this workspace (or any included file).
    // Resolved at load time to the template's content.
    // Mutually exclusive with template: and template_file:.
    @JsonProperty("template_id") String templateId
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SamplingDef(
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("temperature") float temperature,
    @JsonProperty("top_p") float topP,
    @JsonProperty("presence_penalty") float presencePenalty,
    @JsonProperty("frequency_penalty") float frequencyPenalty,
    @JsonProperty("stop") List<String> stop
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TagsDef(
    // Names this tag set (workspace-level `tags:` entries) or references one
    // (a step whose whole tags value is a string parses as an id-only TagsDef
    // via the delegating creator below; the loader resolves it).
    @JsonProperty("id") String id,
    // Accepts a single string or a list, like tool_open: Harmony enters its non-answer
    // channels as both analysis and commentary.
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_open")
    List<String> reasoningOpen,
    // Boxed: unset must stay distinguishable from false so the engine default applies.
    @JsonProperty("reasoning_repeatable") Boolean reasoningRepeatable,
    // Accepts a single string or a list, like tool_open: a channel may be left more than one
    // way (Harmony reaches the final channel after <|end|> when answering directly and after
    // <|call|> when a tool call intervened).
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_close")
    List<String> reasoningClose,
    // Accepts a single string or a list: a dialect may open the tool channel more than one way
    // (Harmony uses both the commentary and analysis channels).
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("tool_open")
    List<String> toolOpen,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("tool_close")
    List<String> toolClose
  ) {
    /** A bare string as the whole {@code tags:} value is a reference by id. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TagsDef ref(String id) {
      return new TagsDef(id, null, null, null, null, null);
    }

    /** True when this instance is only a reference to a named workspace tag set. */
    public boolean isReference() {
      return (
        id != null &&
        reasoningOpen == null &&
        reasoningClose == null &&
        toolOpen == null &&
        toolClose == null &&
        reasoningRepeatable == null
      );
    }
  }

  // ── Classify ──────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ClassifyConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("threshold") float threshold
  ) implements StepConfig {}

  // ── Todo ──────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TodoConfig(
    // Step to branch to after consuming a todo tool call (usually the infer
    // step that produced it); falls through to next_step when unset.
    @JsonProperty("handled_step") String handledStep
  ) implements StepConfig {}

  // ── Tool select ───────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ToolSelectConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("batch_size") int batchSize,
    @JsonProperty("threshold") float threshold,
    @JsonProperty("label_template") String labelTemplate,
    @JsonProperty("always_include") List<String> alwaysInclude,
    @JsonProperty("trim_descriptions") Boolean trimDescriptions,
    @JsonProperty("description_template") String descriptionTemplate
  ) implements StepConfig {}

  // ── Embed ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmbedConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField
  ) implements StepConfig {}

  // ── Route ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RouteConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("strategy") String strategy,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("default_step") String defaultStep,
    @JsonProperty("rules") List<RouteRuleDef> rules
  ) implements StepConfig {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RouteRuleDef(
    @JsonProperty("label") String label,
    @JsonProperty("sentences") List<String> sentences,
    @JsonProperty("next_step") String nextStep
  ) {}

  // ── Guard ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GuardConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("action") String action,
    @JsonProperty("trigger") TriggerDef trigger,
    @JsonProperty("triggers") List<TriggerDef> triggers,
    @JsonProperty("message") String message,
    @JsonProperty("redact_with_entity_type") boolean redactWithEntityType
  ) implements StepConfig {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TriggerDef(
    @JsonProperty("label") String label,
    @JsonProperty("score") float score
  ) {}

  // ── Regex Guard ───────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexEntityConfig(
    @JsonProperty("name") String name,
    @JsonProperty("pattern") String pattern
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexGuardConfig(
    @JsonProperty("input_field") String inputField,
    // Unified list of {name, pattern} entries. Free-form names (spaces,
    // dashes, etc. are all valid — the executor generates positional named
    // groups internally). Action determines behaviour: REJECT/WARN triggers
    // on any match; REDACT performs character-level span replacement.
    @JsonProperty("patterns") List<RegexEntityConfig> patterns,
    @JsonProperty("redact_with_entity_type") boolean redactWithEntityType,
    @JsonProperty("action") String action,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("message") String message
  ) implements StepConfig {}

  // ── LLM Guard ────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlmGuardConfig(
    @JsonProperty("model_id") String modelId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("action") String action,
    @JsonProperty("safe_token") String safeToken,
    @JsonProperty("prompt") PromptDef prompt,
    @JsonProperty("sampling") SamplingDef sampling,
    @JsonProperty("message") String message,
    @JsonProperty("context") Map<String, Object> context
  ) implements StepConfig {}

  // ── Break ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BreakConfig(
    @JsonProperty("output_field") String outputField,
    @JsonProperty("condition") ConditionDef condition
  ) implements StepConfig {}

  // ── Loop ──────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LoopConfig(
    @JsonProperty("loopback_step") String loopbackStep,
    @JsonProperty("max_iterations") int maxIterations,
    @JsonProperty("fallback_step") String fallbackStep,
    @JsonProperty("condition") ConditionDef condition,
    @JsonProperty("loopback_message") MessageEntry loopbackMessage,
    // Sampling override for retry-edge generations only (see LoopStepConfig proto).
    @JsonProperty("retry_sampling_params") SamplingDef retrySamplingParams
  ) implements StepConfig {}

  // ── Shared condition (break + loop) ───────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConditionDef(
    @JsonProperty("type") String type,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("match_value") Object rawMatchValue,
    @JsonProperty("threshold") float threshold
  ) {
    /**
     * Returns match_value as a string. YAML 1.1 silently parses bare YES/NO/TRUE/FALSE
     * as booleans — this accessor normalises back to the string the user intended.
     */
    public String matchValue() {
      if (rawMatchValue == null) return null;
      if (rawMatchValue instanceof Boolean b) return b ? "YES" : "NO";
      return rawMatchValue.toString();
    }
  }

  // ── Sub-pipeline ──────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SubPipelineConfig(
    @JsonProperty("pipeline_id") String pipelineId,
    @JsonProperty("input_field") String inputField,
    @JsonProperty("output_field") String outputField,
    @JsonProperty("server") String server,
    @JsonProperty("system_prompt") String systemPrompt,
    @JsonProperty("forward_messages") boolean forwardMessages
  ) implements StepConfig {}
}
