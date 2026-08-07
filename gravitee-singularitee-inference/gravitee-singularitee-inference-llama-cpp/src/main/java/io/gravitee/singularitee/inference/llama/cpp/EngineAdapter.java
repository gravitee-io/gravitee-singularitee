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

import io.gravitee.llama.cpp.BatchIterator;
import io.gravitee.llama.cpp.ConversationState;
import io.gravitee.llama.cpp.FinishReason;
import io.gravitee.llama.cpp.LlamaOutput;
import io.gravitee.llama.cpp.MtmdMedia;
import io.gravitee.singularitee.inference.api.memory.InsufficientVramException;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import io.gravitee.singularitee.inference.api.textgen.InferencePerformance;
import io.gravitee.singularitee.inference.api.textgen.PromptStats;
import io.gravitee.singularitee.inference.api.textgen.TokenChannel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine adapter for llama.cpp backend.
 * Handles all llama.cpp-specific operations while the AbstractBatchEngine
 * manages sequence lifecycle, queuing, and token emission.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EngineAdapter
  implements
    io.gravitee.singularitee.inference.api.EngineAdapter<
      ModelConfig,
      Request,
      String,
      ConversationState
    > {

  private static final Logger LOGGER = LoggerFactory.getLogger(EngineAdapter.class);

  private final Model model;
  private final BatchIterator iterator;

  public EngineAdapter(ModelConfig config) {
    // Compute backends must be registered BEFORE the memory pre-flight opens the GGUF:
    // LlamaModelDims.loadFrom() calls llama_model_load_from_file, which needs ≥1 backend device
    // (else "no backends are loaded"). RPC models register backends explicitly, so skip the bulk
    // load for them (Model applies the same condition; loadAllBackends() is idempotent).
    if (!config.hasRpcServers()) {
      Model.loadAllBackends();
    }
    runMemoryCheck(config);
    this.model = new Model(config);
    this.iterator = model.newBatchIterator();
  }

  /** Returns the underlying model instance. */
  public Model model() {
    return model;
  }

  private static void runMemoryCheck(ModelConfig config) {
    MemoryCheckPolicy policy = config.memoryCheckPolicy();
    if (policy == null || policy == MemoryCheckPolicy.DISABLED) {
      LOGGER.info("Memory pre-flight check is disabled");
      return;
    }
    String modelName = config.modelPath().getFileName().toString();
    if (config.nCtx() == 0) {
      LOGGER.info(
        "Memory pre-flight: nCtx deferred to model — skipping estimate for {}",
        modelName
      );
      return;
    }
    LOGGER.info("Running memory pre-flight check for {} (policy={})", modelName, policy);
    MemoryEstimate estimate = LlamaMemoryEstimator.estimate(
      config.modelPath(),
      config.mmprojPath(),
      config.loraPath(),
      config.nGpuLayers(),
      config.nCtx(),
      config.nSeqMax(),
      config.rpcServers(),
      config.logLevel()
    );
    if (estimate.isUnknown()) {
      LOGGER.info("Memory pre-flight: estimate unavailable — skipping check");
      return;
    }
    LOGGER.info("Memory pre-flight: {}", estimate.toHumanReadable());
    if (estimate.willFit()) {
      return;
    }
    if (policy == MemoryCheckPolicy.FAIL) {
      throw new InsufficientVramException(modelName, estimate);
    }
    LOGGER.warn("Memory pre-flight: model may not fit — {}", estimate.suggestion());
  }

  /**
   * Prompts rendered by {@link #tokenizePrompt} and consumed by
   * {@link #createSequenceState(int, Request, int)} — avoids rendering the chat
   * template twice per request. Keyed by Request identity; entries are removed
   * on consumption, so the map stays bounded by in-flight requests.
   */
  private final Map<Request, String> renderedPrompts = Collections.synchronizedMap(
    new IdentityHashMap<>()
  );

  @Override
  public ConversationState createSequenceState(int internalId, Request request) {
    return createSequenceState(internalId, request, 0);
  }

  @Override
  public ConversationState createSequenceState(
    int internalId,
    Request request,
    int reusePrefixTokens
  ) {
    String preRendered = renderedPrompts.remove(request);
    ConversationState state = model.newConversation(
      internalId,
      request,
      reusePrefixTokens,
      preRendered
    );
    // Add the state to the iterator so it can be processed
    iterator.addState(state);
    return state;
  }

  @Override
  public int[] tokenizePrompt(Request request) {
    // Media requests bypass the prefix cache: mtmd chunks are not represented
    // in committedTokens() and reuse offsets would not line up.
    if (model.isMediaRequest(request)) {
      return null;
    }
    String prompt = model.promptFor(request);
    renderedPrompts.put(request, prompt);
    return model.tokenizeToIds(prompt);
  }

  @Override
  public int[] committedTokens(ConversationState state) {
    return state == null ? null : state.committedTokens();
  }

  /**
   * Shares the donor sequence's leading KV rows with the destination sequence.
   *
   * <p>A llama.cpp KV cell carries a <em>set</em> of sequence ids, so
   * {@code seq_cp} adds the destination to the cells at {@code [0, prefixTokens)}
   * and moves no tensor data — a 2000-token system prompt is published in
   * microseconds, with no extra VRAM and without disturbing a donor that is still
   * generating.
   *
   * <p>{@code LlamaMemory.copyPrefix} carries the contract: it wipes the destination first
   * ({@code seq_cp} adds rows rather than replacing them, so copying onto a slot that still
   * held cells would stack one prefix on another) and clamps the count below the prompt
   * length, since the last prompt token must be re-decoded for the logits its first output
   * token is sampled from.
   */
  @Override
  public int copyKvPrefix(int donorSlot, int destSlot, int prefixTokens, int promptTokens) {
    if (donorSlot == destSlot || prefixTokens <= 0) {
      return 0;
    }
    return model.memory().copyPrefix(donorSlot, destSlot, prefixTokens, promptTokens);
  }

  @Override
  public void removeSequence(int internalId, boolean keepKv) {
    iterator.removeState(internalId, keepKv);
  }

  @Override
  public PromptStats validateRequest(Request request) {
    var stats = model.promptStats(request);
    return new PromptStats(
      stats.promptTokens(),
      stats.contextTokens(),
      request.maxTokens() != null ? request.maxTokens() : 0
    );
  }

  @Override
  public Optional<EngineOutput<String, ConversationState>> processNextBatch() {
    if (!iterator.hasNext()) {
      return Optional.empty();
    }

    LlamaOutput output = iterator.next();
    return Optional.of(new EngineOutput<>(output.sequenceId(), output.text()));
  }

  /**
   * A failed {@code llama_decode} marks every conversation finished and clears the
   * iterator's state map, so "nothing unfinished left" while the engine still tracks
   * sequences means the two sides have desynchronised. Prefilling sequences carry no
   * finish reason and still count as active, so a slow first token is not a stall.
   */
  @Override
  public boolean hasStalled() {
    return !iterator.hasActiveConversations();
  }

  @Override
  public void removeSequence(int internalId) {
    iterator.removeState(internalId);
  }

  @Override
  public Optional<String> getFinishReason(ConversationState state) {
    if (state == null) return Optional.empty();

    // Only report the finish reason when the model has truly stopped generating.
    // finishReason may be set as a marker (e.g. TOOL_CALL after the first
    // </tool_call>) while the model is still producing tokens for additional
    // tool calls. The `finished` flag is set by shouldContinue() when EOG
    // or LENGTH is hit.
    if (!state.isFinished()) return Optional.empty();

    FinishReason finishReason = state.getFinishReason();
    return finishReason == null ? Optional.empty() : Optional.of(mapFinishReason(finishReason));
  }

  @Override
  public TokenCountInfo getTokenCounts(ConversationState state) {
    if (state == null) {
      return new TokenCountInfo(0, 0, 0, 0);
    }
    // OpenAI semantics: completion_tokens is EVERYTHING generated; reasoning/tool counts are
    // breakdowns of that total, not separate buckets.
    return new TokenCountInfo(
      state.getInputTokens(),
      state.getAnswerTokens() + state.getReasoningTokens() + state.getToolsTokens(),
      state.getReasoningTokens(),
      state.getToolsTokens()
    );
  }

  @Override
  public TokenChannel channelOf(ConversationState state) {
    if (state == null || state.getGenerationState() == null) {
      return null;
    }
    // llama.cpp classifies every generated token via the reasoning/tool tags
    // configured on the request. With a <think>-prefilled prompt the state
    // STARTS in REASONING, so no literal open tag ever appears in the emitted
    // text — this classification is the only reliable signal downstream.
    return switch (state.getGenerationState()) {
      case ANSWER -> TokenChannel.ANSWER;
      case REASONING -> TokenChannel.REASONING;
      case TOOLS -> TokenChannel.TOOL;
    };
  }

  @Override
  public io.gravitee.singularitee.inference.api.textgen.PositionLogprobs logprobsOf(
    ConversationState state
  ) {
    if (state == null || state.getLogprobs() == null) {
      return null;
    }
    var lp = state.getLogprobs();
    return new io.gravitee.singularitee.inference.api.textgen.PositionLogprobs(
      toLogprobEntry(lp.chosenToken()),
      lp.topLogprobs().stream().map(EngineAdapter::toLogprobEntry).toList()
    );
  }

  private static io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry toLogprobEntry(
    io.gravitee.llama.cpp.TokenLogprob t
  ) {
    return new io.gravitee.singularitee.inference.api.textgen.TokenLogprobEntry(
      t.token(),
      t.tokenId(),
      t.logprob(),
      t.bytes()
    );
  }

  @Override
  public InferencePerformance buildPerformance(ConversationState state) {
    if (state == null) {
      return null;
    }
    var arena = state.getArena();
    var contextPerf = state.getContext().getPerformance(arena);
    var samplerPerf = state.getSampler().getPerformance(arena);

    // Direct primitive conversions - eliminates temporary Double object allocation
    // and reduces bytecode overhead vs Double.valueOf(x).longValue()
    return new InferencePerformance(
      (long) contextPerf.startTimeMs(),
      (long) contextPerf.loadTimeMs(),
      (long) contextPerf.promptEvalTimeMs(),
      (long) contextPerf.evalTimeMs(),
      contextPerf.promptTokensEvaluated(),
      contextPerf.tokensGenerated(),
      // Cross-request prefix reuse (getReusePrefixTokens) supersedes the native
      // in-context reuse counter when present — both mean "prompt tokens not
      // re-evaluated because their KV was already resident".
      Math.max(contextPerf.tokensReused(), state.getReusePrefixTokens()),
      (long) samplerPerf.samplingTimeMs(),
      samplerPerf.sampleCount()
    );
  }

  @Override
  public void cleanupSequenceState(ConversationState state) {
    if (state != null) {
      // Free native media bitmaps to prevent memory leaks and stale encoder state
      for (MtmdMedia media : state.getMedia()) {
        if (!media.isFree()) {
          media.free();
        }
      }
      state.getMedia().clear();

      // Free the sampler
      if (state.getSampler() != null) {
        state.getSampler().free();
      }
    }
  }

  @Override
  public void shutdown() {
    iterator.stop();
    iterator.free();
    model.close();
  }

  private String mapFinishReason(FinishReason finishReason) {
    if (finishReason == null) {
      return null;
    }
    return switch (finishReason) {
      case LENGTH -> "length";
      case TOOL_CALL -> "tool_calls";
      case STOP, EOS -> "stop";
    };
  }
}
