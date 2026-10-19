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
package io.gravitee.singularitee.inference.llama.cpp.decision;

import io.gravitee.llama.cpp.ClefPrompt;
import io.gravitee.llama.cpp.DecisionOrder;
import io.gravitee.llama.cpp.DecisionPrompt;
import io.gravitee.llama.cpp.LabelDecider;
import io.gravitee.llama.cpp.LayaDecider;
import io.gravitee.llama.cpp.LayaPrompt;
import io.gravitee.llama.cpp.LlamaDecider;
import io.gravitee.llama.cpp.LlamaExt;
import io.gravitee.llama.cpp.LlamaModel;
import io.gravitee.llama.cpp.LlamaModelParams;
import io.gravitee.llama.cpp.LlamaRuntime;
import io.gravitee.llama.cpp.LlamaTemplate;
import io.gravitee.llama.cpp.LlamaVocab;
import io.gravitee.llama.cpp.QuestionType;
import io.gravitee.llama.cpp.TextPrompt;
import io.gravitee.llama.cpp.nativelib.LlamaLibLoader;
import io.gravitee.singularitee.inference.api.decision.DecisionFamily;
import io.gravitee.singularitee.inference.api.memory.InsufficientVramException;
import io.gravitee.singularitee.inference.api.memory.MemoryCheckPolicy;
import io.gravitee.singularitee.inference.api.memory.MemoryEstimate;
import io.gravitee.singularitee.inference.decision.BackendPrompt;
import io.gravitee.singularitee.inference.decision.DecisionBackend;
import io.gravitee.singularitee.inference.llama.cpp.LlamaBackend;
import io.gravitee.singularitee.inference.llama.cpp.LlamaMemoryEstimator;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A llama.cpp decision model: one {@link LlamaModel} shared by a pool of deciders, each with its
 * own context. A call borrows one decider for the whole request, so the prompts of a request
 * share their common prefix inside it.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LlamaCppDecisionBackend implements DecisionBackend {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlamaCppDecisionBackend.class);

  /** Most options of the families that are not limited by their label codes. */
  private static final int MAX_MARKER_OPTIONS = 255;

  /** How long {@link #close()} waits for running calls. */
  private static final long CLOSE_WAIT_SECONDS = 10;

  static {
    LlamaBackend.init();
  }

  private final Arena arena;
  private final LlamaModel model;
  private final List<LlamaDecider> deciders;
  private final DeciderPool<LlamaDecider> pool;
  private final DecisionFamily family;
  private final String decisionTemplate;
  private final String chatTemplate;
  private final List<String> labelTexts;
  private final int maxOptions;
  private final LayaVocab layaVocab;
  private volatile boolean closed;

  /** Loads the model and its deciders. */
  public LlamaCppDecisionBackend(DecisionModelConfig config) {
    runMemoryCheck(config);
    this.arena = Arena.ofShared();
    try (Arena local = Arena.ofConfined()) {
      LlamaRuntime.ggml_backend_load_all_from_path(local, LlamaLibLoader.load());
    }
    this.model = new LlamaModel(
      arena,
      config.modelPath(),
      new LlamaModelParams(arena).nGpuLayers(config.nGpuLayers())
    );
    if (config.loraPath() != null) model.initLoraAdapter(arena, config.loraPath());

    var options = new LlamaDecider.Options(config.nCtx(), config.nBatch(), config.nSeqMax());
    var created = new ArrayList<LlamaDecider>(config.nParallel());
    try {
      for (int i = 0; i < config.nParallel(); i++) {
        LlamaDecider decider = config.custom()
          ? LabelDecider.custom(arena, model, options, config.codes())
          : LlamaDecider.create(arena, model, options);
        if (model.loraAdapter() != null) {
          decider.context().setLoraAdapter(model.loraAdapter(), 1.0f);
        }
        created.add(decider);
      }
    } catch (RuntimeException e) {
      created.forEach(LlamaDecider::close);
      model.free();
      arena.close();
      throw e;
    }
    this.deciders = List.copyOf(created);
    this.pool = new DeciderPool<>(deciders);

    LlamaDecider first = deciders.getFirst();
    this.family = DecisionFamily.valueOf(first.type().name());
    if (family == DecisionFamily.CLEF && !LlamaExt.decisionAvailable()) {
      close();
      throw new IllegalStateException(
        "clef needs the native decision order symbol: " + LlamaExt.decisionResolutionReport()
      );
    }
    this.decisionTemplate = first.template();
    this.chatTemplate = readChatTemplate();
    this.labelTexts = switch (first) {
      case LabelDecider label when family != DecisionFamily.OPENJEV -> label
        .labels()
        .stream()
        .map(LabelDecider.Label::code)
        .toList();
      default -> List.of();
    };
    this.maxOptions = switch (first) {
      case LabelDecider label -> label.labels().size();
      default -> MAX_MARKER_OPTIONS;
    };
    this.layaVocab = first instanceof LayaDecider laya
      ? new LayaVocab(
        laya.markerToken(),
        laya.sepToken(),
        new String(new LlamaVocab(model).tokenToPiece(laya.markerToken()), StandardCharsets.UTF_8),
        laya.maxHeadTokens()
      )
      : null;
    LOGGER.info(
      "Decision model loaded: family={}, deciders={}, maxOptions={}, decisionTemplate={}",
      family.wireName(),
      deciders.size(),
      maxOptions,
      decisionTemplate != null
    );
  }

  private String readChatTemplate() {
    try {
      return new LlamaTemplate(model).templateString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void runMemoryCheck(DecisionModelConfig config) {
    MemoryCheckPolicy policy = config.memoryCheckPolicy();
    if (policy == null || policy == MemoryCheckPolicy.DISABLED) return;
    int nCtx = config.nCtx() != null ? config.nCtx() : 8192;
    MemoryEstimate estimate = LlamaMemoryEstimator.estimate(
      config.modelPath(),
      null,
      config.loraPath(),
      config.nGpuLayers(),
      nCtx,
      config.nParallel()
    );
    if (estimate.isUnknown() || estimate.willFit()) return;
    String name = config.modelPath().getFileName().toString();
    if (policy == MemoryCheckPolicy.FAIL) throw new InsufficientVramException(name, estimate);
    LOGGER.warn("Memory pre-flight: decision model may not fit: {}", estimate.suggestion());
  }

  @Override
  public DecisionFamily family() {
    return family;
  }

  @Override
  public String decisionTemplate() {
    return decisionTemplate;
  }

  @Override
  public String chatTemplate() {
    return chatTemplate;
  }

  @Override
  public Map<String, Float> temperatures() {
    return deciders.getFirst().temperatures();
  }

  @Override
  public List<String> labelTexts() {
    return labelTexts;
  }

  @Override
  public int maxOptions() {
    return maxOptions;
  }

  @Override
  public int[] tokenize(String text) {
    // Tokenizing reads the vocabulary only; any decider will do, idle or not.
    return deciders.getFirst().tokenize(text, true);
  }

  @Override
  public LayaVocab layaVocab() {
    return layaVocab;
  }

  @Override
  public List<float[]> score(List<BackendPrompt> prompts) {
    List<DecisionPrompt> nativePrompts = prompts
      .stream()
      .map(LlamaCppDecisionBackend::toNative)
      .toList();
    LlamaDecider decider = pool.acquire();
    try {
      return decider.score(nativePrompts);
    } finally {
      pool.release(decider);
    }
  }

  private static DecisionPrompt toNative(BackendPrompt prompt) {
    return switch (prompt) {
      case BackendPrompt.Text text -> TextPrompt.of(text.tokens(), text.nOutputs());
      case BackendPrompt.Laya laya -> new LayaPrompt(
        laya.tokens(),
        QuestionType.valueOf(laya.kind().name()),
        laya.nOutputs()
      );
      case BackendPrompt.Clef clef -> new ClefPrompt(
        clef.tokens(),
        Arrays.stream(clef.order())
          .map(o -> DecisionOrder.valueOf(o.name()))
          .toArray(DecisionOrder[]::new)
      );
    };
  }

  /**
   * Frees the model once every decider is back, so no call scores on freed memory. Calls waiting
   * for a decider, and calls that start afterwards, fail. When a call is still running after
   * {@value #CLOSE_WAIT_SECONDS} s, the native memory is left to the process exit rather than
   * freed under it.
   */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (!pool.close(Duration.ofSeconds(CLOSE_WAIT_SECONDS))) {
      LOGGER.warn(
        "Decision model still scoring after {} s: its native memory is not freed",
        CLOSE_WAIT_SECONDS
      );
      return;
    }
    deciders.forEach(LlamaDecider::close);
    model.free();
    arena.close();
  }
}
