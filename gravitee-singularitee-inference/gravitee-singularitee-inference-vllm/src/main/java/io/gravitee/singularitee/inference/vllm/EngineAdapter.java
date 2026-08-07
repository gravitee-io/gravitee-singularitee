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
import io.gravitee.singularitee.inference.api.textgen.TagConfig;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import io.gravitee.vllm.engine.CompletionOutput;
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
 * Engine adapter for vLLM backend.
 * Bridges the Gravitee {@link io.gravitee.singularitee.inference.api.EngineAdapter} abstraction
 * with the vLLM4J {@link VllmEngine} and {@link VllmIterator}.
 *
 * <p>Unlike llama.cpp which uses a native batch iterator, vLLM manages its own
 * continuous batching via the Python engine. This adapter drives the VllmIterator
 * which calls {@code engine.step()} and extracts per-token deltas.
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
   * Fallback budget when the context window is unknown — generous enough that a
   * reasoning model can finish, unlike vLLM's own default of 16.
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

  public EngineAdapter(VllmConfig config) {
    VllmEngineBuilder builder = VllmEngine.builder().dtype(resolveDtype(config.dtype()));

    // The weights are fetched in Java before we get here, so point vLLM at the
    // directory rather than a repo id — that keeps the download on one code path
    // and one cache, and lets the engine load with no network at all.
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
    // Unlike the other booleans this one is three-valued, because a backend can
    // need it OFF: vllm-metal's paged-attention runtime loses the RequestState
    // for cached requests and falls back to emitting token id 0 as a placeholder,
    // so generation silently produces nothing until the window fills.
    //
    // Nobody should have to know that to run a workspace on a Mac, so an
    // unconfigured workspace gets it turned off on Metal rather than inheriting
    // vLLM's on-by-default. An explicit value in the workspace always wins —
    // including an explicit true, which is how you re-test the upstream fix.
    // Metal's paged-attention runtime loses the RequestState for cached requests
    // and answers every decode step with placeholder token id 0 — generation looks
    // alive, produces nothing, and runs until the context window fills. Turn it off
    // there, before the interpreter starts, unless the workspace wants LoRA, which
    // that backend serves only from the paged path:
    //   LLMEngine.from_engine_args(): LoRA on Metal requires paged attention.
    //
    // This lives here rather than in vLLM4j because it is a policy call about which
    // broken-ness to accept, and because the library must stay usable by callers who
    // need LoRA — its own integration suite is one of them.
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
    // Only ever turn it ON, like every other boolean here.
    //
    // The proto field is a plain bool, so "the workspace never mentioned
    // enable_chunked_prefill" arrives as false — indistinguishable from someone
    // asking for it to be off. Forwarding that unconditionally turned a default
    // into an explicit disable on every workspace, and vLLM says what that costs:
    //   WARNING [arg_utils.py] This model does not officially support disabling
    //   chunked prefill. Disabling this manually may cause the engine to crash
    //   or produce incorrect outputs.
    // gpt-oss-20b is such a model — half its layers are sliding-window attention
    // — and the engine segfaulted shortly after startup on an A100.
    //
    // Left unset, vLLM picks its own default (on, for V1), which is what an
    // unconfigured workspace means.
    if (config.enableChunkedPrefill()) builder.enableChunkedPrefill(true);
    if (config.kvCacheDtype() != null) builder.kvCacheDtype(config.kvCacheDtype());
    if (config.enableLora()) {
      builder.enableLora(true);
      if (config.maxLoras() > 0) builder.maxLoras(config.maxLoras());
      if (config.maxLoraRank() > 0) builder.maxLoraRank(config.maxLoraRank());
    }
    if (config.venvPath() != null) builder.venvPath(config.venvPath());
    if (config.enableSleepMode() != null) builder.enableSleepMode(config.enableSleepMode());

    // Distributed inference. These arrive as plain configuration — resolving
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
    // imports the FlashInfer backend unconditionally unless this says otherwise
    // — so on such a card the engine fails to start with either a JIT build
    // error (package present) or ModuleNotFoundError (package removed, which is
    // what scripts/setup-venv.sh does since the attention selector only skips
    // FlashInfer when it is not importable).
    //
    // Set before the vLLM import below: vllm.envs snapshots this at first read.
    if (GpuCapability.isPreAmpere()) {
      PythonRuntime.setEnv("VLLM_USE_FLASHINFER_SAMPLER", "0");
      boolean pinnedAttention = pinTritonAttention();
      LOGGER.info(
        "Compute capability {} is pre-Ampere — disabling the FlashInfer sampler{}",
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
   * said which dialect it speaks, and asking it to repeat that in a second
   * setting is a way to get the two out of step.
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
   * <p>vLLM auto-selects FlashInfer there — FLASH_ATTN requires sm_80+, and
   * FlashInfer's own {@code supports_compute_capability()} optimistically claims
   * Turing support — but its kernels either fail to JIT-build against the CUDA 13
   * toolchain they pull in, or fail at runtime with "BatchPrefillWithPagedKVCache
   * failed with error invalid argument".
   *
   * <p>vLLM 0.23 dropped the attention-backend environment variable, so this goes
   * through vLLM4j, which forwards this system property to the engine arg. That
   * is why the fix belongs here and not in the venv: the alternative — deleting
   * flashinfer so vLLM stops offering it — is a change to a shared environment
   * that also costs performance the day that venv meets an Ampere card.
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
   * {@code bfloat16} — which compute capability &lt; 8.0 does not implement, so
   * vLLM rejects the load outright. Every workspace would otherwise need
   * {@code dtype: float16} spelled out to run on such a card, including the
   * shipped examples.
   *
   * <p>An explicit {@code dtype:} is always honoured: someone who wrote
   * {@code bfloat16} deserves vLLM's error, not a silent substitution.
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

  public String bosToken() {
    return engine.getBosToken();
  }

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
   * Tokenizes with the model's own tokenizer.
   *
   * @return the exact token count, or -1 when it cannot be determined
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
        "Memory pre-flight for model {}: skipped — could not determine the model shape " +
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

    // WARN means "the estimate says it is tight, proceed anyway" — reasonable,
    // since the KV-cache half of the estimate is approximate. The weights are
    // not: their size comes from the checkpoint and vLLM must hold all of them
    // at once, so a budget that cannot even cover the weights is a certain
    // failure and there is nothing for WARN to be optimistic about.
    //
    // Reported here rather than left to the engine because vLLM's own error —
    // "No available memory for the cache blocks" — names neither the budget, nor
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
   * Whether the weights alone overflow the budget — the deterministic part of an
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

  /**
   * Resolves the context length to use for KV-cache sizing in the pre-flight
   * VRAM estimate.
   *
   * <p>Priority:
   * <ol>
   *   <li>User-configured {@code maxModelLen} (explicit override).</li>
   *   <li>{@code max_position_embeddings} from the model's {@code config.json}
   *       — the maximum sequence length the model's positional encoding supports.
   *       This is what vLLM uses as default context length when no override is
   *       provided.</li>
   *   <li>Fallback to {@code 4096} if neither is available.</li>
   * </ol>
   */
  private static int resolveContextLength(VllmConfig config) {
    if (config.maxModelLen() > 0) {
      return config.maxModelLen();
    }
    if (config.maxPositionEmbeddings() > 0) {
      return config.maxPositionEmbeddings();
    }
    return 4096;
  }

  @Override
  public VllmSequenceState createSequenceState(int internalId, VllmRequest request)
    throws Exception {
    // Prompt must be pre-rendered by the caller — this adapter does not template.
    String prompt = request.prompt();
    MultiModalData multiModalData = null;

    if (request.hasMessages() && request.messages() != null) {
      multiModalData = extractMultiModalData(request.messages());
    }

    if (prompt == null || prompt.isBlank()) {
      LOGGER.error(
        "Cannot create sequence state: prompt is empty for internalId {} — vLLM requires a pre-rendered prompt",
        internalId
      );
      return null;
    }

    // Build SamplingParams — the engine's arena outlives every request
    SamplingParams sp = new SamplingParams(engine.arena());
    if (request.temperature() != null) sp.temperature(request.temperature());
    sp.maxTokens(resolveMaxTokens(request));
    if (request.topP() != null) sp.topP(request.topP());
    if (request.presencePenalty() != null) sp.presencePenalty(request.presencePenalty());
    if (request.frequencyPenalty() != null) sp.frequencyPenalty(request.frequencyPenalty());
    if (request.seed() != null) sp.seed(request.seed().longValue());
    if (request.stop() != null && !request.stop().isEmpty()) sp.stop(request.stop());

    // Keep the markers the tag FSM below is about to look for.
    //
    // Harmony-style dialects delimit channels with *special* tokens —
    // <|channel|>analysis<|message|>, <|start|>assistant, <|call|> — and vLLM
    // deletes those during detokenization by default (skip_special_tokens=True).
    // The FSM then searches for markers the text no longer contains, nothing
    // matches, and the model's reasoning leaks into the answer with the markers
    // dissolved into bare words:
    //     analysisWe need to explain project.assistantcommentary to=functions...
    //
    // Conditional rather than always-off: for a dialect whose markers are
    // ordinary text (Qwen's <think>), preserving special tokens would instead
    // surface the model's terminal tokens (<|return|>, <|endoftext|>) in the
    // answer. Each model gets what its own markers require.
    if (needsSpecialTokens(request.reasoningTags()) || needsSpecialTokens(request.toolTags())) {
      sp.skipSpecialTokens(false);
    }

    // Always create a ConversationState so token counts (prompt, answer,
    // reasoning, tools) are tracked even without reasoning/tool tags.
    ConversationState conversationState = new ConversationState();
    // Every configured marker, opening and closing, not just the primaries. A dialect may open
    // a channel more than one way (Harmony opens tool calls on both the commentary and analysis
    // channels) and leave it more than one way (reasoning reaches the final channel after
    // <|end|> when answering directly, after <|call|> when a tool call intervened). The
    // workspace already carries them all — the YAML accepts a list for each — so taking only the
    // primary here silently dropped configuration the file was allowed to express.
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
   * default is <strong>16 tokens</strong>, which silently truncates every
   * request that does not name a limit. On a thinking model the entire budget
   * disappears inside the reasoning block, so the caller gets empty content and
   * {@code finish_reason=length} — which looks like the model failing rather
   * than a default being applied.
   *
   * <p>llama.cpp treats "unset" as "whatever is left in the context window"
   * ({@code Model.availableForCompletion}), so the same workspace behaves
   * completely differently across the two backends. This matches that: unset
   * means the rest of the window, and an explicit value is clamped to it.
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

  /** Context window, read once — it cannot change for the life of the engine. */
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
    // the request, and it suppresses the tags themselves — so, exactly as on
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
    // Note: we do NOT call engine.freeCache() here. vLLM manages its own
    // KV cache internally — calling torch.cuda.synchronize() + empty_cache()
    // after every request destroys pipeline overlap, forces expensive
    // cudaMalloc round-trips, and can race with vLLM's background engine_core
    // loop that manages block allocation/deallocation asynchronously.
  }

  /**
   * Performs aggressive memory maintenance suitable for periodic scheduling.
   *
   * <p>Call this method periodically (e.g., every 60-300 seconds) to trigger
   * heavier-weight cleanup including multiple garbage collection passes.
   * Useful when operating the gateway in low-memory environments or when
   * gradual memory growth is observed despite per-sequence flushing.
   *
   * <p>Does NOT restart the engine or release model weights, only cleans up
   * temporary allocations and breaks circular references in the Python runtime.
   *
   * <p>Best-effort — silently ignores errors.
   */
  public void performMemoryMaintenance() {
    try {
      engine.reset();
      LOGGER.debug("Performed aggressive GPU memory maintenance");
    } catch (Exception e) {
      LOGGER.warn("Error during GPU memory maintenance: {}", e.getMessage());
    }
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
   * <p>Iterates over all messages, collecting any {@link ImageContent} or
   * {@link AudioContent} media items. The base64-encoded data is decoded
   * to raw bytes and added to a {@link MultiModalData} object.
   *
   * <p>For VLMs (e.g. Qwen2.5-VL, LLaVA), the chat template handles
   * inserting the appropriate placeholder tokens ({@code <image>}, etc.)
   * into the rendered prompt. This method only handles the binary data.
   *
   * @param messages the parsed chat messages with optional media
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
   * Per-sequence state for vLLM.
   * Tracks the request ID, sampling params, conversation state for token classification,
   * and timing information for performance metrics.
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
