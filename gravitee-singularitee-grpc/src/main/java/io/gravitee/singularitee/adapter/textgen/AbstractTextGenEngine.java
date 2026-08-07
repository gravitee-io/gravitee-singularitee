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
package io.gravitee.singularitee.adapter.textgen;

import static java.util.stream.Collectors.toUnmodifiableList;

import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.engine.template.Jinja4jChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.template.ChatTemplateRenderer;
import io.gravitee.singularitee.inference.api.textgen.*;
import io.gravitee.singularitee.pipeline.executor.ChatWindowTrimmer;
import io.gravitee.singularitee.pipeline.executor.TokenCounter;
import io.reactivex.rxjava3.core.BackpressureOverflowStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base adapter that wraps an {@link AbstractBatchEngine} from {@code gravitee-inference-api}
 * and exposes it as a {@link TextGenEngine}.
 *
 * <p>This is the <strong>only</strong> class in the entire project that is permitted
 * to import {@code gravitee-inference-api} text-gen types. All conversion logic
 * between local engine types and library types lives here.
 *
 * <p>{@link #rxAddSequence} returns a {@link Completable} that completes when the
 * final token for the given sequence has been delivered to the consumer, eliminating
 * the need for a {@code CountDownLatch} in callers.
 *
 * @param <CFG>     engine-specific config type
 * @param <REQ>     engine-specific request type
 * @param <STATE>   engine-specific state type
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
abstract sealed class AbstractTextGenEngine<
  CFG,
  REQ extends io.gravitee.singularitee.inference.api.textgen.GenerationRequest,
  STATE
>
  implements TextGenEngine
  permits LlamaCppTextGenEngine, VllmTextGenEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTextGenEngine.class);

  /**
   * Fraction of the model context window ({@link #contextSize()}) at which a
   * running sequence is force-stopped to avoid overrunning the KV cache.
   * The stop fires when {@code promptTokens + completionTokens} reaches this
   * fraction of the context size; the sequence then finishes with reason
   * {@code "length"}.
   */
  private static final double CONTEXT_STOP_RATIO = 0.95;

  /**
   * Single renderer for the direct-model path (no pipeline). The pipeline path
   * pre-renders prompts in the executor; this renderer is only used when a
   * caller hands us messages without a pre-rendered prompt (e.g. the CLI
   * invoked with {@code --model-id}).
   */
  private static final ChatTemplateRenderer RENDERER = new Jinja4jChatTemplateRenderer();

  protected final AbstractBatchEngine<CFG, REQ, String, STATE> delegate;

  /**
   * Per-sequence subjects that complete when the final token arrives.
   * Keyed by seqId. Entries are removed by the token consumer on completion/error.
   */
  private final ConcurrentHashMap<Integer, CompletableSubject> pendingSequences =
    new ConcurrentHashMap<>();

  /**
   * Per-sequence reactive token streams, keyed by seqId. Present only while a caller
   * has an active {@link #rxStream} subscription for that sequence; when present, tokens
   * are routed to the processor instead of the legacy {@code start(...)} consumer.
   */
  private final ConcurrentHashMap<Integer, FlowableProcessor<ModelEngineToken>> streams =
    new ConcurrentHashMap<>();

  /**
   * Sequences already stopped by the context-window guard. Guards against
   * issuing the cancel/synthetic-final more than once when several tokens for
   * the same sequence cross the threshold before the cancel takes effect.
   */
  private final java.util.Set<Integer> contextStoppedSequences = ConcurrentHashMap.newKeySet();

  protected AbstractTextGenEngine(AbstractBatchEngine<CFG, REQ, String, STATE> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void start(Consumer<ModelEngineToken> tokenConsumer) {
    delegate.start(token -> {
      ModelEngineToken localToken = toLocalToken(token);
      int seqId = localToken.seqId();

      // Route to the per-sequence reactive stream when one is subscribed (the direct
      // gRPC path), otherwise to the legacy callback (the pipeline path, until migrated).
      FlowableProcessor<ModelEngineToken> processor = streams.get(seqId);
      Consumer<ModelEngineToken> sink = processor != null ? processor::onNext : tokenConsumer;

      // Context-window guard: stop a runaway sequence before it overruns the
      // model's context. We still forward the triggering token, then halt the
      // engine and synthesise a final "length" token so downstream completes.
      if (!localToken.isFinal() && reachedContextLimit(localToken)) {
        sink.accept(localToken);
        stopForContextLimit(localToken, sink);
        completeStream(seqId, processor);
        return;
      }

      sink.accept(localToken);
      if (localToken.isFinal()) {
        contextStoppedSequences.remove(seqId);
        completeStream(seqId, processor);
        CompletableSubject subject = pendingSequences.remove(seqId);
        if (subject != null) {
          subject.onComplete();
        }
      }
    });
  }

  /** Detaches and completes the per-sequence reactive stream, if one is subscribed. */
  private void completeStream(int seqId, FlowableProcessor<ModelEngineToken> processor) {
    if (processor != null) {
      streams.remove(seqId);
      processor.onComplete();
    }
  }

  @Override
  public Flowable<ModelEngineToken> rxStream(int seqId) {
    int capacity = StreamingConfig.streamBufferCapacity();
    // Serialized so the worker thread (onNext/onComplete) and close() (onError) can
    // touch the processor without violating the single-producer contract.
    FlowableProcessor<ModelEngineToken> processor = UnicastProcessor.<
        ModelEngineToken
      >create().toSerialized();
    streams.put(seqId, processor);
    return processor
      .onBackpressureBuffer(
        capacity,
        () ->
          LOGGER.warn(
            "Sequence {} token-stream buffer overflowed ({} tokens) — consumer too slow; cancelling",
            seqId,
            capacity
          ),
        BackpressureOverflowStrategy.ERROR
      )
      // Disposal (client disconnect) cancels native generation, mirroring rxAddSequence.
      .doOnCancel(() -> cancelSequence(seqId))
      .doFinally(() -> streams.remove(seqId));
  }

  /**
   * Returns {@code true} when this token pushes the sequence's total token count
   * ({@code promptTokens + completionTokens}) to {@link #CONTEXT_STOP_RATIO} of
   * the engine's {@link #contextSize()}. Always {@code false} when the context
   * size is unknown ({@code 0}) or the sequence is already being stopped.
   */
  private boolean reachedContextLimit(ModelEngineToken token) {
    int ctx = contextSize();
    if (ctx <= 0 || contextStoppedSequences.contains(token.seqId())) {
      return false;
    }
    long total = (long) token.promptTokens() + token.completionTokens();
    return total >= (long) Math.floor(ctx * CONTEXT_STOP_RATIO);
  }

  /**
   * Cancels the sequence on the underlying engine and emits a synthetic final
   * token with {@code finishReason = "length"} so the registered consumer (and
   * the reactive chain awaiting it) completes cleanly.
   */
  private void stopForContextLimit(ModelEngineToken trigger, Consumer<ModelEngineToken> consumer) {
    if (!contextStoppedSequences.add(trigger.seqId())) {
      return; // another token already triggered the stop for this sequence
    }
    LOGGER.warn(
      "Sequence {} reached {}% of the {}-token context window ({} prompt + {} completion) — stopping generation",
      trigger.seqId(),
      (int) (CONTEXT_STOP_RATIO * 100),
      contextSize(),
      trigger.promptTokens(),
      trigger.completionTokens()
    );
    try {
      delegate.cancelSequence(trigger.seqId());
    } catch (RuntimeException e) {
      LOGGER.warn("cancelSequence({}) failed: {}", trigger.seqId(), e.getMessage());
    }

    ModelEngineToken finalToken = new ModelEngineToken(
      trigger.seqId(),
      null,
      trigger.index() + 1,
      true,
      "length",
      trigger.promptTokens(),
      trigger.completionTokens(),
      trigger.reasoningTokens(),
      trigger.toolTokens(),
      null
    );
    consumer.accept(finalToken);

    CompletableSubject subject = pendingSequences.remove(trigger.seqId());
    if (subject != null) {
      subject.onComplete();
    }
  }

  @Override
  public Completable rxAddSequence(int seqId, TextGenRequest request) {
    CompletableSubject subject = CompletableSubject.create();
    pendingSequences.put(seqId, subject);
    try {
      delegate.addSequence(seqId, toEngineRequest(maybePreRender(request)), request.cacheKey());
    } catch (Exception e) {
      pendingSequences.remove(seqId);
      return Completable.error(e);
    }
    // Disposing the subscription (pipeline cancelled, client disconnected)
    // must stop native generation too — otherwise an orphaned sequence keeps
    // burning compute until max_tokens. cancelSequence() is guarded by the
    // pendingSequences map, so a dispose that races (or follows) normal
    // completion is a no-op.
    return subject.doOnDispose(() -> cancelSequence(seqId));
  }

  /**
   * Cancels a running sequence: stops native generation, releases the
   * pending-completion subject (so any reactive chain awaiting the sequence
   * finishes), and clears guard state. Safe to call for already-finished or
   * unknown sequence ids — the {@link #pendingSequences} guard makes it a
   * no-op then.
   */
  @Override
  public void cancelSequence(int seqId) {
    CompletableSubject subject = pendingSequences.remove(seqId);
    if (subject == null) {
      return; // already finished or never started — nothing to cancel
    }
    contextStoppedSequences.remove(seqId);
    try {
      delegate.cancelSequence(seqId);
      LOGGER.warn("Sequence {} cancelled — generation stopped before completion", seqId);
    } catch (RuntimeException e) {
      LOGGER.warn("cancelSequence({}) failed on engine: {}", seqId, e.getMessage());
    }
    subject.onComplete();
  }

  /**
   * Ensures the request carries a rendered prompt when the caller supplied
   * messages only. This is the direct-model path (no pipeline executor to
   * pre-render); we apply the model's own chat template via Jinja4j so that
   * llama.cpp and vLLM produce identical outputs for identical inputs.
   *
   * <p>If the request already has a non-blank prompt, it is returned unchanged:
   * the pipeline executor has authority and its rendered output wins. The
   * {@code messages} list is retained either way for downstream multimodal
   * media extraction.
   *
   * <p>If no chat template is available (model has none, or reading it fails)
   * we log once and hand the request to the engine as-is — llama.cpp's native
   * {@code LlamaTemplate.applyTemplate} will then fall back to its own
   * template application (see {@code Model.promptFor}).
   */
  /**
   * Trims the request's chat history to this engine's context window (see
   * {@link ChatWindowTrimmer}). No-op when the engine does not report a
   * context size or the conversation already fits. Uses this engine's own
   * {@link #countTokens(String)} when available, estimation otherwise.
   */
  private TextGenRequest maybeTrimHistory(TextGenRequest request) {
    int ctx = contextSize();
    if (ctx <= 0) {
      return request;
    }
    TokenCounter estimator = TokenCounter.estimator();
    TokenCounter counter = text -> {
      int exact = countTokens(text);
      return exact >= 0 ? exact : estimator.count(text);
    };
    String toolOpenTag = (request.toolCallTags() != null &&
        request.toolCallTags().openToken() != null &&
        !request.toolCallTags().openToken().isBlank())
      ? request.toolCallTags().openToken()
      : ChatWindowTrimmer.DEFAULT_TOOL_OPEN_TAG;
    List<ChatTurn> trimmed = ChatWindowTrimmer.trimTurns(
      request.messages(),
      ctx,
      request.maxTokens() != null ? request.maxTokens() : 0,
      counter,
      toolOpenTag
    );
    if (trimmed == request.messages()) {
      return request;
    }
    LOGGER.info(
      "Direct-model path: trimmed {}→{} messages to fit context budget {}",
      request.messages().size(),
      trimmed.size(),
      ctx
    );
    return new TextGenRequest(
      request.prompt(),
      trimmed,
      request.maxTokens(),
      request.temperature(),
      request.topP(),
      request.presencePenalty(),
      request.frequencyPenalty(),
      request.stop(),
      request.seed(),
      request.reasoningTags(),
      request.toolCallTags(),
      request.loraName(),
      request.loraPath(),
      request.templateContext(),
      request.cacheKey()
    );
  }

  private TextGenRequest maybePreRender(TextGenRequest request) {
    if (request.prompt() != null && !request.prompt().isBlank()) {
      return request;
    }
    if (request.messages() == null || request.messages().isEmpty()) {
      return request;
    }

    // Context-window history trimming (direct path — no pipeline executor,
    // hence no config surface: always on when the engine knows its window).
    // Runs before rendering so the rendered prompt AND the retained messages
    // (multimodal media extraction) agree; media-carrying turns are preserved
    // exactly (kept whole, never content-truncated).
    request = maybeTrimHistory(request);

    String template = chatTemplateString();
    if (template == null || template.isBlank()) {
      LOGGER.debug(
        "No chat template available on engine — letting engine handle messages natively"
      );
      return request;
    }

    Map<String, Object> extras = new HashMap<>();
    if (bosToken() != null) extras.put("bos_token", bosToken());
    if (eosToken() != null) extras.put("eos_token", eosToken());
    // Caller-supplied template variables (e.g. enable_thinking=false to
    // suppress Qwen3 reasoning). Without these, templates that gate behavior
    // on such variables silently take their default branch.
    if (request.templateContext() != null) {
      extras.putAll(request.templateContext());
    }

    // Multimodal: inject one media marker per attachment into message content BEFORE rendering,
    // so the rendered prompt carries markers matching the bitmaps the engine attaches later.
    // mediaMarker() is null for engines that don't use a literal marker (keeps vLLM unchanged).
    List<ChatTurn> renderMessages = request.messages();
    String marker = mediaMarker();
    if (marker != null) {
      renderMessages = injectMediaMarkers(request.messages(), marker);
    }

    String rendered;
    try {
      rendered = RENDERER.render(template, toChatMessages(renderMessages), null, true, extras);
    } catch (RuntimeException e) {
      // ERROR, not warn: the native fallback ignores template_context
      // (enable_thinking etc.) and may scaffold the prompt differently —
      // silent degradation here has already cost one demo.
      LOGGER.error(
        "Failed to render chat template via Jinja4j — falling back to native engine handling " +
          "(template_context variables will be IGNORED)",
        e
      );
      return request;
    }

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace(
        "Direct-model path: rendered prompt via Jinja4j ({} chars) ->\n{}",
        rendered.length(),
        rendered
      );
    }

    return new TextGenRequest(
      rendered,
      request.messages(), // retained for multimodal media extraction
      request.maxTokens(),
      request.temperature(),
      request.topP(),
      request.presencePenalty(),
      request.frequencyPenalty(),
      request.stop(),
      request.seed(),
      request.reasoningTags(),
      request.toolCallTags(),
      request.loraName(),
      request.loraPath(),
      request.templateContext(),
      request.cacheKey()
    );
  }

  @Override
  public void close() throws Exception {
    // Complete any pending subjects so subscribers don't hang
    pendingSequences.forEach((seqId, subject) -> {
      subject.onError(
        new IllegalStateException("Engine closed while sequence " + seqId + " was pending")
      );
    });
    pendingSequences.clear();
    // Error any live token streams so their subscribers terminate instead of hanging.
    streams.forEach((seqId, processor) ->
      processor.onError(
        new IllegalStateException("Engine closed while streaming sequence " + seqId)
      )
    );
    streams.clear();
    contextStoppedSequences.clear();
    delegate.close();
  }

  /**
   * Converts a library {@link InferenceToken} to a local {@link ModelEngineToken}.
   */
  private static ModelEngineToken toLocalToken(InferenceToken<String> token) {
    return new ModelEngineToken(
      token.seqId(),
      token.token(),
      token.index(),
      token.isFinal(),
      token.finishReason(),
      token.promptTokens(),
      token.completionTokens(),
      token.reasoningTokens(),
      token.toolTokens(),
      token.performance() == null
        ? null
        : new ModelEnginePerformance(
          token.performance().startTimeMs(),
          token.performance().loadTimeMs(),
          token.performance().promptEvalTimeMs(),
          token.performance().evalTimeMs(),
          token.performance().promptTokensEvaluated(),
          token.performance().tokensGenerated(),
          token.performance().tokensReused(),
          token.performance().samplingTimeMs(),
          token.performance().sampleCount()
        ),
      token.channel(),
      token.logprobs()
    );
  }

  /**
   * Converts a local {@link TextGenRequest} to the engine-specific request type.
   * Implemented by each concrete subclass.
   */
  protected abstract REQ toEngineRequest(TextGenRequest request);

  // ------------------------------------------------------------------
  // Shared proto→library mapping helpers (package-private)
  // ------------------------------------------------------------------

  /**
   * The literal media placeholder to inject into rendered prompts for multimodal models, or
   * {@code null} when this engine does not use one (default). Overridden by the llama.cpp engine to
   * return its {@code mtmd} marker so the pre-rendered prompt's marker count matches the attached
   * bitmaps.
   */
  protected String mediaMarker() {
    return null;
  }

  /** Prepends {@code marker + "\n"} once per attachment to each turn's content, in message order. */
  static List<ChatTurn> injectMediaMarkers(List<ChatTurn> turns, String marker) {
    if (turns == null) return null;
    return turns
      .stream()
      .map(turn -> {
        int count = turn.media() == null ? 0 : turn.media().size();
        if (count == 0) return turn;
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < count; i++) {
          content.append(marker).append('\n');
        }
        content.append(turn.content() == null ? "" : turn.content());
        return new ChatTurn(turn.role(), content.toString(), turn.media());
      })
      .toList();
  }

  static List<ChatMessage> toChatMessages(List<ChatTurn> turns) {
    if (turns == null) return null;
    return turns.stream().map(AbstractTextGenEngine::toChatMessage).toList();
  }

  static ChatMessage toChatMessage(ChatTurn turn) {
    var role = switch (turn.role()) {
      case SYSTEM -> Role.SYSTEM;
      case USER -> Role.USER;
      case ASSISTANT -> Role.ASSISTANT;
      // The engine-side rendering path speaks the three-role vocabulary of gravitee-inference-api,
      // which has no tool role. Server-side rendering (the default for local GGUF models) keeps
      // the tool turn intact; here it degrades to a user turn rather than vanishing.
      case TOOL -> Role.USER;
    };

    List<Content> media = Optional.ofNullable(turn.media())
      .orElse(List.of())
      .stream()
      .map(AbstractTextGenEngine::buildContent)
      .toList();

    return new ChatMessage(role, turn.content(), media);
  }

  private static Content buildContent(MediaAttachment m) {
    var mt = toLibraryMediaType(m.mediaType());
    boolean isImage = mt.value().startsWith("image");
    return isImage ? new ImageContent(mt, m.data()) : new AudioContent(mt, m.data());
  }

  static MediaType toLibraryMediaType(MediaAttachmentType type) {
    return switch (type) {
      case IMAGE_JPEG -> MediaType.IMAGE_JPEG;
      case IMAGE_PNG -> MediaType.IMAGE_PNG;
      case IMAGE_GIF -> MediaType.IMAGE_GIF;
      case IMAGE_BMP -> MediaType.IMAGE_BMP;
      case AUDIO_WAV -> MediaType.AUDIO_WAV;
      case APPLICATION_OCTET_STREAM -> MediaType.APPLICATION_OCTET_STREAM;
    };
  }

  static io.gravitee.singularitee.inference.api.textgen.TagConfig toLibraryTagConfig(
    io.gravitee.singularitee.inference.api.textgen.TagConfig config
  ) {
    if (config == null || !config.isConfigured()) {
      return new io.gravitee.singularitee.inference.api.textgen.TagConfig(null, null);
    }
    return config;
  }
}
