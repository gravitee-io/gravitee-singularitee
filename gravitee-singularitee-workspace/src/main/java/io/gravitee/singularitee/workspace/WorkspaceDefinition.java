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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Jackson-deserialisable tree for a Singularitee workspace YAML file.
 *
 * <p>These records are the YAML schema: each {@code @JsonProperty} name is a key a workspace
 * may write. {@link YamlWorkspaceLoader} turns the tree into {@link ModelLoadRequest}s and
 * the pipeline model; defaults noted here are what that mapping applies when a key is absent.
 * Unknown keys are ignored at every level.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceDefinition(
  /** The single top-level {@code workspace:} mapping; required. */
  @JsonProperty("workspace") WorkspaceRoot workspace
) {
  /**
   * Body of the {@code workspace:} mapping.
   *
   * <p>Every section is optional. Sections pulled in through {@code includes:} are appended
   * to the ones declared inline; {@code tags:} comes from the root file only.
   */
  public record WorkspaceRoot(
    /** {@code name}: label used in logs. Falls back to the first included model file's name. */
    @JsonProperty("name") String name,
    /** {@code remote}: gRPC endpoints that {@code remote_*} models and remote pipelines call. */
    @JsonProperty("remote") RemoteConfig remote,
    /** {@code models}: models to publish, in declaration order. */
    @JsonProperty("models") List<ModelDefinition> models,
    /** {@code pipelines}: pipelines to publish, in declaration order. */
    @JsonProperty("pipelines") List<PipelineDefinition> pipelines,
    /** {@code templates}: named Jinja templates steps reference by id. */
    @JsonProperty("templates") List<TemplateDefinition> templates,
    /**
     * {@code tags}: named, reusable reasoning/tool tag sets. A step references one by writing
     * the id as its whole {@code tags:} value ({@code tags: harmony}).
     */
    @JsonProperty("tags") List<TagsDef> tags,
    /** {@code includes}: fragment files merged into this workspace. */
    @JsonProperty("includes") IncludesDef includes,
    /**
     * Any top-level section this module does not model itself: a list of {@code {id, ...}}
     * entries reaches a step's codec untyped through {@code StepCodecContext.section(name)}.
     * A step plugin owns the schema of its own workspace section.
     */
    @JsonAnySetter Map<String, Object> extras
  ) {}

  /**
   * Typed include directives for workspace composition.
   *
   * <p>Each list holds file names or glob patterns resolved against the sibling folder named
   * after the key ({@code models/}, {@code pipelines/}, {@code templates/}). Only the matching
   * section is extracted from each file: a file listed under {@code models:} contributes its
   * {@code workspace.models} list and nothing else. Globs expand alphabetically.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncludesDef(
    /** {@code models}: files under {@code models/} whose {@code workspace.models} are appended. */
    @JsonProperty("models") List<String> models,
    /** {@code pipelines}: files under {@code pipelines/} whose {@code workspace.pipelines} are appended. */
    @JsonProperty("pipelines") List<String> pipelines,
    /** {@code templates}: files under {@code templates/} whose {@code workspace.templates} are appended. */
    @JsonProperty("templates") List<String> templates
  ) {}

  // ── Remote config ──────────────────────────────────────────────────────────

  /** The {@code remote:} section: where remote models and pipelines are served from. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteConfig(
    /** {@code default}: endpoint used when a model or pipeline names no {@code server}. Registered under id {@code default}. */
    @JsonProperty("default") RemoteEndpoint defaultEndpoint,
    /** {@code servers}: named endpoints referenced through {@code server:}. Entries without an id are dropped. */
    @JsonProperty("servers") List<RemoteEndpoint> servers
  ) {}

  /** One remote Singularitee gRPC endpoint. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteEndpoint(
    /** {@code id}: name used by {@code server:} references. */
    @JsonProperty("id") String id,
    /** {@code host}: hostname or IP of the remote gRPC server. */
    @JsonProperty("host") String host,
    /** {@code port}: gRPC port of the remote server. */
    @JsonProperty("port") int port,
    /**
     * {@code http2_keep_alive_timeout}: HTTP/2 keep-alive interval in seconds. {@code -1}
     * (the default) keeps idle connections open indefinitely; any positive value is how long
     * an idle connection is held before the client closes it.
     */
    @JsonProperty("http2_keep_alive_timeout") Integer http2KeepAliveTimeout,
    /** {@code username}: HTTP Basic user sent as gRPC metadata. Unset = no authentication. */
    @JsonProperty("username") String username,
    /** {@code password}: HTTP Basic password paired with {@link #username()}. */
    @JsonProperty("password") String password,
    /**
     * {@code ssl}: reach the endpoint over TLS. Server certificates are validated against the
     * JVM default trust store and ALPN negotiates HTTP/2; client certificates are not
     * supported here. Defaults to {@code false} (plaintext), so Basic credentials on a
     * non-loopback endpoint belong with {@code ssl: true}.
     */
    @JsonProperty("ssl") Boolean ssl
  ) {
    /** Keep-alive value meaning "never close an idle connection". */
    public static final int DEFAULT_HTTP2_KEEP_ALIVE_TIMEOUT = -1;

    /** Plaintext endpoint with default keep-alive and no credentials. */
    public RemoteEndpoint(String id, String host, int port) {
      this(id, host, port, null, null, null, null);
    }

    /** Plaintext endpoint without credentials. */
    public RemoteEndpoint(String id, String host, int port, Integer http2KeepAliveTimeout) {
      this(id, host, port, http2KeepAliveTimeout, null, null, null);
    }

    /** Plaintext endpoint with Basic credentials. */
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
   * A named, reusable Jinja template declared under {@code templates:}.
   *
   * <p>Steps reference it through {@code prompt.template_id:} or {@code chat_template:}. The
   * loader resolves the reference at load time, so the engine always receives the
   * materialised template string and never sees the id.
   *
   * <p>Exactly one of {@code content} or {@code file} must be set; both is a load-time error,
   * neither skips the template with a warning.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TemplateDefinition(
    /** {@code id}: name steps reference. Entries without an id are skipped. */
    @JsonProperty("id") String id,
    /** {@code content}: inline Jinja source. */
    @JsonProperty("content") String content,
    /**
     * {@code file}: path to a Jinja file, resolved against the workspace directory first and
     * the configured templates directory second. Mutually exclusive with {@code content}.
     */
    @JsonProperty("file") String file
  ) {}

  // ── Model ─────────────────────────────────────────────────────────────────

  /**
   * A model published by the workspace.
   *
   * <p>{@code type:} selects which engine block below is read; the others are ignored.
   * {@code remote_*} types are proxies to a {@code remote:} endpoint and {@code regex} /
   * {@code composite_classifier} run in-process without native code.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModelDefinition(
    /** {@code id}: stable logical id pipelines reference. */
    @JsonProperty("id") String id,
    /** {@code name}: HuggingFace repository id or local path the weights load from. */
    @JsonProperty("name") String name,
    /** {@code type}: one of the {@link ModelType} wire names ({@code llama_cpp}, {@code vllm}, ...). Required. */
    @JsonProperty("type") String type,
    /** {@code server}: id of the {@code remote:} endpoint serving a {@code remote_*} model. */
    @JsonProperty("server") String server,
    /**
     * {@code task}: slug callers route on ({@code text-generation}, {@code text-classification},
     * {@code token-classification}, {@code feature-extraction}, {@code reranking}). Unset =
     * the engine reports its own. Any other value fails the workspace.
     */
    @JsonProperty("task") String task,
    /**
     * {@code visible}: catalogue membership. {@code false} drops the model from listings and
     * from HTTP resolution while leaving it callable as a pipeline dependency and over gRPC.
     * Unset = visible.
     */
    @JsonProperty("visible") Boolean visible,
    /**
     * {@code modalities}: subset of {@code text}, {@code image}, {@code audio} the model
     * accepts. Unset = detected from the backend (llama.cpp asks the projector, vLLM reads
     * {@code config.json}); declare it where detection cannot run, such as a {@code remote_*}
     * proxy.
     */
    @JsonProperty("modalities") List<String> modalities,
    /** {@code memory_check}: {@code disabled}, {@code warn} (default) or {@code fail} when the weights may not fit. */
    @JsonProperty("memory_check") String memoryCheck,
    /** {@code download}: narrows which repository files are fetched. */
    @JsonProperty("download") DownloadDef download,
    /** {@code llama_cpp}: engine block for {@code type: llama_cpp}. */
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    /** {@code vllm}: engine block for {@code type: vllm}. */
    @JsonProperty("vllm") VllmDef vllm,
    /** {@code onnx_classifier}: engine block for {@code type: onnx_classifier}. */
    @JsonProperty("onnx_classifier") OnnxClassifierDef onnxClassifier,
    /** {@code onnx_embedding}: engine block for {@code type: onnx_embedding}. */
    @JsonProperty("onnx_embedding") OnnxEmbeddingDef onnxEmbedding,
    /** {@code gliner_classifier}: engine block for {@code type: gliner_classifier}. */
    @JsonProperty("gliner_classifier") GlinerClassifierDef glinerClassifier,
    /** {@code gliner_ner}: engine block for {@code type: gliner_ner}. */
    @JsonProperty("gliner_ner") GlinerNerDef glinerNer,
    /** {@code regex}: patterns for {@code type: regex}. */
    @JsonProperty("regex") RegexDef regex,
    /** {@code composite_classifier}: member models for {@code type: composite_classifier}. */
    @JsonProperty("composite_classifier") CompositeClassifierDef compositeClassifier,
    /** {@code onnx_reranker}: engine block for {@code type: onnx_reranker}. */
    @JsonProperty("onnx_reranker") OnnxRerankerDef onnxReranker,
    /** {@code llama_cpp_embedding}: engine block for {@code type: llama_cpp_embedding}. */
    @JsonProperty("llama_cpp_embedding") LlamaCppEmbeddingDef llamaCppEmbedding,
    /** {@code llama_cpp_reranker}: engine block for {@code type: llama_cpp_reranker}. */
    @JsonProperty("llama_cpp_reranker") LlamaCppRerankerDef llamaCppReranker
  ) {
    /** Returns {@code true} unless the workspace explicitly hid this model. */
    public boolean isVisible() {
      return visible == null || visible;
    }
  }

  /**
   * The {@code download:} block: narrows what is pulled from HuggingFace.
   *
   * <p>Each resolver already drops the formats its engine cannot read. {@code exclude} is for
   * what those rules cannot know: a duplicate in the same format, an unwanted variant, a
   * multi-gigabyte extra.
   *
   * <p>Patterns are globs matched case-insensitively against the repository-relative path:
   * {@code *} within a segment, {@code **} across segments, {@code ?} for one character. A
   * pattern with no {@code /} also matches the bare file name, so {@code "*.pth"} catches
   * {@code original/consolidated.00.pth}.
   *
   * <p>Excludes only narrow the built-in selection and apply where a resolver picks files out
   * of a listing (vLLM, GLiNER, ONNX tokenizer directories). A file named outright in the
   * definition (a GGUF {@code path:}, an ONNX {@code model_path:}) is always fetched.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DownloadDef(
    /** {@code exclude}: glob patterns of repository files to skip. Blank entries are ignored. */
    @JsonProperty("exclude") List<String> exclude
  ) {}

  /**
   * The {@code llama_cpp:} engine block.
   *
   * <p>Numeric zero and empty string leave the engine default in place. Boxed booleans
   * distinguish "unset" from an explicit {@code false}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppDef(
    /** {@code path}: GGUF file name inside the repository, or a local file path. */
    @JsonProperty("path") String path,
    /** {@code n_ctx}: context window per sequence in tokens. Total KV is {@code n_ctx * n_seq_max}. */
    @JsonProperty("n_ctx") int nCtx,
    /** {@code n_batch}: logical batch size for prompt processing. */
    @JsonProperty("n_batch") int nBatch,
    /** {@code n_ubatch}: physical micro-batch size. */
    @JsonProperty("n_ubatch") int nUbatch,
    /** {@code n_seq_max}: concurrent sequences (slots). */
    @JsonProperty("n_seq_max") int nSeqMax,
    /** {@code n_gpu_layers}: layers offloaded to the GPU; a large value offloads all. */
    @JsonProperty("n_gpu_layers") int nGpuLayers,
    /** {@code pooling_type}: llama.cpp pooling enum name ({@code MEAN}, {@code CLS}, {@code LAST}, {@code RANK}). */
    @JsonProperty("pooling_type") String poolingType,
    /** {@code attention_type}: llama.cpp attention enum name. */
    @JsonProperty("attention_type") String attentionType,
    /** {@code flash_attn_type}: llama.cpp flash-attention enum name ({@code AUTO}, {@code ENABLED}, {@code DISABLED}). */
    @JsonProperty("flash_attn_type") String flashAttnType,
    /** {@code offload_kqv}: keep the KV cache on the GPU. Unset = engine default. */
    @JsonProperty("offload_kqv") Boolean offloadKqv,
    /** {@code lora_path}: LoRA adapter applied at load. */
    @JsonProperty("lora_path") String loraPath,
    /** {@code mmproj_path}: multimodal projector file; enables image/audio input. */
    @JsonProperty("mmproj_path") String mmprojPath,
    /** {@code media_marker}: marker injected once per attachment. Unset = the mtmd default. */
    @JsonProperty("media_marker") String mediaMarker,
    /** {@code mtp}: enable multi-token prediction on models that ship a NextN head. Default {@code false}. */
    @JsonProperty("mtp") boolean mtp,
    /** {@code speculative}: draft-model speculative decoding parameters. */
    @JsonProperty("speculative") SpeculativeDef speculative,
    /** {@code use_mlock}: pin the weights in RAM. Unset = engine default. */
    @JsonProperty("use_mlock") Boolean useMlock,
    /** {@code cache_type_k}: KV cache key type (for example {@code q8_0}). */
    @JsonProperty("cache_type_k") String cacheTypeK,
    /** {@code cache_type_v}: KV cache value type; quantised values need flash attention. */
    @JsonProperty("cache_type_v") String cacheTypeV,
    /** {@code prompt_cache}: reuse the KV prefix across requests. Unset = engine default. */
    @JsonProperty("prompt_cache") Boolean promptCache,
    /** {@code prompt_cache_min_tokens}: shortest prefix worth caching. */
    @JsonProperty("prompt_cache_min_tokens") int promptCacheMinTokens,
    /** {@code eog_ramp_start}: fraction of {@code max_tokens} after which end-of-generation is biased up. Unset = disabled. */
    @JsonProperty("eog_ramp_start") Float eogRampStart,
    /** {@code eog_ramp_max_bias}: logit bias reached at the end of the ramp. */
    @JsonProperty("eog_ramp_max_bias") Float eogRampMaxBias,
    /** {@code draft_model}: HuggingFace repository of the speculative draft model. */
    @JsonProperty("draft_model") String draftModel,
    /** {@code draft_path}: GGUF file of the draft model. */
    @JsonProperty("draft_path") String draftPath,
    /** {@code eagle3_model}: HuggingFace repository of an EAGLE-3 draft head. */
    @JsonProperty("eagle3_model") String eagle3Model,
    /** {@code eagle3_path}: GGUF file of the EAGLE-3 draft head. */
    @JsonProperty("eagle3_path") String eagle3Path
  ) {}

  /** The {@code llama_cpp.speculative:} block. Zero leaves the engine default in place. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SpeculativeDef(
    /** {@code n_draft}: tokens drafted per step. */
    @JsonProperty("n_draft") int nDraft,
    /** {@code draft_min}: minimum draft length before verification. */
    @JsonProperty("draft_min") int draftMin,
    /** {@code p_min}: minimum draft token probability to keep drafting. */
    @JsonProperty("p_min") float pMin,
    /** {@code temperature}: draft sampling temperature. Unset = engine default. */
    @JsonProperty("temperature") Float temperature,
    /** {@code top_k}: draft top-k. */
    @JsonProperty("top_k") int topK,
    /** {@code top_p}: draft top-p. Unset = engine default. */
    @JsonProperty("top_p") Float topP,
    /** {@code seed}: draft sampler seed. Unset = engine default. */
    @JsonProperty("seed") Long seed
  ) {}

  /**
   * The {@code vllm:} engine block.
   *
   * <p>Numeric zero and empty string leave the engine default in place.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VllmDef(
    /** {@code dtype}: weight dtype ({@code auto}, {@code bfloat16}, ...). */
    @JsonProperty("dtype") String dtype,
    /** {@code max_model_len}: context window in tokens. */
    @JsonProperty("max_model_len") int maxModelLen,
    /** {@code max_num_seqs}: concurrent sequences. */
    @JsonProperty("max_num_seqs") int maxNumSeqs,
    /** {@code gpu_memory_utilization}: fraction of GPU memory vLLM may claim. */
    @JsonProperty("gpu_memory_utilization") double gpuMemoryUtilization,
    /** {@code max_num_batched_tokens}: tokens per scheduler step. */
    @JsonProperty("max_num_batched_tokens") int maxNumBatchedTokens,
    /** {@code enforce_eager}: disable CUDA graphs. Default {@code false}. */
    @JsonProperty("enforce_eager") boolean enforceEager,
    /** {@code trust_remote_code}: allow the checkpoint's custom Python code. Default {@code false}. */
    @JsonProperty("trust_remote_code") boolean trustRemoteCode,
    /** {@code quantization}: quantisation scheme name. */
    @JsonProperty("quantization") String quantization,
    /** {@code seed}: sampler seed. */
    @JsonProperty("seed") int seed,
    /**
     * {@code enable_prefix_caching}: vLLM native prefix cache. Boxed so an omitted key stays
     * distinct from an explicit {@code false}.
     */
    @JsonProperty("enable_prefix_caching") Boolean enablePrefixCaching,
    /** {@code enable_chunked_prefill}: chunked prefill scheduling. Default {@code false}. */
    @JsonProperty("enable_chunked_prefill") boolean enableChunkedPrefill,
    /** {@code kv_cache_dtype}: KV cache dtype ({@code auto}, {@code fp8}, ...). */
    @JsonProperty("kv_cache_dtype") String kvCacheDtype,
    /** {@code enable_lora}: accept per-request LoRA adapters. Default {@code false}. */
    @JsonProperty("enable_lora") boolean enableLora,
    /** {@code max_loras}: adapters resident at once. */
    @JsonProperty("max_loras") int maxLoras,
    /** {@code max_lora_rank}: highest adapter rank accepted. */
    @JsonProperty("max_lora_rank") int maxLoraRank,
    /** {@code enable_sleep_mode}: allow the engine to release GPU memory while idle. Unset = engine default. */
    @JsonProperty("enable_sleep_mode") Boolean enableSleepMode,
    /**
     * {@code tensor_parallel_size}: GPUs per model replica. Zero falls back to the server-wide
     * {@code ai.vllm.*} setting, then to vLLM's default.
     */
    @JsonProperty("tensor_parallel_size") int tensorParallelSize,
    /** {@code pipeline_parallel_size}: pipeline stages. Zero falls back like {@code tensor_parallel_size}. */
    @JsonProperty("pipeline_parallel_size") int pipelineParallelSize,
    /** {@code distributed_executor_backend}: {@code mp} or {@code ray}. Empty falls back like {@code tensor_parallel_size}. */
    @JsonProperty("distributed_executor_backend") String distributedExecutorBackend,
    /** {@code prompt_cache}: alias for {@code enable_prefix_caching}; either key enables the prefix cache. */
    @JsonProperty("prompt_cache") Boolean promptCache
  ) {}

  /** The {@code onnx_classifier:} engine block (BERT-family sequence or token classifier). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxClassifierDef(
    /** {@code model_path}: ONNX graph, repository-relative or local. */
    @JsonProperty("model_path") String modelPath,
    /** {@code tokenizer_path}: {@code tokenizer.json}, repository-relative or local. */
    @JsonProperty("tokenizer_path") String tokenizerPath,
    /** {@code config_json_path}: {@code config.json} carrying {@code id2label}. */
    @JsonProperty("config_json_path") String configJsonPath,
    /** {@code labels}: explicit label list overriding {@code id2label}. */
    @JsonProperty("labels") List<String> labels,
    /** {@code max_sequence_length}: truncation length in tokens. Zero = engine default. */
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    /** {@code classifier_mode}: {@code SEQUENCE} (whole input, default) or {@code TOKEN} (spans / NER). */
    @JsonProperty("classifier_mode") String classifierMode
  ) {}

  /** The {@code onnx_embedding:} engine block. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxEmbeddingDef(
    /** {@code model_path}: ONNX graph, repository-relative or local. */
    @JsonProperty("model_path") String modelPath,
    /** {@code tokenizer_path}: {@code tokenizer.json}, repository-relative or local. */
    @JsonProperty("tokenizer_path") String tokenizerPath,
    /** {@code config_json_path}: the model's {@code config.json}. */
    @JsonProperty("config_json_path") String configJsonPath,
    /** {@code max_sequence_length}: truncation length in tokens. Zero = engine default. */
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    /** {@code pooling_mode}: {@code MEAN} (default) or {@code CLS}; follow the model card. */
    @JsonProperty("pooling_mode") String poolingMode,
    /** {@code normalize}: L2-normalise vectors. Default {@code false}. */
    @JsonProperty("normalize") boolean normalize
  ) {}

  /** The {@code onnx_reranker:} engine block (cross-encoder). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OnnxRerankerDef(
    /** {@code model_path}: ONNX graph, repository-relative or local. */
    @JsonProperty("model_path") String modelPath,
    /** {@code tokenizer_path}: {@code tokenizer.json}, repository-relative or local. */
    @JsonProperty("tokenizer_path") String tokenizerPath,
    /** {@code config_json_path}: the model's {@code config.json}. */
    @JsonProperty("config_json_path") String configJsonPath,
    /** {@code max_sequence_length}: truncation length for the query/document pair. Zero = engine default. */
    @JsonProperty("max_sequence_length") int maxSequenceLength,
    /** {@code scoring}: {@code SIGMOID}, {@code SOFTMAX} or {@code LOGIT}. Unset = auto-detect from the head. */
    @JsonProperty("scoring") String scoring
  ) {}

  /**
   * The {@code llama_cpp_embedding:} engine block.
   *
   * <p>Wraps a {@link LlamaCppDef} for engine parameters plus an optional prompt template for
   * instruction-aware models such as Qwen3-Embedding.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppEmbeddingDef(
    /** {@code llama_cpp}: nested engine block; {@code pooling_type} selects the pooling. */
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    /**
     * {@code embedding_template}: wraps the raw text before tokenisation, with {@code {text}}
     * as the placeholder. Leave unset for models that accept plain text.
     */
    @JsonProperty("embedding_template") String embeddingTemplate
  ) {}

  /**
   * The {@code llama_cpp_reranker:} engine block (cross-encoder).
   *
   * <p>The GGUF must export a classifier head and the nested block must set
   * {@code pooling_type: RANK}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LlamaCppRerankerDef(
    /** {@code llama_cpp}: nested engine block. */
    @JsonProperty("llama_cpp") LlamaCppDef llamaCpp,
    /**
     * {@code scoring}: {@code SIGMOID} (1-logit heads), {@code SOFTMAX} (2-class heads) or
     * {@code LOGIT} (raw score). Unset = auto-detect.
     */
    @JsonProperty("scoring") String scoring,
    /**
     * {@code rerank_template}: prompt for chat-style rerankers, with {@code {query}} and
     * {@code {document}} placeholders. Unset = plain concatenation (BERT-family models).
     */
    @JsonProperty("rerank_template") String rerankTemplate
  ) {}

  /** One zero-shot label of a {@code gliner_classifier}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerLabelDef(
    /** {@code name}: label returned in results. */
    @JsonProperty("name") String name,
    /** {@code description}: natural-language gloss the model matches against. */
    @JsonProperty("description") String description
  ) {}

  /** The {@code gliner_classifier:} engine block (zero-shot sequence classification). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerClassifierDef(
    /** {@code model_dir}: directory holding the ONNX export and tokenizer. */
    @JsonProperty("model_dir") String modelDir,
    /** {@code labels}: candidate labels; requests may override them. */
    @JsonProperty("labels") List<GlinerLabelDef> labels,
    /** {@code threshold}: minimum score for a label to be reported. Zero = engine default. */
    @JsonProperty("threshold") float threshold,
    /** {@code variant}: runtime variant subfolder ({@code onnx}, {@code onnx_fp16}, ...). */
    @JsonProperty("variant") String variant,
    /** {@code token_cap}: maximum tokens per chunk, sent when positive. */
    @JsonProperty("token_cap") int tokenCap
  ) {}

  /** One entity type of a {@code gliner_ner} model. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerEntityDef(
    /** {@code name}: entity label returned on each span. */
    @JsonProperty("name") String name,
    /** {@code description}: natural-language gloss the model matches against. */
    @JsonProperty("description") String description
  ) {}

  /** The {@code gliner_ner:} engine block (zero-shot span extraction). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GlinerNerDef(
    /** {@code model_dir}: directory holding the ONNX export and tokenizer. */
    @JsonProperty("model_dir") String modelDir,
    /** {@code entities}: entity types to extract. */
    @JsonProperty("entities") List<GlinerEntityDef> entities,
    /** {@code threshold}: minimum span score. Zero = engine default. */
    @JsonProperty("threshold") float threshold,
    /** {@code variant}: runtime variant subfolder ({@code onnx}, {@code onnx_fp16}, ...). */
    @JsonProperty("variant") String variant,
    /** {@code token_cap}: maximum tokens per chunk, sent when positive. */
    @JsonProperty("token_cap") int tokenCap
  ) {}

  // ── Client-local model defs (pure Java, no proto) ─────────────────────────

  /**
   * One pattern of a {@code regex} model. The entity type surfaces as the {@code label} of
   * every result the engine emits and drives guard trigger matching and REDACT replacement
   * (for example {@code [SSN]}, {@code [EMAIL]}).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexPatternDef(
    /** {@code pattern}: Java regular expression. */
    @JsonProperty("pattern") String pattern,
    /** {@code entity_type}: label attached to every match. */
    @JsonProperty("entity_type") String entityType
  ) {}

  /** The {@code regex:} block of a client-local regex classifier. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegexDef(
    /** {@code patterns}: patterns evaluated on every input. */
    @JsonProperty("patterns") List<RegexPatternDef> patterns
  ) {}

  /**
   * The {@code composite_classifier:} block: a client-local classifier that fans out to other
   * classifiers declared in the workspace and merges their results.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CompositeClassifierDef(
    /**
     * {@code models}: ids of classifier models in this workspace ({@code regex}, another
     * composite, or any remote/ONNX/GLiNER classifier).
     */
    @JsonProperty("models") List<String> models
  ) {}

  // ── Pipeline ──────────────────────────────────────────────────────────────

  /**
   * A pipeline published by the workspace.
   *
   * <p>With {@code server:} set the pipeline is a proxy for one of the same id on that remote
   * endpoint and {@code steps} are ignored; otherwise {@code entry} and {@code steps} define a
   * local DAG.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PipelineDefinition(
    /** {@code id}: stable pipeline id; what clients call and what sub-pipeline steps reference. */
    @JsonProperty("id") String id,
    /** {@code name}: human-readable label. Defaults to the id (suffixed for remote proxies). */
    @JsonProperty("name") String name,
    /** {@code server}: id of the {@code remote:} endpoint that runs this pipeline. Unset = local. */
    @JsonProperty("server") String server,
    /** {@code entry}: id of the first step to execute. */
    @JsonProperty("entry") String entry,
    /** {@code task}: published task slug. Unset = derived from the model behind the {@code role: output} step. */
    @JsonProperty("task") String task,
    /** {@code visible}: {@code false} hides the pipeline from listings and HTTP resolution. Unset = visible. */
    @JsonProperty("visible") Boolean visible,
    /** {@code modalities}: accepted input modalities. Unset = union over the model-bound steps. */
    @JsonProperty("modalities") List<String> modalities,
    /** {@code steps}: the DAG nodes; order carries no execution meaning. */
    @JsonProperty("steps") List<StepDefinition> steps,
    /** {@code remote}: options for the proxy call when {@code server} is set. */
    @JsonProperty("remote") RemoteProxyDef remote
  ) {
    /** Returns {@code true} if this pipeline is a remote reference (no local steps). */
    public boolean isRemote() {
      return server != null && !server.isBlank();
    }

    /** Returns {@code true} unless the workspace explicitly hid this pipeline. */
    public boolean isVisible() {
      return visible == null || visible;
    }
  }

  /** The {@code remote:} block of a remote pipeline proxy. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RemoteProxyDef(
    /** {@code system_prompt}: system message prepended on the remote call, replacing any existing one. */
    @JsonProperty("system_prompt") String systemPrompt,
    /** {@code forward_messages}: send the full chat transcript instead of the flat prompt. Default {@code false}. */
    @JsonProperty("forward_messages") boolean forwardMessages
  ) {}

  // ── Step ──────────────────────────────────────────────────────────────────

  /**
   * One node of a pipeline DAG.
   *
   * <p>{@code type} selects the codec that parses {@code config}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StepDefinition(
    /**
     * {@code id}: step id, also the Jinja identifier under which its output is exposed
     * ({@code {{ my_step.output }}}); must match {@code [A-Za-z_][A-Za-z0-9_]*}.
     */
    @JsonProperty("id") String id,
    /** {@code type}: the step type string a plugin provides ({@code infer}, {@code classify}, ...). Required. */
    @JsonProperty("type") String type,
    /** {@code role}: {@code output} (default), {@code thinking} or {@code internal}; only meaningful on {@code infer}. */
    @JsonProperty("role") String role,
    /** {@code next_step}: id of the step that follows on the plain edge; for {@code loop}, the exit step. */
    @JsonProperty("next_step") String nextStep,
    /**
     * {@code config}: the type-specific block, kept raw. The step type's codec (owned by the
     * plugin providing the type) parses and validates it at load.
     */
    @JsonProperty("config") java.util.Map<String, Object> config
  ) {}

  // ── Tags ──────────────────────────────────────────────────────────────────

  /**
   * A reasoning/tool tag set: the {@code tags:} block of an infer step or an entry of the
   * workspace {@code tags:} list.
   *
   * <p>Each marker key accepts a single string or a list; the first entry is the primary
   * marker and the rest are alternatives matched alongside it.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TagsDef(
    /**
     * {@code id}: names a workspace-level entry, or references one when a step's whole
     * {@code tags:} value is a bare string (parsed through {@link #ref(String)}).
     */
    @JsonProperty("id") String id,
    /** {@code reasoning_open}: marker(s) opening the reasoning channel. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_open")
    List<String> reasoningOpen,
    /** {@code reasoning_repeatable}: whether the reasoning channel may reopen within one generation. Unset = engine default. */
    @JsonProperty("reasoning_repeatable") Boolean reasoningRepeatable,
    /** {@code reasoning_close}: marker(s) closing the reasoning channel. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("reasoning_close")
    List<String> reasoningClose,
    /** {@code tool_open}: marker(s) opening the tool-call channel. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @JsonProperty("tool_open")
    List<String> toolOpen,
    /** {@code tool_close}: marker(s) closing the tool-call channel. */
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
}
