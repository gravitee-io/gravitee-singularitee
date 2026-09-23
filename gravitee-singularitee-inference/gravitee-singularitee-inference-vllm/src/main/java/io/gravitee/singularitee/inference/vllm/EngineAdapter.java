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
package io.gravitee.singularitee.inference.vllm;

import io.gravitee.singularitee.inference.api.memory.InsufficientVramException;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import io.gravitee.singularitee.inference.api.textgen.AudioContent;
import io.gravitee.singularitee.inference.api.textgen.Content;
import io.gravitee.singularitee.inference.api.textgen.ImageContent;
import io.gravitee.singularitee.inference.api.textgen.InferencePerformance;
import io.gravitee.singularitee.inference.api.textgen.PromptStats;
import io.gravitee.singularitee.inference.api.textgen.Role;
import io.gravitee.singularitee.inference.api.textgen.StructuredOutput;
import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import io.gravitee.singularitee.inference.api.textgen.UnsupportedStructuredOutputException;
import io.gravitee.vllm.engine.CompletionOutput;
import io.gravitee.vllm.engine.GuidedDecodingParams;
import io.gravitee.vllm.engine.LoraRequest;
import io.gravitee.vllm.engine.ModelIntrospection;
import io.gravitee.vllm.engine.MultiModalData;
import io.gravitee.vllm.engine.RequestOutput;
import io.gravitee.vllm.engine.SamplingParams;
import io.gravitee.vllm.engine.VllmEngine;
import io.gravitee.vllm.engine.VllmEngineBuilder;
import io.gravitee.vllm.iterator.VllmIterator;
import io.gravitee.vllm.iterator.VllmOutput;
import io.gravitee.vllm.platform.PlatformResolver;
import io.gravitee.vllm.platform.VllmBackend;
import io.gravitee.vllm.runtime.PythonRuntime;
import io.gravitee.vllm.state.ConversationState;
import java.lang.foreign.Arena;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine adapter for the vLLM backend.
 *
 * <p>Bridges the inference API's {@code EngineAdapter} contract with the vLLM4j
 * {@link VllmEngine} and {@link VllmIterator}. vLLM runs its own continuous
 * batching inside the Python engine; this adapter drives the iterator, which
 * calls {@code engine.step()} and extracts per-token deltas.
 *
 * <p>Constructing an instance initialises the CPython runtime and loads the
 * model; the instance is not thread-safe and is owned by a single
 * {@link BatchEngine}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EngineAdapter
  implements
    io.gravitee.singularitee.inference.api.EngineAdapter<
      VllmConfig,
      VllmRequest,
      String,
      EngineAdapter.VllmSequenceState
    > {

  private static final Logger LOGGER = LoggerFactory.getLogger(EngineAdapter.class);

  /**
   * Fallback budget when the context window is unknown: large enough that a
   * reasoning model can finish (vLLM's own default is 16).
   */
  private static final int DEFAULT_MAX_TOKENS = 4096;

  /** Rough bytes-per-token for the prompt estimate; vLLM enforces the real limit. */
  private static final int APPROX_CHARS_PER_TOKEN = 4;

  private final VllmEngine engine;
  private final VllmIterator iterator;

  /** Lazily-read context window; -1 until first use. */
  private volatile int contextWindow = -1;

  /** Tracks per-sequence state keyed by internal ID. */
  private final Map<Integer, VllmSequenceState> states = new ConcurrentHashMap<>();

  /** Buffer for the latest output from the iterator. */
  private final AtomicReference<VllmOutput> currentOutput = new AtomicReference<>();

  /**
   * Builds the vLLM engine from {@code config}.
   *
   * <p>Applies platform and GPU gating, initialises the CPython runtime, runs the
   * VRAM pre-flight, then constructs the engine. Throws
   * {@link InsufficientVramException} when the pre-flight fails under
   * {@code memory_check: fail}, or when the weights alone exceed the budget.
   */
  public EngineAdapter(VllmConfig config) {
    VllmEngineBuilder builder = VllmEngine.builder().dtype(resolveDtype(config.dtype()));

    // The weights are fetched in Java before we get here, so point vLLM at the
    // directory rather than a repo id: one download path, one cache, and no
    // network needed at load time.
    if (config.modelPath() != null) {
      builder.modelPath(config.modelPath());
    } else {
      builder.model(config.model());
    }

    if (config.maxModelLen() > 0) builder.maxModelLen(config.maxModelLen());
    if (config.maxNumSeqs() > 0) builder.maxNumSeqs(config.maxNumSeqs());
    if (config.gpuMemoryUtilization() > 0) builder.gpuMemoryUtilization(
      config.gpuMemoryUtilization()
    );
    if (config.maxNumBatchedTokens() > 0) builder.maxNumBatchedTokens(config.maxNumBatchedTokens());
    if (config.enforceEager()) builder.enforceEager(true);
    if (config.trustRemoteCode()) builder.trustRemoteCode(true);
    if (config.quantization() != null) builder.quantization(config.quantization());
    if (config.swapSpace() > 0) builder.swapSpace(config.swapSpace());
    if (config.seed() != null) builder.seed(config.seed());
    // Three-valued, unlike the other booleans, because a backend can need it OFF.
    // vllm-metal's paged-attention runtime loses the RequestState for cached
    // requests and answers every decode step with placeholder token id 0, so
    // generation produces nothing until the window fills. On Metal an unconfigured
    // workspace gets it turned off before the interpreter starts; an explicit value
    // always wins. LoRA is the exception: that backend serves LoRA only from the
    // paged path (LLMEngine.from_engine_args: LoRA on Metal requires paged attention).
    //
    // This is a policy choice, so it lives here rather than in vLLM4j, which must
    // stay usable by callers that need LoRA.
    if (PlatformResolver.backend() == VllmBackend.METAL && !config.enableLora()) {
      PythonRuntime.setEnv("VLLM_METAL_USE_PAGED_ATTENTION", "0");
    }

    if (config.enablePrefixCaching() != null) {
      builder.enablePrefixCaching(config.enablePrefixCaching());
    } else if (PlatformResolver.backend() == VllmBackend.METAL) {
      LOGGER.info(
        "Disabling prefix caching: the Metal backend desyncs on cached requests " +
          "and emits placeholder tokens. Set enable_prefix_caching explicitly to override."
      );
      builder.enablePrefixCaching(false);
    }
    // Only ever turn it ON, like every other boolean here. The proto field is a
    // plain bool, so an unset enable_chunked_prefill arrives as false and cannot
    // be told apart from an explicit disable. Forwarding false would disable
    // chunked prefill on models that do not support running without it (vLLM
    // warns the engine may crash or produce incorrect output). Left unset, vLLM
    // picks its own default.
    if (config.enableChunkedPrefill()) builder.enableChunkedPrefill(true);
    if (config.kvCacheDtype() != null) builder.kvCacheDtype(config.kvCacheDtype());
    if (config.enableLora()) {
      builder.enableLora(true);
      if (config.maxLoras() > 0) builder.maxLoras(config.maxLoras());
      if (config.maxLoraRank() > 0) builder.maxLoraRank(config.maxLoraRank());
    }
    if (config.venvPath() != null) builder.venvPath(config.venvPath());
    if (config.enableSleepMode() != null) builder.enableSleepMode(config.enableSleepMode());

    // Distributed inference. These arrive as plain configuration; resolving
    // them from the environment is the server's job, not this library's.
    if (config.tensorParallelSize() > 0) builder.tensorParallelSize(config.tensorParallelSize());
    if (config.pipelineParallelSize() > 0) builder.pipelineParallelSize(
      config.pipelineParallelSize()
    );
    if (
      config.distributedExecutorBackend() != null && !config.distributedExecutorBackend().isBlank()
    ) builder.distributedExecutorBackend(config.distributedExecutorBackend());

    if (config.hfToken() != null && !config.hfToken().isBlank()) {
      PythonRuntime.setEnv("HF_TOKEN", config.hfToken());
      PythonRuntime.setEnv("HUGGING_FACE_HUB_TOKEN", config.hfToken());
    }

    // Pre-Ampere cards cannot run FlashInfer's sampler, and vLLM's sampler
    // imports the FlashInfer backend unconditionally unless this says otherwise,
    // so on such a card the engine fails to start with either a JIT build error
    // (package present) or ModuleNotFoundError (package removed, which is what
    // scripts/setup-venv.sh does since the attention selector only skips
    // FlashInfer when it is not importable).
    //
    // Set before the vLLM import below: vllm.envs snapshots this at first read.
    if (GpuCapability.isPreAmpere()) {
      PythonRuntime.setEnv("VLLM_USE_FLASHINFER_SAMPLER", "0");
      boolean pinnedAttention = pinTritonAttention();
      LOGGER.info(
        "Compute capability {} is pre-Ampere: disabling the FlashInfer sampler{}",
        GpuCapability.lowest().orElse(0),
        pinnedAttention ? " and pinning the Triton attention backend" : ""
      );
    }

    // Initialize CPython runtime before the memory check so that
    // GpuMemoryQuery can safely acquire the GIL via PyGILState_Ensure.
    // Without this, calling PyGILState_Ensure before Py_InitializeEx
    // dereferences a NULL PyThreadState and crashes with SIGSEGV.
    builder.initRuntime();

    runMemoryCheck(config);
    this.engine = builder.build();
    this.iterator = new VllmIterator(engine);
  }

  /**
   * Whether any marker in {@code tags} is a special token, and therefore only
   * reaches the tag FSM when detokenization keeps special tokens.
   *
   * <p>Detected from the marker text rather than from a per-model flag: a
   * workspace that writes {@code <|channel|>analysis<|message|>} has already
   * named its dialect, and a second setting could drift from it. Accepts
   * {@code null}.
   */
  static boolean needsSpecialTokens(TagConfig tags) {
    if (tags == null || !tags.isConfigured()) {
      return false;
    }
    return Stream.concat(tags.allOpenTokens().stream(), tags.allCloseTokens().stream())
      .filter(Objects::nonNull)
      .anyMatch(marker -> marker.contains("<|"));
  }

  /** vLLM4j's knob for vLLM's {@code attention_backend} engine arg. */
  private static final String ATTENTION_BACKEND_PROPERTY = "vllm4j.attentionBackend";

  /**
   * Pins the attention backend to Triton on a pre-Ampere GPU.
   *
   * <p>vLLM auto-selects FlashInfer there (FLASH_ATTN requires sm_80+, and
   * FlashInfer's own {@code supports_compute_capability()} claims Turing
   * support), but its kernels either fail to JIT-build against the CUDA 13
   * toolchain they pull in, or fail at runtime with "BatchPrefillWithPagedKVCache
   * failed with error invalid argument".
   *
   * <p>vLLM 0.23 dropped the attention-backend environment variable, so this goes
   * through vLLM4j, which forwards the system property to the engine arg. Removing
   * flashinfer from the venv instead would change a shared environment and cost
   * performance on an Ampere card.
   *
   * @return {@code true} if this call pinned the backend; {@code false} when an
   *         explicit property or {@code VLLM4J_ATTENTION_BACKEND} already chose
   *         one, which always wins
   */
  private static boolean pinTritonAttention() {
    if (
      System.getProperty(ATTENTION_BACKEND_PROPERTY) != null ||
      System.getenv("VLLM4J_ATTENTION_BACKEND") != null
    ) {
      return false;
    }
    System.setProperty(ATTENTION_BACKEND_PROPERTY, "TRITON_ATTN");
    return true;
  }

  /**
   * Resolves {@code dtype: auto} to {@code float16} on a pre-Ampere GPU.
   *
   * <p>"auto" means "whatever the checkpoint says", and current checkpoints say
   * {@code bfloat16}, which compute capability &lt; 8.0 does not implement, so
   * vLLM rejects the load outright. Without this, every workspace would need
   * {@code dtype: float16} spelled out to run on such a card.
   *
   * <p>An explicit {@code dtype:} is always honoured, including {@code bfloat16}:
   * vLLM then reports the error rather than a silent substitution.
   */
  private static String resolveDtype(String configured) {
    boolean auto =
      configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured.trim());
    if (!auto || !GpuCapability.isPreAmpere()) {
      return configured;
    }
    LOGGER.info(
      "dtype 'auto' resolved to float16: compute capability {} has no bfloat16 support",
      GpuCapability.lowest().orElse(0)
    );
    return "float16";
  }

  /** Returns the raw chat template string from the HuggingFace tokenizer. */
  public String chatTemplateString() {
    return engine.getChatTemplate();
  }

  /** Beginning-of-sequence token text as declared by the tokenizer. */
  public String bosToken() {
    return engine.getBosToken();
  }

  /** End-of-sequence token text as declared by the tokenizer. */
  public String eosToken() {
    return engine.getEosToken();
  }

  /** Context window vLLM resolved for this model, in tokens (0 if unknown). */
  public int maxModelLen() {
    return engine.maxModelLen();
  }

  /** Every special token the tokenizer declares. */
  public List<String> allSpecialTokens() {
    return engine.allSpecialTokens();
  }

  /**
   * Counts tokens with the model's own tokenizer.
   *
   * <p>Takes the GIL for the round-trip; not for use on the per-token hot path.
   *
   * @return the exact token count, 0 for empty input, or -1 when it cannot be
   *         determined
   */
  public int countTokens(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    try {
      return engine.encode(text).size();
    } catch (RuntimeException e) {
      LOGGER.debug("Token count failed, caller will fall back: {}", e.getMessage());
      return -1;
    }
  }

  /**
   * VRAM pre-flight according to {@code memoryCheckPolicy}. Must run after the
   * CPython runtime is initialised, since the GPU query takes the GIL.
   */
  private static void runMemoryCheck(VllmConfig config) {
    MemoryCheckPolicy policy = config.memoryCheckPolicy();
    if (policy == null || policy == MemoryCheckPolicy.DISABLED) {
      LOGGER.debug("Memory pre-flight check disabled for model {}", config.model());
      return;
    }

    // The model's shape is read from its config.json rather than asked of the
    // operator: nobody should have to hand-copy layer counts and head dimensions
    // into a workspace to get a VRAM warning. Explicit configuration still wins,
    // so an unusual model can be described by hand.
    Shape shape = resolveShape(config);

    MemoryEstimate estimate = VllmMemoryEstimator.estimate(
      shape.totalParams(),
      shape.bitsPerParam(),
      shape.numHiddenLayers(),
      shape.numKvHeads(),
      shape.headDim(),
      shape.contextLength(),
      config.maxNumSeqs(),
      config.gpuMemoryUtilization(),
      shape.multimodal()
    );
    if (estimate.isUnknown()) {
      LOGGER.warn(
        "Memory pre-flight for model {}: skipped, could not determine the model shape " +
          "or query CUDA memory. Set total_params/bytes_per_param explicitly to force a check.",
        config.model()
      );
      return;
    }
    if (estimate.willFit()) {
      LOGGER.info("Memory pre-flight for model {}: {}", config.model(), estimate.toHumanReadable());
      return;
    }
    if (policy == MemoryCheckPolicy.FAIL) {
      throw new InsufficientVramException(config.model(), estimate);
    }

    // WARN means "the estimate says it is tight, proceed anyway", which is
    // reasonable for the approximate KV-cache half of the estimate. The weights
    // are not approximate: their size comes from the checkpoint and vLLM must
    // hold all of them at once, so a budget that cannot cover the weights is a
    // certain failure.
    //
    // Reported here rather than left to the engine because vLLM's own error,
    // "No available memory for the cache blocks", names neither the budget, nor
    // the weights, nor the setting that produced it.
    double weightsGb = weightsGb(shape);
    if (weightsExceedBudget(weightsGb, estimate)) {
      throw new InsufficientVramException(
        config.model(),
        estimate,
        String.format(
          "gpu_memory_utilization=%.0f%% of %.2f GiB leaves %.2f GiB usable, but the weights " +
            "alone need %.2f GiB. %s",
          config.gpuMemoryUtilization() * 100,
          estimate.totalGb(),
          estimate.usableGb(),
          weightsGb,
          estimate.suggestion()
        )
      );
    }

    LOGGER.warn("Memory pre-flight for model {}: {}", config.model(), estimate.toHumanReadable());
  }

  private static double weightsGb(Shape shape) {
    return (shape.totalParams() * (shape.bitsPerParam() / 8.0)) / (1024.0 * 1024.0 * 1024.0);
  }

  /**
   * Whether the weights alone overflow the budget, the deterministic part of an
   * otherwise approximate estimate. Guards against the unknown sentinel, whose
   * zeroed figures would otherwise read as "nothing fits".
   */
  static boolean weightsExceedBudget(double weightsGb, MemoryEstimate estimate) {
    return !estimate.isUnknown() && weightsGb > 0 && weightsGb > estimate.usableGb();
  }

  /** The model dimensions the VRAM estimate needs, however they were obtained. */
  private record Shape(
    long totalParams,
    int bitsPerParam,
    int numHiddenLayers,
    int numKvHeads,
    int headDim,
    int contextLength,
    boolean multimodal
  ) {}

  /**
   * Merges what the workspace states with what {@code config.json} says.
   *
   * <p>Configuration wins field by field, so a checkpoint whose config is
   * missing or unusual can still be described by hand; everything left unset
   * comes from the model itself. Introspection is skipped entirely when the
   * workspace already supplies the two fields the estimate cannot run without.
   */
  private static Shape resolveShape(VllmConfig config) {
    boolean configured = config.totalParams() > 0 && config.bytesPerParam() > 0;
    ModelIntrospection.ModelShape read = ModelIntrospection.ModelShape.UNKNOWN;
    if (!configured) {
      // Confined and short-lived: the arena only backs the native strings handed
      // to CPython, which copies them, so nothing outlives this call.
      try (Arena introspectionArena = Arena.ofConfined()) {
        // Read the local copy when there is one: no Hub round-trip, and it
        // works in an air-gapped deployment.
        String target = config.modelPath() != null ? config.modelPath().toString() : config.model();
        read = ModelIntrospection.read(introspectionArena, target, config.trustRemoteCode());
      }
    }

    if (!configured && read.isUsable()) {
      LOGGER.debug(
        "Read shape for {} from config.json: {} params @ {} bits, {} layers, {} KV heads, head dim {}",
        config.model(),
        read.totalParams(),
        read.bitsPerParam(),
        read.numHiddenLayers(),
        read.numKvHeads(),
        read.headDim()
      );
    }

    // A locally-resolved model has its weights on disk, so measure them instead
    // of asking the Hub for a parameter count it cannot give for a directory.
    // File sizes are also strictly better than params x width: they already
    // account for quantization, and for exactly which shards were downloaded.
    long weightBytes = 0;
    // The workspace speaks bytes; the introspected shape speaks bits so that
    // 4-bit quantized checkpoints are not overstated by a rounded-up byte.
    int bitsPerParam = config.bytesPerParam() > 0
      ? config.bytesPerParam() * 8
      : read.bitsPerParam();
    long totalParams = config.totalParams() > 0 ? config.totalParams() : read.totalParams();
    if (totalParams <= 0 && config.modelPath() != null) {
      weightBytes = weightBytesOnDisk(config.modelPath());
      if (weightBytes > 0 && bitsPerParam > 0) {
        totalParams = (weightBytes * 8L) / bitsPerParam;
      }
    }

    int contextLength = config.maxModelLen() > 0
      ? config.maxModelLen()
      : firstPositive(config.maxPositionEmbeddings(), read.maxPositionEmbeddings(), 4096);

    return new Shape(
      totalParams,
      bitsPerParam,
      firstPositive(config.numHiddenLayers(), read.numHiddenLayers(), 0),
      firstPositive(config.numKvHeads(), read.numKvHeads(), 0),
      firstPositive(config.headDim(), read.headDim(), 0),
      contextLength,
      config.multimodal() || read.multimodal()
    );
  }

  /**
   * Total size of the weight files in a local model directory.
   *
   * <p>Counts safetensors, or {@code .bin} when the directory predates them.
   * Returns 0 if the directory cannot be read, which downgrades the estimate to
   * "unknown" rather than inventing a number.
   */
  private static long weightBytesOnDisk(java.nio.file.Path modelDir) {
    try (var files = java.nio.file.Files.list(modelDir)) {
      return files
        .filter(java.nio.file.Files::isRegularFile)
        .filter(p -> {
          String name = p.getFileName().toString().toLowerCase(java.util.Locale.ENGLISH);
          return name.endsWith(".safetensors") || name.endsWith(".bin");
        })
        .mapToLong(p -> {
          try {
            return java.nio.file.Files.size(p);
          } catch (java.io.IOException e) {
            return 0L;
          }
        })
        .sum();
    } catch (java.io.IOException e) {
      LOGGER.debug("Could not measure weights in {}: {}", modelDir, e.getMessage());
      return 0L;
    }
  }

  /** First value greater than zero, else {@code fallback}. */
  private static int firstPositive(int preferred, int alternative, int fallback) {
    if (preferred > 0) return preferred;
    if (alternative > 0) return alternative;
    return fallback;
  }

  /** vLLM enforces every format natively; its GBNF-style grammar always starts at {@code root}. */
  public static GuidedDecodingParams guidedDecoding(StructuredOutput format) {
    return switch (format) {
      case StructuredOutput.JsonSchema(String schema) -> GuidedDecodingParams.json(schema);
      case StructuredOutput.JsonObject() -> GuidedDecodingParams.jsonObject();
      case StructuredOutput.Choice(var values) -> GuidedDecodingParams.choice(values);
      case StructuredOutput.Regex(String pattern) -> GuidedDecodingParams.regex(pattern);
      case StructuredOutput.Grammar(String text, String root) -> {
        if (!StructuredOutput.DEFAULT_ROOT.equals(root)) {
          throw new UnsupportedStructuredOutputException(
            "grammar: the vLLM engine requires the start rule to be named `root`, got `" +
              root +
              "`"
          );
        }
        yield GuidedDecodingParams.grammar(text);
      }
    };
  }

  @Override
  public VllmSequenceState createSequenceState(int internalId, VllmRequest request)
    throws Exception {
    // Prompt must be pre-rendered by the caller: this adapter does not template.
    String prompt = request.prompt();
    MultiModalData multiModalData = null;

    if (request.hasMessages() && request.messages() != null) {
      multiModalData = extractMultiModalData(request.messages());
    }

    if (prompt == null || prompt.isBlank()) {
      LOGGER.error(
        "Cannot create sequence state: prompt is empty for internalId {}, vLLM requires a pre-rendered prompt",
        internalId
      );
      return null;
    }

    // The engine's arena outlives every request, so SamplingParams can live in it.
    SamplingParams sp = new SamplingParams(engine.arena());
    if (request.temperature() != null) sp.temperature(request.temperature());
    sp.maxTokens(resolveMaxTokens(request));
    if (request.topP() != null) sp.topP(request.topP());
    if (request.presencePenalty() != null) sp.presencePenalty(request.presencePenalty());
    if (request.frequencyPenalty() != null) sp.frequencyPenalty(request.frequencyPenalty());
    if (request.seed() != null) sp.seed(request.seed().longValue());
    if (request.stop() != null && !request.stop().isEmpty()) sp.stop(request.stop());
    if (request.structuredOutput() != null) {
      sp.guidedDecoding(guidedDecoding(request.structuredOutput()));
    }

    // Keep the markers the tag FSM below is about to look for.
    //
    // Harmony delimits channels with special tokens (<|channel|>analysis<|message|>,
    // <|start|>assistant, <|call|>) and vLLM drops those during detokenization by
    // default (skip_special_tokens=True). The FSM would then search for markers
    // the text no longer contains and the reasoning would leak into the answer.
    //
    // Conditional rather than always-off: for a dialect whose markers are
    // ordinary text (Qwen's <think>), preserving special tokens would instead
    // surface the model's terminal tokens (<|return|>, <|endoftext|>) in the
    // answer.
    if (needsSpecialTokens(request.reasoningTags()) || needsSpecialTokens(request.toolTags())) {
      sp.skipSpecialTokens(false);
    }

    // Always create a ConversationState so token counts (prompt, answer,
    // reasoning, tools) are tracked even without reasoning/tool tags.
    ConversationState conversationState = new ConversationState();
    // Every configured marker, opening and closing, not just the primaries: a
    // dialect may open a channel more than one way (Harmony opens tool calls on
    // both the commentary and analysis channels) and leave it more than one way
    // (after <|end|> when answering directly, after <|call|> when a tool call
    // intervened).
    if (request.reasoningTags() != null && request.reasoningTags().isConfigured()) {
      conversationState.reasoning(
        request.reasoningTags().allOpenTokens(),
        request.reasoningTags().allCloseTokens()
      );
    }
    if (request.toolTags() != null && request.toolTags().isConfigured()) {
      conversationState.toolCall(
        request.toolTags().allOpenTokens(),
        request.toolTags().allCloseTokens()
      );
    }

    String requestId = "seq-" + internalId;

    // Build optional LoRA request
    LoraRequest loraReq = null;
    if (request.hasLora()) {
      loraReq = new LoraRequest(
        request.loraName() != null ? request.loraName() : "lora-" + internalId,
        internalId + 1, // loraIntId must be >= 1
        request.loraPath()
      );
    }

    // Build the vLLM4J request with full constructor (supports multimodal + LoRA)
    var vllmRequest = new io.gravitee.vllm.engine.VllmRequest(
      requestId,
      prompt,
      sp,
      multiModalData,
      0,
      loraReq
    );

    // Submit to iterator with conversation state for token tracking
    iterator.addRequest(vllmRequest, conversationState);

    VllmSequenceState state = new VllmSequenceState(
      requestId,
      sp,
      conversationState,
      System.currentTimeMillis()
    );
    states.put(internalId, state);
    return state;
  }

  /**
   * Resolves the completion budget for a request.
   *
   * <p>Leaving this to vLLM is not an option: its {@code SamplingParams}
   * default is 16 tokens, which silently truncates every request that does not
   * name a limit. On a thinking model the entire budget disappears inside the
   * reasoning block, so the caller gets empty content and
   * {@code finish_reason=length}.
   *
   * <p>llama.cpp treats "unset" as "whatever is left in the context window"
   * ({@code Model.availableForCompletion}); this matches it: unset means the
   * rest of the window, and an explicit value is clamped to it.
   *
   * @return the token budget to hand vLLM
   */
  private int resolveMaxTokens(VllmRequest request) {
    int available = availableForCompletion(request);
    Integer requested = request.maxTokens();
    if (requested == null || requested <= 0) {
      return available;
    }
    return Math.min(requested, available);
  }

  /**
   * Tokens left in the context window once the prompt is accounted for.
   *
   * <p>The prompt length is estimated rather than tokenized: this runs on the
   * request path, and tokenizing twice to save a few tokens of headroom is not
   * worth the GIL round-trip. vLLM enforces the real limit itself.
   */
  private int availableForCompletion(VllmRequest request) {
    int context = contextWindow();
    if (context <= 0) {
      return DEFAULT_MAX_TOKENS;
    }
    int promptEstimate = request.prompt() == null
      ? 0
      : request.prompt().length() / APPROX_CHARS_PER_TOKEN;
    int available = context - promptEstimate;
    return available > 0 ? available : DEFAULT_MAX_TOKENS;
  }

  /** Context window, read once; it cannot change for the life of the engine. */
  private int contextWindow() {
    int cached = contextWindow;
    if (cached < 0) {
      try {
        cached = engine.maxModelLen();
      } catch (RuntimeException e) {
        LOGGER.debug("Could not read max_model_len: {}", e.getMessage());
        cached = 0;
      }
      contextWindow = cached;
    }
    return cached;
  }

  @Override
  public PromptStats validateRequest(VllmRequest request) {
    // vLLM handles prompt validation internally via the Python engine.
    // We provide a permissive estimate here. The engine will reject
    // requests that exceed the model's context length.
    int estimatedPromptTokens = 0;
    if (request.prompt() != null) {
      estimatedPromptTokens = request.prompt().length() / 4; // rough estimate
    }
    int maxTokens = request.maxTokens() != null ? request.maxTokens() : 0;
    // Use a large context window estimate since vLLM manages this internally
    return new PromptStats(estimatedPromptTokens, Integer.MAX_VALUE, maxTokens);
  }

  @Override
  public Optional<EngineOutput<String, VllmSequenceState>> processNextBatch() throws Exception {
    if (!iterator.hasNext()) {
      return Optional.empty();
    }

    VllmOutput output = iterator.next();
    currentOutput.set(output);

    // Find the internal ID for this request
    for (var entry : states.entrySet()) {
      if (entry.getValue().requestId.equals(output.requestId())) {
        VllmSequenceState state = entry.getValue();
        state.lastOutput = output;
        state.totalTokensGenerated++;
        if (output.finished()) {
          state.finishReason = output.finishReason();
          state.finishedTimeMs = System.currentTimeMillis();
        }
        return Optional.of(new EngineOutput<>(entry.getKey(), output.delta()));
      }
    }

    return Optional.empty();
  }

  @Override
  public void removeSequence(int internalId) {
    VllmSequenceState state = states.get(internalId);
    if (state != null) {
      try {
        iterator.abortRequest(state.requestId);
      } catch (Exception e) {
        LOGGER.debug("Error aborting request {}: {}", state.requestId, e.getMessage());
      }
    }
  }

  @Override
  public Optional<String> getFinishReason(VllmSequenceState state) {
    if (state == null) return Optional.empty();
    if (state.finishReason == null) return Optional.empty();

    // Prefer the Java-side ConversationState finish reason when it detected
    // tool calls. Python vLLM has no concept of <tool_call> tags and reports
    // "stop", but the Java FSM correctly identified TOOL_CALL boundaries.
    if (state.conversationState != null && state.conversationState.finishReason() != null) {
      return Optional.of(state.conversationState.finishReason().label());
    }
    return Optional.of(state.finishReason);
  }

  @Override
  public TokenCountInfo getTokenCounts(VllmSequenceState state) {
    if (state == null) {
      return new TokenCountInfo(0, 0, 0, 0);
    }
    if (state.conversationState != null) {
      return new TokenCountInfo(
        state.conversationState.inputTokens(),
        state.conversationState.answerTokens() +
          state.conversationState.reasoningTokens() +
          state.conversationState.toolsTokens(),
        state.conversationState.reasoningTokens(),
        state.conversationState.toolsTokens()
      );
    }
    return new TokenCountInfo(0, state.totalTokensGenerated, 0, 0);
  }

  @Override
  public TokenChannel channelOf(VllmSequenceState state) {
    if (state == null || state.conversationState == null) {
      return null;
    }
    // The FSM classifies every generated token from the reasoning/tool tags on
    // the request, and it suppresses the tags themselves, so, exactly as on
    // llama.cpp, this classification is the only signal downstream has. It is
    // also the only one that survives a <think>-prefilled prompt, where the
    // open tag never appears in the generated text at all.
    var generationState = state.conversationState.currentState();
    if (generationState == null) {
      return null;
    }
    return switch (generationState) {
      case ANSWER -> TokenChannel.ANSWER;
      case REASONING -> TokenChannel.REASONING;
      case TOOLS -> TokenChannel.TOOL;
    };
  }

  @Override
  public InferencePerformance buildPerformance(VllmSequenceState state) {
    if (state == null) {
      return null;
    }
    long startTime = state.startTimeMs;
    long totalTime =
      (state.finishedTimeMs > 0 ? state.finishedTimeMs : System.currentTimeMillis()) - startTime;
    return new InferencePerformance(
      startTime,
      0,
      0,
      totalTime,
      0,
      state.totalTokensGenerated,
      0,
      0,
      state.totalTokensGenerated
    );
  }

  @Override
  public void cleanupSequenceState(VllmSequenceState state) {
    if (state != null && state.samplingParams != null) {
      try {
        state.samplingParams.close();
      } catch (Exception e) {
        LOGGER.debug("Error closing sampling params: {}", e.getMessage());
      }
    }
    // Deliberately no engine.freeCache() here. vLLM manages its own KV cache:
    // torch.cuda.synchronize() + empty_cache() after every request destroys
    // pipeline overlap, forces cudaMalloc round-trips, and can race with vLLM's
    // background engine_core loop that allocates and frees blocks asynchronously.
  }

  @Override
  public void shutdown() {
    try {
      iterator.stop();
    } catch (Exception e) {
      LOGGER.debug("Error stopping iterator: {}", e.getMessage());
    }
    try {
      engine.close();
    } catch (Exception e) {
      LOGGER.debug("Error closing engine: {}", e.getMessage());
    }
  }

  /**
   * Extracts multimodal data (images, audio) from chat messages.
   *
   * <p>Collects every {@link ImageContent} and {@link AudioContent} item, decodes
   * the base64 payload and adds the bytes to a {@link MultiModalData}. The chat
   * template is responsible for the placeholder tokens in the rendered prompt;
   * this method only handles the binary data. Undecodable items are logged and
   * skipped.
   *
   * @return a populated {@link MultiModalData}, or {@code null} if no media found
   */
  private static MultiModalData extractMultiModalData(
    List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages
  ) {
    MultiModalData mmData = null;

    for (var msg : messages) {
      if (!msg.hasMedia()) continue;

      for (Content content : msg.media()) {
        if (content instanceof ImageContent img) {
          try {
            byte[] imageBytes = Base64.getDecoder().decode(img.data());
            if (mmData == null) mmData = new MultiModalData();
            mmData.addImage(imageBytes);
          } catch (IllegalArgumentException e) {
            LOGGER.warn("Failed to decode base64 image data: {}", e.getMessage());
          }
        } else if (content instanceof AudioContent audio) {
          try {
            byte[] audioBytes = Base64.getDecoder().decode(audio.data());
            if (mmData == null) mmData = new MultiModalData();
            mmData.addAudio(audioBytes);
          } catch (IllegalArgumentException e) {
            LOGGER.warn("Failed to decode base64 audio data: {}", e.getMessage());
          }
        }
      }
    }

    return mmData;
  }

  /**
   * Per-sequence state for vLLM: request id, sampling params, conversation state
   * for token classification, and timing for performance metrics.
   *
   * <p>Owned by the adapter; the engine thread is the only writer of the
   * mutable fields.
   */
  public static class VllmSequenceState {

    final String requestId;
    final SamplingParams samplingParams;
    final ConversationState conversationState;
    final long startTimeMs;
    VllmOutput lastOutput;
    String finishReason;
    long finishedTimeMs;
    int totalTokensGenerated;

    VllmSequenceState(
      String requestId,
      SamplingParams samplingParams,
      ConversationState conversationState,
      long startTimeMs
    ) {
      this.requestId = requestId;
      this.samplingParams = samplingParams;
      this.conversationState = conversationState;
      this.startTimeMs = startTimeMs;
    }
  }
}
