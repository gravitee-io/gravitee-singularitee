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
package io.gravitee.singularitee.service;

import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.adapter.gliner.GlinerClassifierFactory;
import io.gravitee.singularitee.adapter.gliner.GlinerNerFactory;
import io.gravitee.singularitee.adapter.textgen.LlamaCppEngineFactory;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.engine.TextGenEngine;
import io.gravitee.singularitee.grpc.resolver.GgufModelResolver;
import io.gravitee.singularitee.grpc.resolver.GlinerModelResolver;
import io.gravitee.singularitee.grpc.resolver.OnnxModelResolver;
import io.gravitee.singularitee.grpc.resolver.VllmModelResolver;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.workspace.ModelLoadRequest;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x gRPC service implementation for model lifecycle management (read-only: Get / List).
 *
 * <p>Implements the generated {@code GraviteeModelServiceApi} and delegates all
 * engine creation to {@link ModelEngineFactory} implementations. The registry
 * manages the running engines; this service translates gRPC requests into
 * registry operations.
 *
 * <p>Models are loaded at startup via {@link #loadAndRegisterModel(ModelLoadRequest)}
 * (called by {@code WorkspaceLoaderComponent}) — there is no public gRPC endpoint
 * to publish models at runtime.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GraviteeModelServiceImpl
  extends GraviteeModelServiceGrpcService
  implements io.gravitee.singularitee.pipeline.executor.StreamRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraviteeModelServiceImpl.class);

  private final Vertx vertx;
  private final ModelRegistry registry;
  private final Map<ModelType, ModelEngineFactory> factories;
  private final GgufModelResolver ggufResolver;
  private final OnnxModelResolver onnxResolver;
  private final GlinerModelResolver glinerResolver;
  private final VllmModelResolver vllmResolver;

  /**
   * Per-model map of active stream contexts, keyed by sequence ID.
   * Bridges the engine's callback-based token emission to gRPC streaming responses.
   */
  private final ConcurrentHashMap<
    String,
    ConcurrentHashMap<Integer, StreamContext>
  > streamsByModel = new ConcurrentHashMap<>();

  public ConcurrentHashMap<Integer, StreamContext> streamsForModel(String modelId) {
    return streamsByModel.get(modelId);
  }

  public GraviteeModelServiceImpl(
    Vertx vertx,
    ModelRegistry registry,
    Map<ModelType, ModelEngineFactory> factories,
    GgufModelResolver ggufResolver,
    OnnxModelResolver onnxResolver,
    GlinerModelResolver glinerResolver,
    VllmModelResolver vllmResolver
  ) {
    this.vertx = vertx;
    this.registry = registry;
    this.factories = factories;
    this.ggufResolver = ggufResolver;
    this.onnxResolver = onnxResolver;
    this.glinerResolver = glinerResolver;
    this.vllmResolver = vllmResolver;
  }

  /** Returns the underlying {@link ModelRegistry}. */
  public ModelRegistry modelRegistry() {
    return registry;
  }

  // ---------------------------------------------------------------------------
  // Workspace startup: load and register a local model
  // ---------------------------------------------------------------------------

  /**
   * Loads and registers a model engine from a {@link ModelLoadRequest}.
   * Called at startup by {@code WorkspaceLoaderComponent} — not a gRPC endpoint.
   *
   * @param request the model load request built by the workspace loader
   * @return a {@link Future} emitting the resolved model ID
   */
  public Future<String> loadAndRegisterModel(ModelLoadRequest request) {
    var modelType = resolveModelType(request);
    var factory = factories.get(modelType);
    if (factory == null) {
      return Future.failedFuture("Unsupported model type: " + modelType);
    }

    String requestedId = request.modelId() != null ? request.modelId() : "";
    LOGGER.info(
      "Loading model: name={}, type={}, path={}, requestedId={}",
      request.modelName(),
      modelType,
      request.modelPath(),
      requestedId.isBlank() ? "(auto)" : requestedId
    );

    return rxBuildEngine(request, factory)
      .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
      .map(engine -> {
        var activeStreams = new ConcurrentHashMap<Integer, StreamContext>();
        Consumer<ModelEngineToken> tokenConsumer = token -> dispatchToken(token, activeStreams);

        String resolvedId = registry.register(
          requestedId,
          request.modelName(),
          engine,
          tokenConsumer,
          request.task(),
          request.visible(),
          request.modalities()
        );
        streamsByModel.put(resolvedId, activeStreams);
        return resolvedId;
      })
      .doOnError(e ->
        LOGGER.error("Failed to load model '{}': {}", request.modelName(), e.getMessage(), e)
      )
      .to(single -> {
        var promise = io.vertx.core.Promise.<String>promise();
        single.subscribe(promise::complete, promise::fail);
        return promise.future();
      });
  }

  // ---------------------------------------------------------------------------
  // Register pre-built engine (for remote models loaded from workspace)
  // ---------------------------------------------------------------------------

  /**
   * Registers a pre-built {@link ModelEngine} directly into the model registry
   * and stream map. Used for remote models declared in workspace YAML.
   */
  public String registerPrebuiltModel(String modelId, String modelName, ModelEngine engine) {
    return registerPrebuiltModel(modelId, modelName, engine, "", true);
  }

  /**
   * Registers a pre-built {@link ModelEngine} along with the publication metadata
   * its workspace entry declared — the task override and catalogue visibility.
   */
  public String registerPrebuiltModel(
    String modelId,
    String modelName,
    ModelEngine engine,
    String task,
    boolean visible
  ) {
    var activeStreams = new ConcurrentHashMap<Integer, StreamContext>();
    Consumer<ModelEngineToken> tokenConsumer = token -> dispatchToken(token, activeStreams);

    String resolvedId = registry.register(modelId, modelName, engine, tokenConsumer, task, visible);
    streamsByModel.put(resolvedId, activeStreams);

    LOGGER.info(
      "Registered pre-built model: id={}, name={}, engine={}",
      resolvedId,
      modelName,
      engine.getClass().getSimpleName()
    );
    return resolvedId;
  }

  // ---------------------------------------------------------------------------
  // GetModel
  // ---------------------------------------------------------------------------

  @Override
  public Future<GetModelResponse> getModel(GetModelRequest request) {
    var entryOpt = registry.get(request.getModelId());
    if (entryOpt.isEmpty()) {
      return Future.failedFuture("Model not found: " + request.getModelId());
    }
    var entry = entryOpt.get();
    var builder = GetModelResponse.newBuilder()
      .setModelId(request.getModelId())
      .setModelName(entry.modelName())
      .setModelType(resolveModelTypeFromEngine(entry.engine()))
      .setStatus(ModelStatus.MODEL_STATUS_ACTIVE)
      .setTask(entry.task())
      .setHidden(!entry.visible())
      .addAllInputModalities(entry.inputModalities());

    if (entry.engine() instanceof TextGenEngine tge) {
      if (tge.chatTemplateString() != null) builder.setChatTemplate(tge.chatTemplateString());
      if (tge.bosToken() != null) builder.setBosToken(tge.bosToken());
      if (tge.eosToken() != null) builder.setEosToken(tge.eosToken());
    }

    return Future.succeededFuture(builder.build());
  }

  // ---------------------------------------------------------------------------
  // ListModels
  // ---------------------------------------------------------------------------

  @Override
  public Future<ListModelsResponse> listModels(ListModelsRequest request) {
    var builder = ListModelsResponse.newBuilder();
    for (var kv : registry.entries()) {
      if (!kv.getValue().visible()) continue;
      builder.addModels(
        GetModelResponse.newBuilder()
          .setModelId(kv.getKey())
          .setModelName(kv.getValue().modelName())
          .setModelType(resolveModelTypeFromEngine(kv.getValue().engine()))
          .setStatus(ModelStatus.MODEL_STATUS_ACTIVE)
          .setTask(kv.getValue().task())
          .addAllInputModalities(kv.getValue().inputModalities())
          .build()
      );
    }
    return Future.succeededFuture(builder.build());
  }

  // ---------------------------------------------------------------------------
  // Token dispatch (shared with GraviteeInferenceServiceImpl)
  // ---------------------------------------------------------------------------

  void dispatchToken(
    ModelEngineToken token,
    ConcurrentHashMap<Integer, StreamContext> activeStreams
  ) {
    var ctx = activeStreams.get(token.seqId());
    if (ctx == null) return;

    try {
      var stream = ctx.stream();

      // Emit CREATED event on the first token for this sequence.
      if (ctx.createdEmitted().compareAndSet(false, true)) {
        var created = InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_CREATED)
          .setResponseCreated(
            ResponseCreated.newBuilder()
              .setResponseId(ctx.requestId() != null ? ctx.requestId() : "")
              .setModel(ctx.modelId() != null ? ctx.modelId() : "")
              .build()
          )
          .build();
        stream.write(created);
      }

      if (token.isFinal()) {
        // Emit COMPLETED event with usage + performance + finish_reason.
        var completed = InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_COMPLETED)
          .setResponseCompleted(
            ResponseCompleted.newBuilder()
              .setUsage(
                TokenUsage.newBuilder()
                  .setPromptTokens(token.promptTokens())
                  .setCompletionTokens(token.completionTokens())
                  .setReasoningTokens(token.reasoningTokens())
                  .setToolTokens(token.toolTokens())
                  .build()
              )
              .setFinishReason(toProtoFinishReason(token.finishReason()))
              .setPerformance(
                token.performance() != null
                  ? toProtoPerformance(token.performance())
                  : InferencePerformance.getDefaultInstance()
              )
              .build()
          )
          .build();
        stream.end(completed);
        activeStreams.remove(token.seqId());
      } else if (token.token() != null && !token.token().isEmpty()) {
        // Emit OUTPUT_TEXT_DELTA event for each token.
        var delta = InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
          .setResponseOutputTextDelta(
            ResponseOutputTextDelta.newBuilder().setDelta(token.token()).build()
          )
          .build();
        stream.write(delta);
      }
    } catch (Exception e) {
      LOGGER.error("Error dispatching token for seq {}: {}", token.seqId(), e.getMessage());
      activeStreams.remove(token.seqId());
    }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private Single<ModelEngine> rxBuildEngine(ModelLoadRequest request, ModelEngineFactory factory) {
    // llama.cpp text-gen — resolve / download the single GGUF file (blocking, offloaded to IO)
    if (
      factory instanceof LlamaCppEngineFactory llamaFactory &&
      ggufResolver != null &&
      request.modelPath() != null &&
      !request.modelPath().isEmpty()
    ) {
      return Single.fromCallable(() -> {
        Path resolved = ggufResolver.resolve(request.modelName(), request.modelPath());
        // mmproj / lora sidecars live in the same HF repo — resolve/download them too,
        // otherwise the engine receives bare filenames and fails to load (VLM/ALM, LoRA).
        var cfg = request.llamaCppConfig();
        Path mmproj = cfg.mmprojPath().isEmpty()
          ? null
          : ggufResolver.resolve(request.modelName(), cfg.mmprojPath());
        Path lora = cfg.loraPath().isEmpty()
          ? null
          : ggufResolver.resolve(request.modelName(), cfg.loraPath());
        // Speculative sidecars: a draft model or EAGLE3 head normally lives in its OWN repo, so
        // each carries an optional repo id and only falls back to the target's.
        Path draft = cfg.draftPath().isEmpty()
          ? null
          : ggufResolver.resolve(
            cfg.draftModel().isEmpty() ? request.modelName() : cfg.draftModel(),
            cfg.draftPath()
          );
        Path eagle3 = cfg.eagle3Path().isEmpty()
          ? null
          : ggufResolver.resolve(
            cfg.eagle3Model().isEmpty() ? request.modelName() : cfg.eagle3Model(),
            cfg.eagle3Path()
          );
        return llamaFactory.create(request, resolved, mmproj, lora, draft, eagle3);
      }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
    }

    // llama.cpp embedding — same GGUF resolution path as text-gen
    if (
      factory instanceof
        io.gravitee.singularitee.adapter.embedding.LlamaCppEmbeddingFactory llamaEmbedFactory &&
      ggufResolver != null &&
      request.modelPath() != null &&
      !request.modelPath().isEmpty()
    ) {
      return Single.fromCallable(() -> {
        Path resolved = ggufResolver.resolve(request.modelName(), request.modelPath());
        return llamaEmbedFactory.create(request, resolved);
      }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
    }

    // llama.cpp reranker — same GGUF resolution path as text-gen
    if (
      factory instanceof
        io.gravitee.singularitee.adapter.reranker.LlamaCppRerankerFactory llamaRerankerFactory &&
      ggufResolver != null &&
      request.modelPath() != null &&
      !request.modelPath().isEmpty()
    ) {
      return Single.fromCallable(() -> {
        Path resolved = ggufResolver.resolve(request.modelName(), request.modelPath());
        return (ModelEngine) llamaRerankerFactory.create(request, resolved);
      }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
    }

    // vLLM — download the model in Java first, then hand the engine a directory.
    // Same reason as every other backend: one cache, one download path, and a
    // load that needs no network once warm.
    if (
      factory instanceof io.gravitee.singularitee.adapter.textgen.VllmEngineFactory vllmFactory &&
      vllmResolver != null
    ) {
      return vllmResolver
        .resolve(request.modelName(), request.downloadExclude())
        .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
        .map(resolved -> vllmFactory.create(request, resolved));
    }

    // ONNX — resolve / download model, tokenizer, and optional config (fully reactive)
    if (
      (factory instanceof io.gravitee.singularitee.adapter.classifier.OnnxClassifierFactory ||
        factory instanceof io.gravitee.singularitee.adapter.embedding.OnnxEmbeddingFactory ||
        factory instanceof io.gravitee.singularitee.adapter.reranker.OnnxRerankerFactory) &&
      onnxResolver != null
    ) {
      return onnxResolver
        .resolve(request)
        .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
        .map(factory::create);
    }

    // GLiNER — resolve / download model directory reactively (download stays off the event loop)
    if (
      (factory instanceof GlinerClassifierFactory || factory instanceof GlinerNerFactory) &&
      glinerResolver != null
    ) {
      return glinerResolver
        .resolve(request)
        .observeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
        .map(factory::create);
    }

    return Single.fromCallable(() -> factory.create(request)).subscribeOn(
      io.reactivex.rxjava3.schedulers.Schedulers.io()
    );
  }

  private ModelType resolveModelTypeFromEngine(ModelEngine engine) {
    return switch (engine.type()) {
      case TEXT_GEN -> ModelType.MODEL_TYPE_LLAMA_CPP;
      case CLASSIFIER -> ModelType.MODEL_TYPE_ONNX_CLASSIFIER;
      case EMBEDDING -> ModelType.MODEL_TYPE_ONNX_EMBEDDING;
      case RERANKER -> ModelType.MODEL_TYPE_ONNX_RERANKER;
    };
  }

  private static ModelType resolveModelType(ModelLoadRequest req) {
    if (req.hasLlamaCppConfig()) return ModelType.MODEL_TYPE_LLAMA_CPP;
    if (req.hasVllmConfig()) return ModelType.MODEL_TYPE_VLLM;
    if (req.hasOnnxClassifier()) return ModelType.MODEL_TYPE_ONNX_CLASSIFIER;
    if (req.hasOnnxEmbedding()) return ModelType.MODEL_TYPE_ONNX_EMBEDDING;
    if (req.hasOnnxReranker()) return ModelType.MODEL_TYPE_ONNX_RERANKER;
    if (req.hasGlinerClassifier()) return ModelType.MODEL_TYPE_GLINER_CLASSIFIER;
    if (req.hasGlinerNer()) return ModelType.MODEL_TYPE_GLINER_NER;
    if (req.hasLlamaCppEmbedding()) return ModelType.MODEL_TYPE_LLAMA_CPP_EMBEDDING;
    if (req.hasLlamaCppReranker()) return ModelType.MODEL_TYPE_LLAMA_CPP_RERANKER;
    return ModelType.MODEL_TYPE_UNSPECIFIED;
  }

  private static FinishReason toProtoFinishReason(String reason) {
    if (reason == null) return FinishReason.FINISH_REASON_UNSPECIFIED;
    return switch (reason) {
      case "stop" -> FinishReason.FINISH_REASON_STOP;
      case "length", "length_prompt", "length_runaway" -> FinishReason.FINISH_REASON_LENGTH;
      case "tool_calls" -> FinishReason.FINISH_REASON_TOOL_CALLS;
      case "cancelled" -> FinishReason.FINISH_REASON_CANCELLED;
      case "stalled" -> FinishReason.FINISH_REASON_STALLED;
      default -> FinishReason.FINISH_REASON_UNSPECIFIED;
    };
  }

  private static InferencePerformance toProtoPerformance(
    io.gravitee.singularitee.engine.ModelEnginePerformance perf
  ) {
    return InferencePerformance.newBuilder()
      .setStartTimeMs(perf.startTimeMs())
      .setLoadTimeMs(perf.loadTimeMs())
      .setPromptEvalTimeMs(perf.promptEvalTimeMs())
      .setEvalTimeMs(perf.evalTimeMs())
      .setPromptTokensEvaluated(perf.promptTokensEvaluated())
      .setTokensGenerated(perf.tokensGenerated())
      .setTokensReused(perf.tokensReused())
      .setSamplingTimeMs(perf.samplingTimeMs())
      .setSampleCount(perf.sampleCount())
      .build();
  }
}
