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
package io.gravitee.singularitee.standalone.vertx;

import io.gravitee.common.service.AbstractService;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.client.SingulariteeClient;
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.engine.remote.RemoteClassifierEngine;
import io.gravitee.singularitee.engine.remote.RemoteEmbeddingEngine;
import io.gravitee.singularitee.engine.remote.RemotePipelineCallback;
import io.gravitee.singularitee.engine.remote.RemoteRerankerEngine;
import io.gravitee.singularitee.engine.remote.RemoteTextGenEngine;
import io.gravitee.singularitee.pipeline.PipelineExecutor;
import io.gravitee.singularitee.pipeline.executor.StepExecutorFactory;
import io.gravitee.singularitee.pipeline.executor.SubPipelineStepExecutor;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.service.GraviteeModelServiceImpl;
import io.gravitee.singularitee.workspace.WorkspaceDefinition;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle component that loads workspace YAML at startup.
 *
 * <p>Reads {@code ai.workspace.path} from {@code gravitee.yml} and publishes
 * all declared models and pipelines before the gRPC server opens.
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public class WorkspaceLoaderComponent extends AbstractService<WorkspaceLoaderComponent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceLoaderComponent.class);

  private final Configuration configuration;
  private final GraviteeModelServiceImpl modelService;
  private final StepExecutorFactory stepExecutorFactory;
  private final PipelineExecutor pipelineExecutor;
  private final PipelineRegistry pipelineRegistry;
  private final ReadinessState readinessState;

  /** gRPC clients created for remote endpoints. Held so they can be closed on stop. */
  private Map<String, SingulariteeClient> remoteClients = Map.of();

  public WorkspaceLoaderComponent(
    Configuration configuration,
    GraviteeModelServiceImpl modelService,
    StepExecutorFactory stepExecutorFactory,
    PipelineExecutor pipelineExecutor,
    PipelineRegistry pipelineRegistry,
    ReadinessState readinessState
  ) {
    this.configuration = configuration;
    this.modelService = modelService;
    this.stepExecutorFactory = stepExecutorFactory;
    this.pipelineExecutor = pipelineExecutor;
    this.pipelineRegistry = pipelineRegistry;
    this.readinessState = readinessState;
  }

  @Override
  protected void doStart() throws Exception {
    String workspacePath = configuration.getProperty("ai.workspace.path");
    if (workspacePath == null || workspacePath.isBlank()) {
      LOGGER.info("No workspace configured (ai.workspace.path not set). Skipping workspace load.");
      // Still set default sub-pipeline callbacks (local-only)
      stepExecutorFactory.setSubPipelineCallbacks(pipelineExecutor, Map.of());
      return;
    }

    Path path = Path.of(workspacePath);
    LOGGER.info("Loading workspace from: {}", path.toAbsolutePath());

    // Resolve the templates base path from gravitee.home (set by Bootstrap).
    String graviteeHome = System.getProperty("gravitee.home");
    Path templatesPath = graviteeHome != null ? Path.of(graviteeHome, "templates") : null;

    YamlWorkspaceLoader.WorkspaceRequests ws;
    try {
      ws = YamlWorkspaceLoader.load(path, templatesPath);
    } catch (Exception e) {
      LOGGER.error("Failed to parse workspace file {}: {}", path, e.getMessage(), e);
      stepExecutorFactory.setSubPipelineCallbacks(pipelineExecutor, Map.of());
      return;
    }

    // ── Local models (GPU-bound, loaded sequentially) ─────────────────────
    int total = ws.models().size();
    int loaded = 0;
    for (var req : ws.models()) {
      LOGGER.info("Loading models {} — {}", progressBar(loaded, total), req.modelName());
      try {
        var resolvedId = modelService
          .loadAndRegisterModel(req)
          .toCompletionStage()
          .toCompletableFuture()
          .get();
        LOGGER.info("Workspace model loaded: id={}, name={}", resolvedId, req.modelName());
      } catch (Exception e) {
        LOGGER.warn("Workspace model '{}' failed to load: {}", req.modelName(), e.getMessage());
      }
      loaded++;
    }
    if (total > 0) {
      LOGGER.info("Models loaded {}", progressBar(loaded, total));
    }

    // ── Remote models (gRPC proxies) ──────────────────────────────────────
    remoteClients = buildRemoteClients(ws.remotes(), configuration);

    for (var modelDef : ws.remoteModels()) {
      try {
        registerRemoteModel(modelDef, remoteClients);
      } catch (Exception e) {
        LOGGER.warn(
          "Workspace remote model '{}' failed to register: {}",
          modelDef.id(),
          e.getMessage()
        );
      }
    }

    // ── Client-local models (pure-Java engines — regex / composite) ──
    io.gravitee.singularitee.engine.remote.ClientLocalModelRegistrar.register(
      ws.clientLocalModels(),
      modelService.modelRegistry(),
      modelService::registerPrebuiltModel
    );

    // ── Pipelines ─────────────────────────────────────────────────────────
    for (var pipeline : ws.pipelines()) {
      try {
        String resolvedId = pipelineRegistry.register(pipeline);
        LOGGER.info(
          "Workspace pipeline registered: id={}, name={}",
          resolvedId,
          pipeline.getPipelineName()
        );
      } catch (Exception e) {
        LOGGER.warn(
          "Workspace pipeline '{}' failed to register: {}",
          pipeline.getPipelineName(),
          e.getMessage()
        );
      }
    }

    // ── Wire remote sub-pipeline callbacks ─────────────────────────────────
    var remoteCallbacks = buildRemotePipelineCallbacks(remoteClients);
    stepExecutorFactory.setSubPipelineCallbacks(pipelineExecutor, remoteCallbacks);

    // ── Warm up KNN reference embeddings ──────────────────────────────────
    for (var pipeline : ws.pipelines()) {
      try {
        stepExecutorFactory.rxWarmupEmbeddings(pipeline).blockingAwait();
      } catch (Exception e) {
        LOGGER.warn(
          "KNN embedding warmup failed for pipeline '{}': {}",
          pipeline.getPipelineId(),
          e.getMessage()
        );
      }
    }

    LOGGER.info(
      "Workspace '{}' loaded: {} local model(s), {} remote model(s), {} pipeline(s), {} remote endpoint(s)",
      ws.name(),
      ws.models().size(),
      ws.remoteModels().size(),
      ws.pipelines().size(),
      remoteClients.size()
    );

    // Models + pipelines are loaded — the server is now ready to serve inference.
    readinessState.markReady();
  }

  /** Renders a fixed-width textual progress bar, e.g. {@code [████████░░░░] 2/3}. */
  private static String progressBar(int done, int total) {
    int width = 20;
    int filled = total <= 0 ? width : (int) Math.round(((double) done / total) * width);
    filled = Math.max(0, Math.min(width, filled));
    return "[" + "█".repeat(filled) + "░".repeat(width - filled) + "] " + done + "/" + total;
  }

  @Override
  protected void doStop() throws Exception {
    // Close all gRPC clients created for remote endpoints.
    // Model and engine cleanup is handled by ModelRegistry.shutdown() in the grpc module.
    for (var entry : remoteClients.entrySet()) {
      try {
        entry.getValue().close();
        LOGGER.info("Closed gRPC client for remote '{}'", entry.getKey());
      } catch (Exception e) {
        LOGGER.warn(
          "Error closing gRPC client for remote '{}': {}",
          entry.getKey(),
          e.getMessage()
        );
      }
    }
    remoteClients = Map.of();
  }

  // -----------------------------------------------------------------------
  // Remote model helpers
  // -----------------------------------------------------------------------

  private static Map<String, SingulariteeClient> buildRemoteClients(
    Map<String, WorkspaceDefinition.RemoteEndpoint> remotes,
    Configuration configuration
  ) {
    // Resolved once, and only when something actually needs it: the outbound key
    // material is per-deployment, but an endpoint opts in with `ssl: true`.
    boolean anySecured = remotes
      .values()
      .stream()
      .anyMatch(r -> r.effectiveSsl());
    var tls = anySecured ? GrpcClientSslConfig.from(configuration) : null;
    var clients = new HashMap<String, SingulariteeClient>();
    for (var entry : remotes.entrySet()) {
      var ep = entry.getValue();
      int keepAlive = ep.effectiveHttp2KeepAliveTimeout();
      LOGGER.info(
        "Creating gRPC client for remote '{}': {}:{} (http2_keep_alive_timeout={}s, transport={})",
        entry.getKey(),
        ep.host(),
        ep.port(),
        keepAlive == -1 ? "always-on" : keepAlive,
        ep.effectiveSsl() ? "tls" : "plaintext"
      );
      if (ep.hasCredentials() && !ep.effectiveSsl()) {
        LOGGER.warn(
          "Remote '{}' sends Basic credentials over plaintext — set ssl: true unless {} is loopback",
          entry.getKey(),
          ep.host()
        );
      }
      clients.put(
        entry.getKey(),
        new SingulariteeClient(
          ep.host(),
          ep.port(),
          keepAlive,
          ep.hasCredentials() ? ep.username() : null,
          ep.hasCredentials() ? ep.password() : null,
          ep.effectiveSsl(),
          // Certificates come from grpc.client.ssl.* — the workspace only says which
          // endpoints are secured, never where the key material lives.
          ep.effectiveSsl() ? tls : null
        )
      );
    }
    return clients;
  }

  private void registerRemoteModel(
    WorkspaceDefinition.ModelDefinition modelDef,
    Map<String, SingulariteeClient> clients
  ) {
    String serverId = (modelDef.server() != null && !modelDef.server().isBlank())
      ? modelDef.server()
      : "default";
    var client = clients.get(serverId);
    if (client == null) {
      throw new IllegalStateException(
        "Remote model '" +
          modelDef.id() +
          "' references server '" +
          serverId +
          "' which is not configured in the workspace remote block"
      );
    }

    // NOTE: we deliberately do NOT call client.getModel() synchronously here.
    //
    // Eagerly probing the remote at YAML-load time couples workspace startup
    // to the remote server's availability: if the remote is still booting or
    // briefly unreachable, the local process would skip every remote model
    // and silently leave pipelines referencing them broken until restart.
    //
    // Instead we register the engine unconditionally. The engine is lazy —
    // it only opens an RPC when a pipeline step actually invokes it (see
    // RemoteTextGenEngine#rxAddSequence etc.). Transient failures surface
    // at call time as a clean per-request error, and the very next pipeline
    // invocation retries against a (now-healthy) remote with no restart.
    //
    // Chat-template metadata (template/bos/eos) is fetched by the engine
    // itself via an async, self-healing GetModel probe — never hardcode it
    // to null here: a null template silently degrades every INFER step to
    // template-less prompts (no ChatML scaffolding, no enable_thinking).
    var modelType = io.gravitee.singularitee.workspace.ModelType.parse(modelDef.type());
    ModelEngine engine = switch (modelType) {
      case REMOTE_LLM -> new RemoteTextGenEngine(client, modelDef.id());
      case REMOTE_CLASSIFIER -> new RemoteClassifierEngine(client, modelDef.id());
      case REMOTE_EMBEDDING -> new RemoteEmbeddingEngine(client, modelDef.id());
      case REMOTE_RERANKER -> new RemoteRerankerEngine(client, modelDef.id());
      default -> throw new IllegalArgumentException("Not a remote model type: " + modelType);
    };

    modelService.registerPrebuiltModel(
      modelDef.id(),
      modelDef.name() != null ? modelDef.name() : modelDef.id(),
      engine,
      modelDef.task() != null ? modelDef.task() : "",
      modelDef.isVisible()
    );

    LOGGER.info(
      "Workspace remote model registered (lazy — no probe): id={}, type={}, server={}",
      modelDef.id(),
      modelDef.type(),
      serverId
    );
  }

  private static Map<
    String,
    SubPipelineStepExecutor.PipelineExecutorCallback
  > buildRemotePipelineCallbacks(Map<String, SingulariteeClient> clients) {
    var callbacks = new HashMap<String, SubPipelineStepExecutor.PipelineExecutorCallback>();
    for (var entry : clients.entrySet()) {
      callbacks.put(entry.getKey(), new RemotePipelineCallback(entry.getValue()));
    }
    return callbacks;
  }
}
