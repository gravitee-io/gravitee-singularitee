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
package io.gravitee.singularitee.inference.llama.cpp;

import io.gravitee.llama.cpp.*;
import io.gravitee.llama.cpp.nativelib.LlamaLibLoader;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Model implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(Model.class);

  private static volatile boolean backendInitialized;
  private static volatile boolean backendsLoaded;

  /**
   * Initializes the llama.cpp backend (native library load). Deliberately NOT a static
   * initializer: static helpers like {@link #totalContext(int, int)} must stay usable
   * without native libraries installed (e.g. in unit tests and on hosts without them).
   */
  private static void ensureBackendInitialized() {
    if (backendInitialized) {
      return;
    }
    synchronized (Model.class) {
      if (backendInitialized) {
        return;
      }
      LlamaBackend.init();
      backendInitialized = true;
    }
  }

  /**
   * Registers the compute backends (CPU/CUDA/…) from the native library directory. Idempotent.
   *
   * <p>Must run before any model file is opened — including the memory pre-flight estimate
   * ({@code LlamaModelDims.loadFrom}) — because llama.cpp requires at least one backend device to
   * load a model, otherwise it fails with "no backends are loaded".
   */
  static void loadAllBackends() {
    if (backendsLoaded) {
      return;
    }
    synchronized (Model.class) {
      if (backendsLoaded) {
        return;
      }
      ensureBackendInitialized();
      String location = LlamaLibLoader.load();
      try (Arena a = Arena.ofConfined()) {
        LlamaRuntime.ggml_backend_load_all_from_path(a, location);
      }
      backendsLoaded = true;
    }
  }

  private final Arena arena;
  private final LlamaModel model;
  private final LlamaContext context;
  private final LlamaVocab vocab;
  private final LlamaTokenizer tokenizer;
  private final LlamaLogger logger;
  private final MtmdContext mtmdContext;
  private final LlamaContext mtpContext;
  /** Model-drafting sidecar: its own weights and context, sharing the target's vocab. */
  private final LlamaModel draftModel;
  private final LlamaContext draftContext;
  /** EAGLE3 speculator head: a trained sidecar for this specific target. */
  private final LlamaModel eagle3Model;
  private final LlamaContext eagle3Context;
  private SpeculativeConfig speculativeConfig;

  /**
   * The media placeholder this model's mtmd context expects, read from the native context
   * ({@code mtmd_get_marker}) rather than hardcoded. {@code mtmd_tokenize} replaces each occurrence
   * with the media chunk, so the prompt must carry exactly this string once per attached bitmap.
   * {@code null} when the model is not multimodal. Exposed via {@link #mediaMarker()} so the prompt
   * renderer injects the SAME marker the tokenizer looks for.
   */
  private final String mediaMarker;

  /** Whether finished sequences retain their KV for cross-request prefix reuse. */
  private final boolean promptCacheEnabled;

  /** Budget-aware EOG ramp; start < 0 disables it. */
  private final float eogRampStart;
  private final float eogRampMaxBias;

  // Memoized chat template string — read once from GGUF metadata.
  private volatile String chatTemplateString;
  private volatile boolean chatTemplateResolved;

  public Model(ModelConfig config) {
    ensureBackendInitialized();
    this.arena = Arena.ofAuto();
    this.promptCacheEnabled = config.promptCache();
    this.eogRampStart = config.eogRampStart();
    this.eogRampMaxBias = config.eogRampMaxBias() > 0f ? config.eogRampMaxBias() : 100f;

    this.logger = config.logLevel() == null ? null : new LlamaLogger(arena);
    if (logger != null) {
      logger.setLogging(config.logLevel());
      if (config.logLevel() == LlamaLogLevel.NONE) {
        logger.setLogging(config.logLevel(), s -> {});
      } else {
        logger.setLogging(config.logLevel());
      }
    }

    // When using RPC, skip ggml_backend_load_all_from_path — loading all backends
    // (CPU, Metal, RPC plugin) from the library directory can interfere with
    // explicit RPC server registration via ggml_backend_rpc_add_server.
    // This matches the behavior of Main.java in llamaj.cpp.
    if (!config.hasRpcServers()) {
      loadAllBackends();
    }

    var modelParams = new LlamaModelParams(arena)
      .useMlock(config.useMlock())
      .useMmap(config.useMmap())
      .nGpuLayers(config.nGpuLayers());

    if (config.splitMode() != null) {
      modelParams.splitMode(config.splitMode());
    }
    if (config.mainGpu() >= 0) {
      modelParams.mainGpu(config.mainGpu());
    }

    // Register RPC servers for distributed inference offloading
    if (config.hasRpcServers()) {
      LOGGER.info(
        "Registering {} RPC server(s): {}",
        config.rpcServers().size(),
        config.rpcServers()
      );
      modelParams.rpcServers(arena, config.rpcServers().toArray(String[]::new));
      var rpcDevices = BackendRegistry.getRpcDeviceHandles();
      LOGGER.info("RPC device handles obtained: {}", rpcDevices.size());
      if (!rpcDevices.isEmpty()) {
        modelParams.devices(arena, rpcDevices);
      }
    }

    this.model = new LlamaModel(arena, config.modelPath(), modelParams);
    if (config.loraPath() != null) {
      this.model.initLoraAdapter(arena, config.loraPath());
    }

    var contextParams = new LlamaContextParams(arena).nCtx(
      totalContext(config.nCtx(), config.nSeqMax())
    );
    if (config.nThreads() > 0) contextParams.nThreads(config.nThreads());
    if (config.nThreadsBatch() > 0) contextParams.nThreadsBatch(config.nThreadsBatch());

    if (config.nBatch() != 0) contextParams.nBatch(config.nBatch());
    if (config.nUBatch() != 0) contextParams.nUBatch(config.nUBatch());
    if (config.nSeqMax() != 0) contextParams.nSeqMax(config.nSeqMax());

    if (config.poolingType() != null) {
      contextParams.poolingType(config.poolingType());
    }
    if (config.attentionType() != null) {
      contextParams.attentionType(config.attentionType());
    }
    if (config.flashAttnType() != null) {
      contextParams.flashAttnType(config.flashAttnType());
    }
    contextParams.offloadKQV(config.offloadKQV());
    contextParams.noPerf(config.noPerf());
    if (config.cacheTypeK() != null) {
      contextParams.typeK(config.cacheTypeK());
    }
    if (config.cacheTypeV() != null) {
      contextParams.typeV(config.cacheTypeV());
    }

    // MTP: rejecting draft tokens rewinds the target; hybrid attention+SSM models can only
    // rewind recurrent state from rollback snapshots (harmless for pure-attention models).
    if (config.mtp()) {
      contextParams.nRsSeq(config.speculative().nDraft() + 1);
    }

    this.context = new LlamaContext(arena, model, contextParams);

    LOGGER.info(
      "Context: n_ctx_per_seq={} x n_seq_max={} = n_ctx={} (total KV budget)",
      perSequenceContext(),
      context.nSeqMax(),
      context.nCtx()
    );

    if (config.mtp()) {
      if (nLayerNextn() <= 0) {
        throw new LlamaException(
          "mtp requires a model with an MTP head (n_layer_nextn > 0): " + config.modelPath()
        );
      }
      if (!LlamaExt.available()) {
        throw new LlamaException(
          "MTP requires the staging nextn API in the loaded libllama:\n" +
            LlamaExt.resolutionReport()
        );
      }
      int nDraft = config.speculative().nDraft();
      int minBatch = context.nSeqMax() * (nDraft + 1);
      if (context.nBatch() < minBatch) {
        throw new LlamaException(
          "MTP fused verify needs n_batch >= n_seq_max * (n_draft + 1) = " +
            minBatch +
            " but n_batch is " +
            context.nBatch()
        );
      }
      var mtpParams = new LlamaContextParams(arena)
        .nCtx(context.nCtx())
        .nBatch(context.nBatch())
        .poolingType(PoolingType.NONE)
        .nRsSeq(nDraft + 1)
        .nSeqMax(context.nSeqMax())
        .nOutputsMax(Math.max(1, context.nSeqMax()))
        .ctxOther(context)
        .ctxTypeMtp()
        .noPerf(true);
      this.mtpContext = new LlamaContext(arena, model, mtpParams);
      this.speculativeConfig = config.speculative();
      LOGGER.info(
        "MTP self-speculative decoding enabled: n_draft={}, draft_min={}, p_min={}",
        speculativeConfig.nDraft(),
        speculativeConfig.draftMin(),
        speculativeConfig.pMin()
      );
    } else {
      this.mtpContext = null;
      this.speculativeConfig = null;
    }

    // Model drafting and EAGLE3 both need a second set of weights plus a context over them. The
    // context params mirror the target's so draft and target agree on batch geometry.
    SpeculativeConfig sidecarSpec = null;
    if (config.draftPath() != null) {
      this.draftModel = new LlamaModel(arena, config.draftPath(), modelParams);
      this.draftContext = new LlamaContext(arena, draftModel, contextParams);
      sidecarSpec = config.speculative() != null
        ? config.speculative()
        : SpeculativeConfig.greedy(2);
      LOGGER.info(
        "Model-draft speculative decoding enabled: draft={}, n_draft={}",
        config.draftPath().getFileName(),
        sidecarSpec.nDraft()
      );
    } else {
      this.draftModel = null;
      this.draftContext = null;
    }
    if (config.eagle3Path() != null) {
      this.eagle3Model = new LlamaModel(arena, config.eagle3Path(), modelParams);
      this.eagle3Context = new LlamaContext(arena, eagle3Model, contextParams);
      sidecarSpec = config.speculative() != null
        ? config.speculative()
        : SpeculativeConfig.greedy(2);
      LOGGER.info(
        "EAGLE3 speculative decoding enabled: head={}, n_draft={}",
        config.eagle3Path().getFileName(),
        sidecarSpec.nDraft()
      );
    } else {
      this.eagle3Model = null;
      this.eagle3Context = null;
    }
    if (sidecarSpec != null) {
      this.speculativeConfig = sidecarSpec;
    }
    this.vocab = new LlamaVocab(model);
    this.tokenizer = new LlamaTokenizer(vocab, context);

    // Initialize multimodal context if mmproj file is provided
    if (config.mmprojPath() != null) {
      var mtmdParams = new MtmdContextParams(arena)
        .useGpu(config.nGpuLayers() > 0)
        .printTimings(false);
      if (config.nThreads() > 0) {
        mtmdParams.nThreads(config.nThreads());
      }
      if (config.flashAttnType() != null) {
        mtmdParams.flashAttnType(config.flashAttnType());
      }
      // Marker comes from Singularitee configuration when set; otherwise MtmdContextParams keeps
      // the mtmd_context_params_default() library default ("<__media__>").
      if (config.mediaMarker() != null && !config.mediaMarker().isBlank()) {
        mtmdParams.mediaMarker(config.mediaMarker());
      }
      this.mtmdContext = new MtmdContext(arena, this.model, config.mmprojPath(), mtmdParams);
      // Read the effective marker back from the params so the prompt renderer injects exactly the
      // marker mtmd_tokenize looks for (configured value, or the library default).
      this.mediaMarker = mtmdParams.mediaMarker();
    } else {
      this.mtmdContext = null;
      this.mediaMarker = null;
    }
  }

  // n_layer_nextn from GGUF metadata (<arch>.nextn_predict_layers); llamaj.cpp keeps the native
  // accessor package-private, and this hparam is only ever read from that metadata key.
  private int nLayerNextn() {
    try (Arena a = Arena.ofConfined()) {
      String arch = model.metaVal(a, "general.architecture");
      if (arch == null || arch.isBlank()) {
        return 0;
      }
      String nextn = model.metaVal(a, arch + ".nextn_predict_layers");
      return nextn == null || nextn.isBlank() ? 0 : Integer.parseInt(nextn.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public BatchIterator newBatchIterator() {
    return new BatchIterator(arena, context, mtmdContext);
  }

  /**
   * Returns true if this model has a multimodal projection loaded,
   * meaning it can process image and/or audio inputs.
   */
  public boolean isMultimodal() {
    return mtmdContext != null;
  }

  /**
   * The media marker this model's mtmd context expects (from {@code mtmd_get_marker}), or
   * {@code null} if the model is not multimodal. Used by the prompt renderer to inject the exact
   * marker the tokenizer looks for.
   */
  public String mediaMarker() {
    return mediaMarker;
  }

  /**
   * Whether the loaded projector can decode images.
   *
   * <p>Asked of {@code mtmd} itself ({@code mtmd_support_vision}) rather than
   * inferred from the file name or the model id: the projector is the only thing
   * that actually knows, and a VLM and an ALM are both loaded from an
   * {@code mmproj} sidecar that looks identical from the outside.
   */
  public boolean supportsVision() {
    return mtmdContext != null && mtmdContext.supportsVision();
  }

  /** Whether the loaded projector can decode audio ({@code mtmd_support_audio}). */
  public boolean supportsAudio() {
    return mtmdContext != null && mtmdContext.supportsAudio();
  }

  /**
   * Returns the MtmdContext for multimodal operations, or null if not multimodal.
   */
  public MtmdContext getMtmdContext() {
    return mtmdContext;
  }

  /** Returns the chat template from GGUF metadata, or {@code null}. */
  /** Every string this model's vocabulary parses as a special token (cached by LlamaVocab). */
  public java.util.List<String> specialTokenTexts() {
    return vocab.specialTokenTexts();
  }

  public String chatTemplateString() {
    if (!chatTemplateResolved) {
      synchronized (this) {
        if (!chatTemplateResolved) {
          try {
            chatTemplateString = new LlamaTemplate(model).templateString();
          } catch (Exception e) {
            LOGGER.debug("Could not read chat template from model: {}", e.getMessage());
            chatTemplateString = null;
          }
          chatTemplateResolved = true;
        }
      }
    }
    return chatTemplateString;
  }

  /** The context's KV cache, for cross-slot prefix sharing. */
  public LlamaMemory memory() {
    return context.getMemory();
  }

  public String bosToken() {
    return vocab.bosTokenText();
  }

  public String eosToken() {
    return vocab.eosTokenText();
  }

  public ConversationState newConversation(int seqId, Request request) {
    return newConversation(seqId, request, 0, null);
  }

  /**
   * Creates a conversation that may reuse the first {@code reusePrefixTokens} prompt tokens
   * already KV-resident in this sequence slot (cross-request prefix cache).
   *
   * <p>Media requests never reuse a prefix and never retain KV — the media chunks are
   * evaluated through the mtmd path and {@code committedTokens()} only covers the text path.
   *
   * @param seqId the slot / sequence id
   * @param request the generation request
   * @param reusePrefixTokens KV-resident prefix length to skip re-evaluating (0 = none)
   * @param preRenderedPrompt the prompt already rendered by the caller (e.g. the adapter's
   *                          {@code tokenizePrompt}), or {@code null} to render here
   */
  public ConversationState newConversation(
    int seqId,
    Request request,
    int reusePrefixTokens,
    String preRenderedPrompt
  ) {
    Objects.requireNonNull(request, "request is required");
    String prompt = preRenderedPrompt != null ? preRenderedPrompt : promptFor(request);
    boolean mediaRequest = isMediaRequest(request);
    if (mediaRequest) {
      reusePrefixTokens = 0;
    }
    PromptStats stats = promptStats(prompt);
    var sampler = samplerFor(request);
    int promptTokens = stats.promptTokens();
    int contextTokens = stats.contextTokens();
    if (promptTokens >= contextTokens) {
      throw new LlamaException(
        "Prompt tokens (" + promptTokens + ") exceed or match context size (" + contextTokens + ")."
      );
    }
    int availableForCompletion = stats.availableForCompletion();
    int maxTokens = request.maxTokens() != null ? request.maxTokens() : availableForCompletion;
    if (maxTokens > availableForCompletion) {
      maxTokens = availableForCompletion;
    }

    var state = ConversationState.create(arena, context, tokenizer, sampler, seqId);
    state.setMaxTokens(maxTokens);

    if (request.topLogprobs() != null && request.topLogprobs() > 0) {
      state.setTopLogprobs(request.topLogprobs());
    }

    if (mtpContext != null) {
      state.setMtp(mtpContext, speculativeConfigFor(request));
    } else if (draftContext != null) {
      state.setDraft(draftContext, speculativeConfigFor(request));
    } else if (eagle3Context != null) {
      state.setEagle3(eagle3Context, eagle3Model, speculativeConfigFor(request));
    }

    if (request.stop() != null && !request.stop().isEmpty()) {
      state.setStopStrings(request.stop());
    }

    // Every configured marker, not just the primary, on both ends: a dialect may open a channel
    // more than one way (Harmony: commentary and analysis) and leave it more than one way
    // (Harmony reaches the final channel after <|end|> when answering directly and after
    // <|call|> when a tool call intervened). Dropping the alternatives leaves the unmatched
    // variant to leak the whole span into the wrong channel as raw text.
    if (request.reasoningTags() != null && request.reasoningTags().isConfigured()) {
      // Repeatable only when the workspace says so: Harmony re-enters its non-answer
      // channels within one generation (analysis, final, then commentary), while a
      // ChatML <think> block must stay once-only so a literal "<think>" typed into an
      // answer cannot re-open reasoning.
      Boolean repeatable = request.reasoningTags().repeatable();
      if (repeatable != null) {
        state.setReasoning(
          request.reasoningTags().allOpenTokens(),
          request.reasoningTags().allCloseTokens(),
          repeatable
        );
      } else {
        state.setReasoning(
          request.reasoningTags().allOpenTokens(),
          request.reasoningTags().allCloseTokens()
        );
      }
    }
    if (request.toolTags() != null && request.toolTags().isConfigured()) {
      state.setToolCall(request.toolTags().allOpenTokens(), request.toolTags().allCloseTokens());
    }

    // Retain KV after the sequence finishes so the next request in this slot can
    // reuse the shared prompt prefix. Never for media requests: mtmd chunks are
    // not represented in committedTokens() (text path only).
    state.setRetainKv(promptCacheEnabled && !mediaRequest);

    // Budget-aware EOG bias: past a fraction of maxTokens, end-of-generation logits are raised so
    // the model finishes its sentence rather than being severed. Off unless configured.
    if (eogRampStart >= 0f) {
      state.setEogRamp(eogRampStart, eogRampMaxBias);
    }
    state.initialize(prompt, reusePrefixTokens);

    // Set media on the conversation state if the model is multimodal and request has media
    if (mtmdContext != null && request.hasMessages()) {
      MediaInfo mediaInfo = processMediaContent(request.messages());
      if (!mediaInfo.media().isEmpty()) {
        state.setMedia(mediaInfo.media());
      }
    }

    return state;
  }

  /**
   * Speculative config for a request: an explicit workspace draft temperature wins; otherwise the
   * request's sampling is applied with the same defaults as {@link #samplerFor} (temperature 0.7,
   * top_p 0.9) — under speculative decoding the per-request sampler is bypassed, and defaulting to
   * greedy here makes penalty-free models loop. A request must ask for {@code temperature: 0}
   * explicitly to get greedy decoding. Penalties are not representable under speculative decoding
   * (rejection sampling is exact only for memoryless samplers) and are silently ignored.
   */
  private SpeculativeConfig speculativeConfigFor(Request request) {
    var spec = speculativeConfig;
    if (spec.temperature() > 0f) {
      return spec;
    }
    float temperature = request.temperature() != null ? request.temperature() : 0.7f;
    if (temperature <= 0f) {
      return spec;
    }
    return spec
      .withTemperature(temperature)
      .withTopP(request.topP() != null ? request.topP() : 0.9f)
      .withSeed(request.seed() != null ? request.seed() : 42);
  }

  public LlamaSampler samplerFor(Request request) {
    float temperature = request.temperature() != null ? request.temperature() : 0.7f;
    float topP = request.topP() != null ? request.topP() : 0.9f;
    float presencePenalty = request.presencePenalty() != null ? request.presencePenalty() : 0.0f;
    float frequencyPenalty = request.frequencyPenalty() != null ? request.frequencyPenalty() : 0.0f;
    int seed = request.seed() != null ? request.seed() : 42;

    var sampler = new LlamaSampler(arena);
    if (temperature <= 0f) {
      return sampler.greedy().seed(seed);
    }
    return sampler
      .temperature(temperature)
      .topP(topP, 64)
      .penalties(vocab, context.nCtx(), 1.0f, frequencyPenalty, presencePenalty)
      .seed(seed);
  }

  public String promptFor(Request request) {
    // Pre-rendered prompt takes precedence over native template application.
    if (request.prompt() != null && !request.prompt().isBlank()) {
      return request.prompt();
    }
    // Fallback: apply the native chat template (direct model path).
    if (request.hasMessages()) {
      return buildChatPrompt(request.messages());
    }
    return "";
  }

  public PromptStats promptStats(Request request) {
    Objects.requireNonNull(request, "request is required");
    return promptStats(promptFor(request));
  }

  private PromptStats promptStats(String prompt) {
    int promptTokens = countPromptTokens(prompt);
    int contextTokens = perSequenceContext();
    return new PromptStats(promptTokens, contextTokens);
  }

  /**
   * Total KV budget to hand llama.cpp, from a <em>per-sequence</em> configured context.
   *
   * <p>{@code config.nCtx()} is per sequence; llama.cpp's {@code n_ctx} is the total budget shared
   * by every sequence, and {@link #perSequenceContext()} divides it back out. Scaling here is what
   * makes a slot actually receive what was configured.
   *
   * <p>{@link LlamaMemoryEstimator} already assumed these semantics — it budgets VRAM for
   * {@code nCtx * nSeqMax} tokens — so before this the pre-flight estimate and the real allocation
   * disagreed by a factor of {@code nSeqMax} in opposite directions: the default 4096 with 8
   * sequences budgeted for 32768 tokens, allocated 4096, and gave each request 512.
   *
   * @param perSequenceCtx configured per-sequence context, or {@code 0} to defer to the model's
   *                       trained context (passed through unchanged)
   * @param nSeqMax        configured sequence count, or {@code 0} for llama.cpp's default of 1
   */
  static int totalContext(int perSequenceCtx, int nSeqMax) {
    int seqMax = nSeqMax != 0 ? nSeqMax : 1;
    long total = (long) perSequenceCtx * seqMax;
    if (total > Integer.MAX_VALUE) {
      throw new LlamaException(
        "n_ctx " + perSequenceCtx + " x n_seq_max " + seqMax + " overflows the total context size"
      );
    }
    return (int) total;
  }

  // Per-sequence token budget: llama_n_ctx is the total KV capacity, split across n_seq_max slots.
  private int perSequenceContext() {
    int nSeqMax = context.nSeqMax();
    return nSeqMax > 1 ? context.nCtx() / nSeqMax : context.nCtx();
  }

  private String buildChatPrompt(
    List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages
  ) {
    try (Arena promptArena = Arena.ofConfined()) {
      List<LlamaChatMessage> llamaMessages = messages
        .stream()
        .map(message -> {
          String content = message.content() != null ? message.content() : "";
          if (mtmdContext != null && message.hasMedia()) {
            // Use unified media processor to get both markers and validate media
            MediaInfo mediaInfo = processMediaContent(List.of(message));
            if (!mediaInfo.media().isEmpty()) {
              content = mediaInfo.promptSuffix() + content;
            }
          }
          return new LlamaChatMessage(promptArena, toRole(message.role()), content);
        })
        .toList();
      return new LlamaTemplate(model).applyTemplate(
        promptArena,
        new LlamaChatMessages(promptArena, llamaMessages),
        context.nCtx()
      );
    }
  }

  /**
   * Builds a list of MtmdMedia from chat messages containing image/audio content.
   * Media is extracted in message order, matching the media markers inserted in buildChatPrompt.
   *
   * <p>Uses {@link Base64#getMimeDecoder()} instead of {@link Base64#getDecoder()} to tolerate
   * whitespace and line breaks in base64 data, matching the permissive behavior of the reference
   * llama.cpp server's custom base64 decoder.</p>
   *
   * <p><strong>Resource Management:</strong> This method acquires native memory resources via
   * {@link MtmdImage#fromBytesNative} and {@link MtmdAudio#fromBytes}. On exception, all
   * successfully created media objects are automatically freed to prevent memory leaks.</p>
   *
   * @deprecated Use {@link #processMediaContent(List)} instead - provides unified processing
   *             with better performance and consistent error handling.
   */
  @Deprecated(since = "1.0", forRemoval = false)
  private List<MtmdMedia> buildMedia(
    List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages
  ) {
    return processMediaContent(messages).media();
  }

  /**
   * Safely frees all media resources in the list.
   * Continues cleanup even if individual free() calls fail.
   *
   * @param mediaList the list of media resources to free
   */
  private void cleanupMedia(List<MtmdMedia> mediaList) {
    for (MtmdMedia media : mediaList) {
      try {
        if (media != null && !media.isFree()) {
          media.free();
        }
      } catch (Exception cleanupException) {
        // Log but continue cleanup to prevent cascading failures
        // Note: Logger is not available in Model class, so this is silent for now
        // Production code should inject a logger or use System.err as fallback
      }
    }
  }

  /**
   * Processes media content from chat messages in a single pass.
   * This replaces the previous dual-loop pattern where media was checked twice
   * (once for prompt markers, once for building media list).
   *
   * <p>Returns both the prompt suffix with media markers and the processed media objects.
   * On exception, all successfully created media objects are automatically freed.</p>
   *
   * @param messages the chat messages containing media content
   * @return MediaInfo with prompt suffix and media list
   */
  private MediaInfo processMediaContent(
    List<io.gravitee.singularitee.inference.api.textgen.ChatMessage> messages
  ) {
    StringBuilder promptBuilder = new StringBuilder();
    List<MtmdMedia> mediaList = new ArrayList<>();

    try {
      for (var message : messages) {
        if (!message.hasMedia()) continue;

        // Single loop with unified type checking - eliminates redundant instanceof calls
        for (var content : message.media()) {
          try {
            if (
              content instanceof io.gravitee.singularitee.inference.api.textgen.ImageContent img
            ) {
              promptBuilder.append(mediaMarker).append('\n');
              byte[] imageBytes = Base64.getMimeDecoder().decode(img.data());
              mediaList.add(MtmdImage.fromBytesNative(arena, mtmdContext, imageBytes));
            } else if (
              content instanceof io.gravitee.singularitee.inference.api.textgen.AudioContent audio
            ) {
              promptBuilder.append(mediaMarker).append('\n');
              byte[] audioBytes = Base64.getMimeDecoder().decode(audio.data());
              int sampleRate = mtmdContext.getAudioSampleRate();
              mediaList.add(
                MtmdAudio.fromBytes(arena, audioBytes, sampleRate > 0 ? sampleRate : 16000)
              );
            }
          } catch (IOException | UnsupportedAudioFileException e) {
            throw new LlamaException("Failed to load media: " + e.getMessage());
          }
        }
      }
    } catch (Exception e) {
      // Cleanup on failure
      cleanupMedia(mediaList);
      throw e;
    }

    return new MediaInfo(promptBuilder.toString(), mediaList);
  }

  /**
   * Counts the tokens of an arbitrary piece of text with the model's own
   * tokenizer (vocab-only — safe on any thread). Used for context-window
   * budgeting (chat history trimming) where an exact count beats estimation.
   *
   * @param text the text to tokenize
   * @return the exact token count
   */
  public int countTokens(String text) {
    return countPromptTokens(text);
  }

  private int countPromptTokens(String prompt) {
    try (Arena promptArena = Arena.ofConfined()) {
      return tokenizer.tokenize(promptArena, prompt).size();
    }
  }

  /**
   * Returns {@code true} when the request carries media attachments this model will
   * evaluate through the mtmd path (prefix cache is bypassed for those).
   */
  public boolean isMediaRequest(Request request) {
    return (
      mtmdContext != null &&
      request.hasMessages() &&
      request
        .messages()
        .stream()
        .anyMatch(m -> m.hasMedia())
    );
  }

  /** Tokenizes a rendered prompt to its token ids (vocab-only — safe on any thread). */
  public int[] tokenizeToIds(String prompt) {
    try (Arena promptArena = Arena.ofConfined()) {
      var response = tokenizer.tokenize(promptArena, prompt);
      int size = response.size();
      int[] ids = new int[size];
      for (int i = 0; i < size; i++) {
        ids[i] = response.data().getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i);
      }
      return ids;
    }
  }

  public record PromptStats(int promptTokens, int contextTokens) {
    public int availableForCompletion() {
      return Math.max(0, contextTokens - promptTokens);
    }
  }

  /**
   * Encapsulates the result of media content processing.
   * Contains both the prompt suffix with media markers and the processed media list.
   */
  private record MediaInfo(String promptSuffix, List<MtmdMedia> media) {}

  private Role toRole(io.gravitee.singularitee.inference.api.textgen.Role role) {
    return switch (role) {
      case ASSISTANT -> Role.ASSISTANT;
      case SYSTEM -> Role.SYSTEM;
      case USER -> Role.USER;
    };
  }

  @Override
  public void close() {
    if (mtmdContext != null) {
      mtmdContext.free();
    }
    if (eagle3Context != null) {
      eagle3Context.free();
    }
    if (eagle3Model != null) {
      eagle3Model.free();
    }
    if (draftContext != null) {
      draftContext.free();
    }
    if (draftModel != null) {
      draftModel.free();
    }
    if (mtpContext != null) {
      mtpContext.free();
    }
    context.free();
    model.free();
    if (arena.scope().isAlive()) {
      arena.close();
    }
  }
}
