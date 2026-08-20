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
package io.gravitee.singularitee.engine.remote;

import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.*;
import io.gravitee.singularitee.pipeline.executor.JinjaContextHelper;
import io.gravitee.singularitee.protocol.*;
import io.reactivex.rxjava3.core.BackpressureOverflowStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote proxy for {@link TextGenEngine} that calls a Singularitee server over gRPC.
 *
 * <p>Fully non-blocking: {@link #rxAddSequence(int, TextGenRequest)} subscribes to the
 * server's {@code Infer} RPC stream, delivers tokens asynchronously to the consumer,
 * and returns a {@link Completable} that completes when the final token is received.
 * No {@code CountDownLatch} or {@code blockingGet()} is needed.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class RemoteTextGenEngine implements TextGenEngine {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteTextGenEngine.class);

  private final SingulariteeClient client;
  private final String modelId;
  private volatile String chatTemplate;
  private volatile String bosToken;
  private volatile String eosToken;

  /**
   * What the backend behind the proxy reads, as reported by {@code GetModel}.
   * Text-only until the probe answers; a workspace {@code modalities:} declaration
   * on the proxy entry overrides this at the registry, so the probe is only the
   * default — the thing that makes a proxy over a VLM advertise images without
   * anyone having to say so.
   */
  private volatile List<String> inputModalities = Modalities.TEXT_ONLY;

  /**
   * Whether the chat-template metadata is final. True when the caller supplied
   * it explicitly (5-arg constructor — caller authority) or after a successful
   * lazy {@code GetModel} probe. While false, {@link #chatTemplateString()}
   * keeps re-triggering the async fetch so the engine self-heals as soon as
   * the remote becomes reachable.
   */
  private volatile boolean metadataLoaded;

  private final AtomicBoolean metadataFetchInFlight = new AtomicBoolean();
  private Consumer<ModelEngineToken> tokenConsumer;

  /**
   * Per-sequence reactive token streams, keyed by seqId. When a subscriber is present,
   * relayed tokens are routed to its processor instead of the legacy callback.
   */
  private final ConcurrentHashMap<Integer, FlowableProcessor<ModelEngineToken>> streams =
    new ConcurrentHashMap<>();

  /**
   * Creates an engine whose chat-template metadata is fetched lazily from the
   * remote server via {@code GetModel}. Construction never opens an RPC and
   * never fails when the remote is down — the fetch is fired asynchronously
   * here and re-attempted on each {@link #chatTemplateString()} miss, so
   * workspace startup stays decoupled from remote availability (see the
   * registration-time comment in {@code WorkspaceLoaderComponent}).
   */
  public RemoteTextGenEngine(SingulariteeClient client, String modelId) {
    this.client = client;
    this.modelId = modelId;
    this.bosToken = "";
    this.eosToken = "";
    fetchMetadataAsync();
  }

  /**
   * Creates an engine with caller-supplied chat-template metadata. No remote
   * probe is ever made — the caller (e.g. {@code ClientPipelineExecutor},
   * which already validated the model via {@code GetModel}) has authority.
   */
  public RemoteTextGenEngine(
    SingulariteeClient client,
    String modelId,
    String chatTemplate,
    String bosToken,
    String eosToken
  ) {
    this(client, modelId, chatTemplate, bosToken, eosToken, Modalities.TEXT_ONLY);
  }

  /**
   * Creates an engine with caller-supplied chat-template metadata and the input
   * modalities the caller already read off {@code GetModel}. No remote probe is
   * ever made.
   */
  public RemoteTextGenEngine(
    SingulariteeClient client,
    String modelId,
    String chatTemplate,
    String bosToken,
    String eosToken,
    List<String> inputModalities
  ) {
    this.client = client;
    this.modelId = modelId;
    this.chatTemplate = chatTemplate;
    this.bosToken = bosToken != null ? bosToken : "";
    this.eosToken = eosToken != null ? eosToken : "";
    this.inputModalities = inputModalities == null || inputModalities.isEmpty()
      ? Modalities.TEXT_ONLY
      : List.copyOf(inputModalities);
    this.metadataLoaded = true;
  }

  @Override
  public List<String> inputModalities() {
    if (!metadataLoaded) {
      // Listing the catalogue is as good a trigger as a request: the probe that
      // fetches the template also answers what the backend reads.
      fetchMetadataAsync();
    }
    return inputModalities;
  }

  @Override
  public String chatTemplateString() {
    if (!metadataLoaded) {
      // Still unknown — kick a background refresh for subsequent requests.
      // This call intentionally returns the current (possibly null) value
      // without blocking: callers fall back to sending raw messages, which
      // the remote server renders with the model's own template.
      fetchMetadataAsync();
    }
    return chatTemplate;
  }

  @Override
  public String bosToken() {
    return bosToken;
  }

  @Override
  public String eosToken() {
    return eosToken;
  }

  /**
   * Fires a non-blocking {@code GetModel} probe to populate chat-template
   * metadata. At most one probe is in flight at a time; failures are logged
   * and the next {@link #chatTemplateString()} miss retries — request traffic
   * naturally paces the retries, so a dead remote is never hammered.
   */
  private void fetchMetadataAsync() {
    if (metadataLoaded || !metadataFetchInFlight.compareAndSet(false, true)) {
      return;
    }
    client
      .getModel(modelId)
      .subscribe(
        info -> {
          String template = info.getChatTemplate();
          // bos/eos first, template last — readers key off chatTemplate, so
          // they never observe a template paired with stale special tokens.
          bosToken = info.getBosToken() != null ? info.getBosToken() : "";
          eosToken = info.getEosToken() != null ? info.getEosToken() : "";
          if (info.getInputModalitiesCount() > 0) {
            inputModalities = List.copyOf(info.getInputModalitiesList());
          }
          if (template != null && !template.isEmpty()) {
            chatTemplate = template;
            metadataLoaded = true;
            LOGGER.info(
              "RemoteTextGenEngine: chat-template metadata loaded for model '{}' ({} chars)",
              modelId,
              template.length()
            );
          } else {
            // Success but no template on the wire — e.g. a chained remote
            // whose own lazy metadata fetch hasn't completed yet. Do NOT
            // latch metadataLoaded: the next chatTemplateString() miss
            // re-probes, so the engine still self-heals once the template
            // becomes available downstream.
            LOGGER.info(
              "RemoteTextGenEngine: GetModel for model '{}' returned no chat template yet — will re-probe on next use",
              modelId
            );
          }
          metadataFetchInFlight.set(false);
        },
        err -> {
          metadataFetchInFlight.set(false);
          LOGGER.warn(
            "RemoteTextGenEngine: GetModel metadata fetch failed for model '{}' — will retry on next use: {}",
            modelId,
            err.getMessage()
          );
        }
      );
  }

  @Override
  public void start(Consumer<ModelEngineToken> tokenConsumer) {
    this.tokenConsumer = tokenConsumer;
  }

  @Override
  public Flowable<ModelEngineToken> rxStream(int seqId) {
    int capacity = StreamingConfig.streamBufferCapacity();
    FlowableProcessor<ModelEngineToken> processor = UnicastProcessor.<
        ModelEngineToken
      >create().toSerialized();
    streams.put(seqId, processor);
    return processor
      .onBackpressureBuffer(
        capacity,
        () ->
          LOGGER.warn(
            "Remote sequence {} token-stream buffer overflowed ({} tokens) — consumer too slow",
            seqId,
            capacity
          ),
        BackpressureOverflowStrategy.ERROR
      )
      .doFinally(() -> streams.remove(seqId));
  }

  /**
   * Routes a relayed token to the per-sequence reactive stream when one is subscribed,
   * otherwise to the legacy {@code start(...)} consumer. Completes the stream on the
   * final token. Cancellation of the remote call is driven by disposal of the
   * {@link #rxAddSequence} subscription, so no per-stream cancel hook is needed here.
   */
  private void emit(int seqId, ModelEngineToken token) {
    FlowableProcessor<ModelEngineToken> processor = streams.get(seqId);
    if (processor != null) {
      processor.onNext(token);
      if (token.isFinal()) {
        streams.remove(seqId);
        processor.onComplete();
      }
    } else if (tokenConsumer != null) {
      tokenConsumer.accept(token);
    }
  }

  @Override
  public Completable rxAddSequence(int seqId, TextGenRequest request) {
    if (tokenConsumer == null) {
      return Completable.error(
        new IllegalStateException("start() must be called before rxAddSequence()")
      );
    }

    var inferReqBuilder = InferRequest.newBuilder().setModelId(modelId);

    // Map prompt / messages — prefer pre-rendered prompt (from client-side
    // chat template rendering) so that per-step context variables like
    // enable_thinking are honoured. Fall back to raw messages otherwise,
    // forwarding template_context so the server-side render still honours
    // those variables.
    if (request.prompt() != null && !request.prompt().isBlank()) {
      inferReqBuilder.setPrompt(request.prompt());
    } else if (request.messages() != null && !request.messages().isEmpty()) {
      var chatList = ChatMessageList.newBuilder();
      for (var turn : request.messages()) {
        chatList.addMessages(
          ChatMessage.newBuilder()
            .setRole(toProtoRole(turn.role()))
            .setContent(turn.content())
            .build()
        );
      }
      inferReqBuilder.setMessages(chatList.build());
      if (request.templateContext() != null && !request.templateContext().isEmpty()) {
        inferReqBuilder.setTemplateContext(
          JinjaContextHelper.mapToStruct(request.templateContext())
        );
      }
    } else if (request.prompt() != null) {
      inferReqBuilder.setPrompt(request.prompt());
    }

    // Map sampling params
    var sp = SamplingParams.newBuilder();
    if (request.maxTokens() != null) sp.setMaxTokens(request.maxTokens());
    if (request.temperature() != null) sp.setTemperature(request.temperature());
    if (request.topP() != null) sp.setTopP(request.topP());
    if (request.presencePenalty() != null) sp.setPresencePenalty(request.presencePenalty());
    if (request.frequencyPenalty() != null) sp.setFrequencyPenalty(request.frequencyPenalty());
    if (request.seed() != null) sp.setSeed(request.seed());
    inferReqBuilder.setSamplingParams(sp.build());

    if (request.stop() != null) {
      inferReqBuilder.addAllStop(request.stop());
    }

    if (request.loraName() != null || request.loraPath() != null) {
      var lora = LoraConfig.newBuilder();
      if (request.loraName() != null) lora.setLoraName(request.loraName());
      if (request.loraPath() != null) lora.setLoraPath(request.loraPath());
      inferReqBuilder.setLora(lora.build());
    }

    if (request.reasoningTags() != null) {
      inferReqBuilder.setReasoningTags(
        TagConfig.newBuilder()
          .setOpenTag(request.reasoningTags().openToken())
          .setCloseTag(request.reasoningTags().closeToken())
          .build()
      );
    }
    if (request.toolCallTags() != null) {
      inferReqBuilder.setToolCallTags(
        TagConfig.newBuilder()
          .setOpenTag(request.toolCallTags().openToken())
          .setCloseTag(request.toolCallTags().closeToken())
          .build()
      );
    }

    var inferReq = inferReqBuilder.build();
    var tokenIndex = new int[] { 0 };

    LOGGER.info("RemoteTextGenEngine: sending Infer RPC for model '{}', seqId={}", modelId, seqId);

    // Subscribe to the streaming RPC; relay each token to the consumer.
    // The Completable completes when the Flowable completes (all tokens delivered),
    // or errors if the stream errors — in both cases the final token was already
    // sent to the consumer by the onNext handler.
    return client
      .infer(inferReq)
      .doOnSubscribe(s ->
        LOGGER.info("RemoteTextGenEngine: subscribed to Infer stream for model '{}'", modelId)
      )
      .doOnNext(resp -> {
        switch (resp.getEventType()) {
          case RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA -> {
            String delta = resp.getResponseOutputTextDelta().getDelta();
            LOGGER.debug(
              "RemoteTextGenEngine: delta received for model '{}': index={}",
              modelId,
              tokenIndex[0]
            );
            emit(
              seqId,
              new ModelEngineToken(seqId, delta, tokenIndex[0]++, false, null, 0, 0, 0, 0, null)
            );
          }
          case RESPONSE_EVENT_TYPE_COMPLETED -> {
            var completed = resp.getResponseCompleted();
            LOGGER.debug(
              "RemoteTextGenEngine: completed for model '{}': finish={}",
              modelId,
              completed.getFinishReason()
            );
            emit(
              seqId,
              new ModelEngineToken(
                seqId,
                "",
                tokenIndex[0],
                true,
                completed.getFinishReason().name(),
                completed.hasUsage() ? completed.getUsage().getPromptTokens() : 0,
                completed.hasUsage() ? completed.getUsage().getCompletionTokens() : 0,
                completed.hasUsage() ? completed.getUsage().getReasoningTokens() : 0,
                completed.hasUsage() ? completed.getUsage().getToolTokens() : 0,
                completed.hasPerformance() ? toLocalPerformance(completed.getPerformance()) : null
              )
            );
          }
          case RESPONSE_EVENT_TYPE_FAILED -> {
            var failed = resp.getResponseFailed();
            LOGGER.warn(
              "RemoteTextGenEngine: inference failed for model '{}': {} - {}",
              modelId,
              failed.getErrorCode(),
              failed.getErrorMessage()
            );
            emit(
              seqId,
              new ModelEngineToken(
                seqId,
                "",
                tokenIndex[0],
                true,
                FinishReason.FINISH_REASON_UNSPECIFIED.name(),
                0,
                0,
                0,
                0,
                null
              )
            );
          }
          default -> {
            // CREATED and UNSPECIFIED — no action needed
          }
        }
      })
      .doOnError(err -> {
        LOGGER.error(
          "RemoteTextGenEngine: error during inference for model '{}': {}",
          modelId,
          err.getMessage(),
          err
        );
        // Deliver a final error token so downstream capture streams close cleanly
        emit(
          seqId,
          new ModelEngineToken(
            seqId,
            "",
            tokenIndex[0],
            true,
            FinishReason.FINISH_REASON_UNSPECIFIED.name(),
            0,
            0,
            0,
            0,
            null
          )
        );
      })
      .doOnComplete(() ->
        LOGGER.info("RemoteTextGenEngine: Infer stream completed for model '{}'", modelId)
      )
      .ignoreElements(); // Flowable<InferResponse> → Completable
  }

  @Override
  public void close() {
    // Nothing to close — the client is shared and managed externally
  }

  private static Role toProtoRole(ChatRole role) {
    return switch (role) {
      case SYSTEM -> Role.ROLE_SYSTEM;
      case ASSISTANT -> Role.ROLE_ASSISTANT;
      case TOOL -> Role.ROLE_TOOL;
      case USER -> Role.ROLE_USER;
    };
  }

  private static ModelEnginePerformance toLocalPerformance(InferencePerformance p) {
    return new ModelEnginePerformance(
      p.getStartTimeMs(),
      p.getLoadTimeMs(),
      p.getPromptEvalTimeMs(),
      p.getEvalTimeMs(),
      p.getPromptTokensEvaluated(),
      p.getTokensGenerated(),
      p.getTokensReused(),
      p.getSamplingTimeMs(),
      p.getSampleCount()
    );
  }
}
