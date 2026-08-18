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
package io.gravitee.singularitee.standalone.spring;

import io.gravitee.node.api.Node;
import io.gravitee.node.api.cache.CacheManager;
import io.gravitee.node.api.cluster.ClusterManager;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.api.opentelemetry.InstrumenterTracerFactory;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.node.api.opentelemetry.TracerFactory;
import io.gravitee.node.plugin.cache.standalone.StandaloneCacheManager;
import io.gravitee.node.plugin.cluster.standalone.StandaloneClusterManager;
import io.gravitee.singularitee.adapter.ModelEngineFactory;
import io.gravitee.singularitee.adapter.classifier.OnnxClassifierFactory;
import io.gravitee.singularitee.adapter.embedding.LlamaCppEmbeddingFactory;
import io.gravitee.singularitee.adapter.embedding.OnnxEmbeddingFactory;
import io.gravitee.singularitee.adapter.gliner.GlinerClassifierFactory;
import io.gravitee.singularitee.adapter.gliner.GlinerNerFactory;
import io.gravitee.singularitee.adapter.reranker.LlamaCppRerankerFactory;
import io.gravitee.singularitee.adapter.reranker.OnnxRerankerFactory;
import io.gravitee.singularitee.adapter.textgen.LlamaCppEngineFactory;
import io.gravitee.singularitee.adapter.textgen.VllmEngineFactory;
import io.gravitee.singularitee.engine.StreamingConfig;
import io.gravitee.singularitee.grpc.resolver.GgufModelResolver;
import io.gravitee.singularitee.grpc.resolver.GlinerModelResolver;
import io.gravitee.singularitee.grpc.resolver.HuggingFaceModelDownloader;
import io.gravitee.singularitee.grpc.resolver.OnnxModelResolver;
import io.gravitee.singularitee.grpc.resolver.VllmModelResolver;
import io.gravitee.singularitee.inference.math.api.GioMaths;
import io.gravitee.singularitee.inference.math.vanilla.NativeMath;
import io.gravitee.singularitee.metrics.InferenceMetrics;
import io.gravitee.singularitee.pipeline.ConversationStore;
import io.gravitee.singularitee.pipeline.PipelineExecutor;
import io.gravitee.singularitee.pipeline.TodoSessionStore;
import io.gravitee.singularitee.pipeline.executor.JinjaRenderer;
import io.gravitee.singularitee.pipeline.executor.StepDispatcher;
import io.gravitee.singularitee.pipeline.executor.StepExecutorFactory;
import io.gravitee.singularitee.protocol.ModelType;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.service.GraviteeInferenceServiceImpl;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.service.GraviteePipelineServiceImpl;
import io.gravitee.singularitee.service.GraviteeVectorServiceImpl;
import io.gravitee.singularitee.standalone.node.SingulariteeNode;
import io.gravitee.singularitee.standalone.vertx.GrpcServerComponent;
import io.gravitee.singularitee.standalone.vertx.HttpApiServerComponent;
import io.gravitee.singularitee.standalone.vertx.WorkspaceLoaderComponent;
import io.vertx.rxjava3.core.Vertx;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Main Spring configuration for Singularitee.
 *
 * <p>Replaces all manual wiring previously in {@code Singularitee.java}. All beans
 * are created with the managed Vert.x RxJava3 instance from gravitee-node's
 * {@code VertxConfiguration}.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
@org.springframework.context.annotation.Configuration
public class SingulariteeConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(SingulariteeConfiguration.class);

  @Bean
  public ClusterManager clusterManager(Vertx vertx) {
    return new StandaloneClusterManager(vertx.getDelegate());
  }

  /**
   * Pluggable cache backing cross-request state (todo sessions). Standalone
   * in-memory by default — swap this one bean for the hazelcast/redis cache
   * plugins to get a distributed backend; no engine code changes.
   */
  @Bean
  public CacheManager cacheManager() {
    return new StandaloneCacheManager();
  }

  /**
   * Cross-request todo-plan persistence keyed by the request's cache_key
   * (OpenAI prompt_cache_key / user). Idle TTL is the session timeout;
   * {@code ai.todos.session-ttl: 0} disables persistence.
   */
  @Bean
  public TodoSessionStore todoSessionStore(Configuration configuration, CacheManager cacheManager) {
    long ttlSeconds = getLongProperty(configuration, "ai.todos.session-ttl", 1800L);
    long maxEntries = getLongProperty(configuration, "ai.todos.session-max-entries", 10000L);
    return new TodoSessionStore(cacheManager, ttlSeconds, maxEntries);
  }

  @Bean
  public Node node() {
    return new SingulariteeNode();
  }

  // ── Observability (OpenTelemetry tracing + Micrometer metrics) ─────────

  /**
   * The server-side OpenTelemetry tracer, built from the gravitee-node OTel factories
   * (already in the context via {@code OpenTelemetrySpringConfiguration}). Returns a no-op
   * tracer when {@code services.opentelemetry.enabled} is false, so it is always safe to
   * inject. Its lifecycle (start/stop) is owned by {@link GrpcServerComponent}.
   */
  @Bean
  public Tracer singulariteeTracer(
    TracerFactory tracerFactory,
    List<InstrumenterTracerFactory> instrumenterTracerFactories,
    Node node
  ) {
    String instanceId = node.id() != null ? node.id() : SingulariteeNode.APPLICATION_NAME;
    return tracerFactory.createTracer(
      instanceId,
      SingulariteeNode.APPLICATION_NAME,
      "gravitee",
      "",
      instrumenterTracerFactories
    );
  }

  /**
   * Micrometer-backed inference metrics, bound to the live registry that feeds the
   * Prometheus endpoint ({@code io.gravitee.node.monitoring.metrics.Metrics}). The
   * {@link Vertx} parameter forces creation after Vert.x — and therefore after the metrics
   * registry is bound. Records to a no-op registry when {@code services.metrics} is disabled.
   */
  @Bean
  public InferenceMetrics inferenceMetrics(Vertx vertx) {
    return new InferenceMetrics(io.gravitee.node.monitoring.metrics.Metrics.getDefaultRegistry());
  }

  // ── Core registries ───────────────────────────────────────────────────

  @Bean
  public ModelRegistry modelRegistry() {
    return new ModelRegistry();
  }

  @Bean
  public PipelineRegistry pipelineRegistry(ModelRegistry modelRegistry) {
    return new PipelineRegistry(modelRegistry);
  }

  // ── Math / Vector ─────────────────────────────────────────────────────

  @Bean
  public GioMaths gioMaths() {
    return NativeMath.INSTANCE;
  }

  // ── Model resolvers (HuggingFace download) ────────────────────────────

  /**
   * One downloader shared by every resolver: one HTTP connection pool instead of four,
   * closed by Spring on shutdown (inferred {@code close()} destroy method).
   */
  @Bean
  public HuggingFaceModelDownloader huggingFaceModelDownloader(
    Vertx vertx,
    Configuration configuration
  ) {
    return new HuggingFaceModelDownloader(
      vertx,
      getHfToken(configuration),
      getDownloadOptions(configuration)
    );
  }

  @Bean
  public GgufModelResolver ggufModelResolver(
    HuggingFaceModelDownloader downloader,
    Configuration configuration
  ) {
    return new GgufModelResolver(downloader, getModelsDir(configuration));
  }

  @Bean
  public OnnxModelResolver onnxModelResolver(
    HuggingFaceModelDownloader downloader,
    Configuration configuration
  ) {
    return new OnnxModelResolver(downloader, getModelsDir(configuration));
  }

  @Bean
  public GlinerModelResolver glinerModelResolver(
    HuggingFaceModelDownloader downloader,
    Configuration configuration
  ) {
    return new GlinerModelResolver(downloader, getModelsDir(configuration));
  }

  /**
   * Downloads vLLM models in Java, so every backend shares one cache and one
   * download path instead of vLLM fetching its own copy through CPython.
   */
  @Bean
  public VllmModelResolver vllmModelResolver(
    HuggingFaceModelDownloader downloader,
    Configuration configuration
  ) {
    return new VllmModelResolver(downloader, getModelsDir(configuration));
  }

  private static Path getModelsDir(Configuration configuration) {
    String modelsPath = configuration.getProperty("ai.models.path", "");
    if (!modelsPath.isBlank()) {
      return Path.of(modelsPath);
    }
    String graviteeHome = System.getProperty("gravitee.home", "");
    if (!graviteeHome.isBlank()) {
      return Path.of(graviteeHome, "models");
    }
    return Path.of(System.getProperty("user.home"), ".cache", "gravitee-singularitee", "models");
  }

  /**
   * Chunked-download tuning (hf_transfer-style parallel Range requests). Peak buffered
   * memory is {@code parallelism × chunkSize}.
   */
  private static HuggingFaceModelDownloader.Options getDownloadOptions(
    Configuration configuration
  ) {
    HuggingFaceModelDownloader.Options defaults = HuggingFaceModelDownloader.Options.defaults();
    long chunkSize = getLongProperty(
      configuration,
      "ai.huggingface.download.chunkSize",
      defaults.chunkSizeBytes()
    );
    int parallelism = (int) getLongProperty(
      configuration,
      "ai.huggingface.download.parallelism",
      defaults.parallelism()
    );
    long threshold = getLongProperty(
      configuration,
      "ai.huggingface.download.chunkedThreshold",
      2 * chunkSize
    );
    return new HuggingFaceModelDownloader.Options(chunkSize, parallelism, threshold);
  }

  private static long getLongProperty(Configuration configuration, String key, long defaultValue) {
    String value = configuration.getProperty(key, "");
    if (value.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      LOGGER.warn("Invalid numeric value '{}' for {}; using default {}", value, key, defaultValue);
      return defaultValue;
    }
  }

  private static String getHfToken(Configuration configuration) {
    String hfToken = configuration.getProperty("ai.huggingface.token", "");
    if (hfToken.isBlank()) {
      hfToken = System.getenv("HF_TOKEN");
    }
    return hfToken;
  }

  private static int getStreamBufferCapacity(Configuration configuration) {
    String value = configuration.getProperty("ai.streaming.buffer-capacity", "");
    if (value.isBlank()) {
      return StreamingConfig.DEFAULT_STREAM_BUFFER_CAPACITY;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return StreamingConfig.DEFAULT_STREAM_BUFFER_CAPACITY;
    }
  }

  /**
   * Reads the deployment-wide vLLM GPU topology.
   *
   * <p>Resolving this belongs to the server, not to the inference library: it
   * is a property of where the process runs, not of any one model. gravitee-node
   * already layers gravitee.yml under {@code GRAVITEE_*} environment variables,
   * so a container can set {@code GRAVITEE_AI_VLLM_TENSORPARALLELSIZE=4}
   * without any workspace change, and a model that needs a different topology
   * still overrides it in its own YAML.
   */
  private static VllmEngineFactory.DistributedDefaults getVllmDistributedDefaults(
    Configuration configuration
  ) {
    String backend = configuration.getProperty("ai.vllm.distributed-executor-backend", "");
    return new VllmEngineFactory.DistributedDefaults(
      getPositiveInt(configuration, "ai.vllm.tensor-parallel-size"),
      getPositiveInt(configuration, "ai.vllm.pipeline-parallel-size"),
      backend.isBlank() ? null : backend.trim()
    );
  }

  /** Reads a positive int property, or 0 when unset or unparseable. */
  private static int getPositiveInt(Configuration configuration, String key) {
    String value = configuration.getProperty(key, "");
    if (value.isBlank()) {
      return 0;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : 0;
    } catch (NumberFormatException e) {
      LOGGER.warn("Ignoring non-numeric {}: '{}'", key, value);
      return 0;
    }
  }

  // ── Engine factories ──────────────────────────────────────────────────

  /** Third-party classes probed to decide which engines this distribution ships. */
  static final String LLAMA_CPP_PROBE = "io.gravitee.llama.cpp.LlamaModel";

  static final String VLLM_PROBE = "io.gravitee.vllm.engine.VllmEngine";
  static final String ONNX_PROBE = "ai.onnxruntime.OrtEnvironment";
  static final String GLINER_PROBE = "io.gravitee.lab.gliner4j.runtime.BaseRuntime";

  @Bean
  public Map<ModelType, ModelEngineFactory> engineFactories(
    Vertx vertx,
    GioMaths gioMaths,
    Configuration configuration
  ) {
    // Server-wide streaming policy (gravitee.yml: ai.streaming.buffer-capacity): the
    // per-sequence token buffer depth a slow client may fall behind before its stream
    // is cancelled. Set once here, before any engine is created or any request streams.
    StreamingConfig.setStreamBufferCapacity(getStreamBufferCapacity(configuration));

    // Registration is conditional on the backing library actually being on the
    // classpath. The distribution ships in per-engine flavours (see the onnx /
    // llama / vllm assembly profiles): the ONNX image carries no llamaj.cpp and
    // no vLLM4j, and so on. Instantiating every factory unconditionally would
    // fail those images at startup with NoClassDefFoundError, long before anyone
    // asks for a model. A flavour simply advertises the engines it has.
    Map<ModelType, ModelEngineFactory> factories = new EnumMap<>(ModelType.class);

    registerIfPresent(factories, ModelType.MODEL_TYPE_LLAMA_CPP, LLAMA_CPP_PROBE, () ->
      new LlamaCppEngineFactory()
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_LLAMA_CPP_EMBEDDING, LLAMA_CPP_PROBE, () ->
      new LlamaCppEmbeddingFactory(gioMaths, vertx)
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_LLAMA_CPP_RERANKER, LLAMA_CPP_PROBE, () ->
      new LlamaCppRerankerFactory(gioMaths, vertx)
    );

    var vllmDefaults = getVllmDistributedDefaults(configuration);
    registerIfPresent(factories, ModelType.MODEL_TYPE_VLLM, VLLM_PROBE, () ->
      new VllmEngineFactory(vllmDefaults)
    );

    registerIfPresent(factories, ModelType.MODEL_TYPE_ONNX_CLASSIFIER, ONNX_PROBE, () ->
      new OnnxClassifierFactory(gioMaths, vertx)
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_ONNX_EMBEDDING, ONNX_PROBE, () ->
      new OnnxEmbeddingFactory(gioMaths, vertx)
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_ONNX_RERANKER, ONNX_PROBE, () ->
      new OnnxRerankerFactory(gioMaths, vertx)
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_GLINER_CLASSIFIER, GLINER_PROBE, () ->
      new GlinerClassifierFactory(vertx)
    );
    registerIfPresent(factories, ModelType.MODEL_TYPE_GLINER_NER, GLINER_PROBE, () ->
      new GlinerNerFactory(vertx)
    );

    LOGGER.info(
      "Engine factories available in this distribution: {}",
      factories.keySet().stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
    );
    return Map.copyOf(factories);
  }

  /**
   * Registers {@code factory} only when {@code probeClass} resolves.
   *
   * <p>The probe is a class from the third-party engine library rather than
   * from our own adapter, because it is the library JAR that a per-engine
   * distribution leaves out.
   */
  private static void registerIfPresent(
    Map<ModelType, ModelEngineFactory> factories,
    ModelType type,
    String probeClass,
    Supplier<ModelEngineFactory> factory
  ) {
    if (!isPresent(probeClass)) {
      LOGGER.debug("{} not registered — {} is not on the classpath", type, probeClass);
      return;
    }
    try {
      factories.put(type, factory.get());
    } catch (RuntimeException | LinkageError e) {
      // The JAR is present but unusable (e.g. a native library the engine loads
      // eagerly is missing). Degrade to "this engine is unavailable" rather than
      // taking the whole server down with it.
      LOGGER.warn("{} not registered — factory could not be created: {}", type, e.toString());
    }
  }

  static boolean isPresent(String className) {
    try {
      Class.forName(className, false, SingulariteeConfiguration.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  // ── gRPC service implementations ──────────────────────────────────────

  @Bean
  public GraviteeModelServiceImpl modelService(
    Vertx vertx,
    ModelRegistry modelRegistry,
    Map<ModelType, ModelEngineFactory> engineFactories,
    GgufModelResolver ggufResolver,
    OnnxModelResolver onnxResolver,
    GlinerModelResolver glinerResolver,
    VllmModelResolver vllmResolver
  ) {
    return new GraviteeModelServiceImpl(
      vertx.getDelegate(),
      modelRegistry,
      engineFactories,
      ggufResolver,
      onnxResolver,
      glinerResolver,
      vllmResolver
    );
  }

  @Bean
  public GraviteePipelineServiceImpl pipelineService(PipelineRegistry pipelineRegistry) {
    return new GraviteePipelineServiceImpl(pipelineRegistry);
  }

  @Bean
  public GraviteeVectorServiceImpl vectorService(
    Vertx vertx,
    ModelRegistry modelRegistry,
    GioMaths gioMaths,
    Tracer singulariteeTracer,
    InferenceMetrics inferenceMetrics
  ) {
    return new GraviteeVectorServiceImpl(
      vertx.getDelegate(),
      modelRegistry,
      gioMaths,
      singulariteeTracer,
      inferenceMetrics
    );
  }

  // ── Pipeline execution ────────────────────────────────────────────────

  @Bean
  public JinjaRenderer jinjaRenderer() {
    return new JinjaRenderer();
  }

  @Bean
  public StepExecutorFactory stepExecutorFactory(
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    GraviteeModelServiceImpl modelService,
    JinjaRenderer jinjaRenderer,
    io.gravitee.singularitee.pipeline.TodoSessionStore todoSessionStore
  ) {
    return new StepExecutorFactory(
      modelRegistry,
      pipelineRegistry,
      modelService,
      jinjaRenderer,
      null,
      todoSessionStore
    );
  }

  @Bean
  public StepDispatcher stepDispatcher(StepExecutorFactory stepExecutorFactory) {
    return stepExecutorFactory.createDispatcher();
  }

  /**
   * Stored conversations for the OpenAI Responses continuation model
   * (previous_response_id / store). {@code ai.conversations.ttl: 0} disables.
   */
  @Bean
  public ConversationStore conversationStore(
    Configuration configuration,
    CacheManager cacheManager
  ) {
    long ttlSeconds = getLongProperty(configuration, "ai.conversations.ttl", 3600L);
    long maxEntries = getLongProperty(configuration, "ai.conversations.max-entries", 10000L);
    return new ConversationStore(cacheManager, ttlSeconds, maxEntries);
  }

  @Bean
  public PipelineExecutor pipelineExecutor(
    PipelineRegistry pipelineRegistry,
    StepDispatcher stepDispatcher,
    Tracer singulariteeTracer,
    InferenceMetrics inferenceMetrics,
    TodoSessionStore todoSessionStore,
    ConversationStore conversationStore
  ) {
    return new PipelineExecutor(
      pipelineRegistry,
      stepDispatcher,
      singulariteeTracer,
      inferenceMetrics,
      todoSessionStore,
      conversationStore
    );
  }

  @Bean
  public GraviteeInferenceServiceImpl inferenceService(
    Vertx vertx,
    ModelRegistry modelRegistry,
    PipelineExecutor pipelineExecutor,
    Tracer singulariteeTracer,
    InferenceMetrics inferenceMetrics
  ) {
    return new GraviteeInferenceServiceImpl(
      vertx.getDelegate(),
      modelRegistry,
      pipelineExecutor,
      singulariteeTracer,
      inferenceMetrics
    );
  }

  // ── Lifecycle components ──────────────────────────────────────────────

  @Bean
  public io.gravitee.singularitee.standalone.vertx.ReadinessState readinessState() {
    return new io.gravitee.singularitee.standalone.vertx.ReadinessState();
  }

  @Bean
  public WorkspaceLoaderComponent workspaceLoaderComponent(
    Configuration configuration,
    GraviteeModelServiceImpl modelService,
    StepExecutorFactory stepExecutorFactory,
    PipelineExecutor pipelineExecutor,
    PipelineRegistry pipelineRegistry,
    io.gravitee.singularitee.standalone.vertx.ReadinessState readinessState
  ) {
    return new WorkspaceLoaderComponent(
      configuration,
      modelService,
      stepExecutorFactory,
      pipelineExecutor,
      pipelineRegistry,
      readinessState
    );
  }

  @Bean
  public GrpcServerComponent grpcServerComponent(
    org.springframework.core.env.Environment environment,
    Vertx vertx,
    io.gravitee.node.vertx.server.VertxServerFactory serverFactory,
    GraviteeModelServiceImpl modelService,
    GraviteePipelineServiceImpl pipelineService,
    GraviteeInferenceServiceImpl inferenceService,
    GraviteeVectorServiceImpl vectorService,
    Tracer singulariteeTracer,
    io.gravitee.singularitee.standalone.vertx.ReadinessState readinessState
  ) {
    return new GrpcServerComponent(
      environment,
      vertx,
      serverFactory,
      modelService,
      pipelineService,
      inferenceService,
      vectorService,
      singulariteeTracer,
      readinessState
    );
  }

  @Bean
  public HttpApiServerComponent httpApiServerComponent(
    org.springframework.core.env.Environment environment,
    Vertx vertx,
    io.gravitee.node.vertx.server.VertxServerFactory serverFactory,
    GraviteeInferenceServiceImpl inferenceService,
    GraviteeVectorServiceImpl vectorService,
    GraviteeModelServiceImpl modelService,
    GraviteePipelineServiceImpl pipelineService,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    Tracer singulariteeTracer,
    io.gravitee.singularitee.standalone.vertx.ReadinessState readinessState
  ) {
    return new HttpApiServerComponent(
      environment,
      vertx,
      serverFactory,
      inferenceService,
      vectorService,
      modelService,
      pipelineService,
      modelRegistry,
      pipelineRegistry,
      singulariteeTracer,
      readinessState
    );
  }
}
