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
import io.gravitee.singularitee.engine.ModelEngine;
import io.gravitee.singularitee.engine.ModelEnginePerformance;
import io.gravitee.singularitee.engine.ModelEngineToken;
import io.gravitee.singularitee.pipeline.PipelineExecutor;
import io.gravitee.singularitee.pipeline.executor.JinjaRenderer;
import io.gravitee.singularitee.pipeline.executor.StepExecutorFactory;
import io.gravitee.singularitee.pipeline.executor.SubPipelineStepExecutor;
import io.gravitee.singularitee.protocol.*;
import io.gravitee.singularitee.registry.ModelRegistry;
import io.gravitee.singularitee.registry.PipelineRegistry;
import io.gravitee.singularitee.workspace.ModelType;
import io.gravitee.singularitee.workspace.WorkspaceDefinition;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import io.vertx.core.streams.WriteStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires a {@link PipelineExecutor} for client-side execution with remote models.
 *
 * <p>Usage:
 * <pre>{@code
 *   var result = ClientPipelineExecutor.create(Path.of("workspace.yaml"));
 *   result.executor().execute(request, response, callerContext);
 *   result.close(); // closes all gRPC clients
 * }</pre>
 *
 * @author Rémi SULTAN (remi.sultan at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ClientPipelineExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientPipelineExecutor.class);

  private ClientPipelineExecutor() {}

  /**
   * Result of building a client-side pipeline executor.
   *
   * @param executor    the ready-to-use pipeline executor
   * @param clients     the gRPC clients created (caller must close on shutdown)
   * @param pipelineIds the pipeline IDs available in the workspace
   * @param modelRegistry the model registry (for direct model access outside pipelines)
   */
  public record Result(
    PipelineExecutor executor,
    Map<String, SingulariteeClient> clients,
    java.util.List<String> pipelineIds,
    ModelRegistry modelRegistry
  ) implements AutoCloseable {
    @Override
    public void close() {
      clients
        .values()
        .forEach(c -> {
          try {
            c.close();
          } catch (Exception e) {
            LOGGER.warn("Error closing client: {}", e.getMessage());
          }
        });
    }
  }

  /**
   * Creates a {@link PipelineExecutor} wired for client-side execution.
   *
   * @param workspaceYaml path to the workspace YAML file
   * @return a result containing the executor and all gRPC clients
   * @throws IOException           if the YAML file cannot be read
   * @throws IllegalStateException if a remote model does not exist on its server
   */
  public static Result create(Path workspaceYaml) throws IOException {
    return create(YamlWorkspaceLoader.load(workspaceYaml), null);
  }

  /**
   * Creates a {@link PipelineExecutor} from an inline YAML string.
   *
   * <p>Use this when the workspace is embedded directly in the endpoint configuration
   * rather than stored on disk. The YAML must include the full {@code remote:} section
   * with host/port coordinates for each referenced server.
   *
   * @param workspaceYaml the full workspace YAML content as a string
   * @return a result containing the executor and all gRPC clients
   * @throws IOException           if the YAML cannot be parsed
   * @throws IllegalStateException if a remote model does not exist on its server
   */
  public static Result createFromString(String workspaceYaml) throws IOException {
    return create(YamlWorkspaceLoader.loadFromString(workspaceYaml), null);
  }

  /**
   * Creates a {@link PipelineExecutor} from an inline YAML string,
   * using the provided Vert.x instance for gRPC clients.
   *
   * <p>When running inside a Gravitee gateway plugin, the gateway's
   * Vert.x event loop must be used — creating a standalone {@code Vertx.vertx()}
   * inside a parent-first classloader environment causes event-loop
   * isolation issues where gRPC response handlers never fire.
   *
   * @param workspaceYaml the full workspace YAML content as a string
   * @param vertx         the Vert.x instance to use (or {@code null} for a standalone one)
   * @return a result containing the executor and all gRPC clients
   * @throws IOException           if the YAML cannot be parsed
   * @throws IllegalStateException if a remote model does not exist on its server
   */
  public static Result createFromString(String workspaceYaml, io.vertx.core.Vertx vertx)
    throws IOException {
    return createFromString(workspaceYaml, vertx, null);
  }

  /**
   * Creates a {@link PipelineExecutor} from an inline YAML string,
   * using the provided Vert.x instance for gRPC clients and the given
   * directory for {@code template_file} resolution.
   *
   * @param workspaceYaml     the full workspace YAML content as a string
   * @param vertx             the Vert.x instance to use (or {@code null} for a standalone one)
   * @param templatesPath     base directory for {@code template_file} resolution;
   *                          when {@code null}, template files are not resolved
   * @return a result containing the executor and all gRPC clients
   * @throws IOException           if the YAML cannot be parsed
   * @throws IllegalStateException if a remote model does not exist on its server
   */
  public static Result createFromString(
    String workspaceYaml,
    io.vertx.core.Vertx vertx,
    Path templatesPath
  ) throws IOException {
    return create(YamlWorkspaceLoader.loadFromString(workspaceYaml, null, templatesPath), vertx);
  }

  /**
   * Creates a {@link PipelineExecutor} from a pre-built {@link YamlWorkspaceLoader.WorkspaceRequests}.
   *
   * <p>Use this when the workspace has already been assembled programmatically (e.g. by the
   * APIM gateway plugin's {@code WorkspaceAssembler}) and YAML parsing has been bypassed entirely.
   *
   * @param ws    the pre-built workspace requests
   * @param vertx the Vert.x instance to use (or {@code null} for a standalone one)
   * @return a result containing the executor and all gRPC clients
   * @throws IllegalStateException if a remote model does not exist on its server
   */
  public static Result create(YamlWorkspaceLoader.WorkspaceRequests ws, io.vertx.core.Vertx vertx) {
    LOGGER.info(
      "Loaded workspace '{}': {} local model(s), {} remote model(s), {} pipeline(s), {} remote endpoint(s)",
      ws.name(),
      ws.models().size(),
      ws.remoteModels().size(),
      ws.pipelines().size(),
      ws.remotes().size()
    );

    // 2. Build one SingulariteeClient per declared remote endpoint
    var clients = buildClients(ws.remotes(), vertx);

    // 3. Build registries
    var streamRegistry = new LocalStreamRegistry();
    var modelRegistry = new ModelRegistry();

    // 4. Register remote models
    for (var modelDef : ws.remoteModels()) {
      registerRemoteModel(modelDef, clients, ws.remotes(), streamRegistry, modelRegistry);
    }

    // 4b. Register client-local models (regex, composite — pure-Java engines)
    ClientLocalModelRegistrar.register(
      ws.clientLocalModels(),
      modelRegistry,
      (id, name, engine, task, visible, modalities) ->
        modelRegistry.register(id, name, engine, token -> {}, task, visible, modalities)
    );

    // 5. Build PipelineRegistry from local YAML
    var pipelineRegistry = new PipelineRegistry(modelRegistry);
    for (var pipeline : ws.pipelines()) {
      String id = pipelineRegistry.register(pipeline);
      LOGGER.info("Registered pipeline: {}", id);
    }

    // 6. Build remote pipeline callbacks
    var remoteCallbacks = buildRemoteCallbacks(clients);

    // 7. Wire step executor factory
    var factory = new StepExecutorFactory(
      modelRegistry,
      pipelineRegistry,
      streamRegistry,
      new JinjaRenderer(),
      null
    );

    var dispatcher = factory.createDispatcher();
    var executor = new PipelineExecutor(pipelineRegistry, dispatcher);

    // 8. Inject callbacks — local + remote
    // PipelineExecutor now implements PipelineExecutorCallback directly
    factory.setSubPipelineCallbacks(executor, remoteCallbacks);

    // 9. Warm up KNN reference embeddings (blockingAwait safe here — off event loop)
    for (var pipeline : ws.pipelines()) {
      try {
        factory.rxWarmupEmbeddings(pipeline).blockingAwait();
      } catch (Exception e) {
        LOGGER.warn(
          "KNN embedding warmup failed for pipeline '{}': {}",
          pipeline.getPipelineId(),
          e.getMessage()
        );
      }
    }

    LOGGER.info(
      "Client-side pipeline executor ready ({} pipelines, {} remote models, {} remote endpoints)",
      ws.pipelines().size(),
      ws.remoteModels().size(),
      clients.size()
    );

    var pipelineIds = ws.pipelines().stream().map(Pipeline::getPipelineId).toList();

    return new Result(executor, clients, pipelineIds, modelRegistry);
  }

  // -----------------------------------------------------------------------
  // Client building
  // -----------------------------------------------------------------------

  private static Map<String, SingulariteeClient> buildClients(
    Map<String, WorkspaceDefinition.RemoteEndpoint> remotes,
    io.vertx.core.Vertx vertx
  ) {
    var clients = new HashMap<String, SingulariteeClient>();
    for (var entry : remotes.entrySet()) {
      var ep = entry.getValue();
      LOGGER.info(
        "Creating gRPC client for remote '{}': {}:{} (vertx={}, auth={}, transport={})",
        entry.getKey(),
        ep.host(),
        ep.port(),
        vertx != null ? "provided" : "standalone",
        ep.hasCredentials() ? "basic" : "none",
        ep.effectiveSsl() ? "tls" : "plaintext"
      );
      if (ep.hasCredentials() && !ep.effectiveSsl()) {
        LOGGER.warn(
          "Remote '{}' sends Basic credentials over plaintext — set ssl: true unless {} is loopback",
          entry.getKey(),
          ep.host()
        );
      }
      String user = ep.hasCredentials() ? ep.username() : null;
      String pass = ep.hasCredentials() ? ep.password() : null;
      SingulariteeClient client = vertx != null
        ? new SingulariteeClient(
          vertx,
          ep.host(),
          ep.port(),
          ep.effectiveHttp2KeepAliveTimeout(),
          user,
          pass,
          ep.effectiveSsl()
        )
        : new SingulariteeClient(
          ep.host(),
          ep.port(),
          ep.effectiveHttp2KeepAliveTimeout(),
          user,
          pass,
          ep.effectiveSsl()
        );
      clients.put(entry.getKey(), client);
    }
    return clients;
  }

  // -----------------------------------------------------------------------
  // Remote model registration
  // -----------------------------------------------------------------------

  private static void registerRemoteModel(
    WorkspaceDefinition.ModelDefinition modelDef,
    Map<String, SingulariteeClient> clients,
    Map<String, WorkspaceDefinition.RemoteEndpoint> remotes,
    LocalStreamRegistry streamRegistry,
    ModelRegistry modelRegistry
  ) {
    // Resolve which client to use
    String serverId = resolveServerId(modelDef.server(), remotes);
    var client = clients.get(serverId);
    if (client == null) {
      throw new IllegalStateException(
        "Remote model '" +
          modelDef.id() +
          "' references server '" +
          serverId +
          "' which is not configured"
      );
    }

    // Validate model exists on the remote server and fetch metadata
    GetModelResponse modelInfo;
    try {
      modelInfo = client.getModel(modelDef.id()).blockingGet();
    } catch (Exception e) {
      throw new IllegalStateException(
        "Remote model '" +
          modelDef.id() +
          "' not found on server '" +
          serverId +
          "': " +
          e.getMessage()
      );
    }

    // Create the appropriate remote engine based on YAML type
    var modelType = ModelType.parse(modelDef.type());
    ModelEngine engine = createRemoteEngine(client, modelDef.id(), modelType, modelInfo);

    // Wire the token dispatcher so InferStepExecutor can capture tokens
    Consumer<ModelEngineToken> tokenDispatcher = buildTokenDispatcher(
      streamRegistry,
      modelDef.id()
    );

    modelRegistry.register(
      modelDef.id(),
      modelDef.name() != null ? modelDef.name() : modelDef.id(),
      engine,
      tokenDispatcher,
      modelDef.task() != null ? modelDef.task() : "",
      modelDef.isVisible(),
      modelDef.modalities() != null ? modelDef.modalities() : List.of()
    );

    LOGGER.info(
      "Registered remote model: id='{}', type='{}', server='{}'",
      modelDef.id(),
      modelDef.type(),
      serverId
    );
  }

  private static String resolveServerId(
    String explicitServer,
    Map<String, WorkspaceDefinition.RemoteEndpoint> remotes
  ) {
    if (explicitServer != null && !explicitServer.isBlank()) {
      return explicitServer;
    }
    if (remotes.containsKey("default")) {
      return "default";
    }
    throw new IllegalStateException(
      "Remote model has no 'server' field and no default remote endpoint is configured"
    );
  }

  private static ModelEngine createRemoteEngine(
    SingulariteeClient client,
    String modelId,
    ModelType type,
    GetModelResponse modelInfo
  ) {
    return switch (type) {
      case REMOTE_LLM -> new RemoteTextGenEngine(
        client,
        modelId,
        emptyToNull(modelInfo.getChatTemplate()),
        emptyToNull(modelInfo.getBosToken()),
        emptyToNull(modelInfo.getEosToken()),
        modelInfo.getInputModalitiesList()
      );
      case REMOTE_CLASSIFIER -> new RemoteClassifierEngine(client, modelId, modelInfo.getTask());
      case REMOTE_EMBEDDING -> new RemoteEmbeddingEngine(client, modelId);
      case REMOTE_RERANKER -> new RemoteRerankerEngine(client, modelId);
      default -> throw new IllegalArgumentException(
        "Not a remote model type: " + type + " for model " + modelId
      );
    };
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  // -----------------------------------------------------------------------
  // Token dispatch (fixes the deadlock bug with remote engines)
  // -----------------------------------------------------------------------

  /**
   * Creates a token dispatcher that routes tokens from a RemoteTextGenEngine
   * to any capture streams registered in the stream registry by InferStepExecutor.
   *
   * <p>This is the critical fix: without this, the TokenCaptureStream registered
   * by InferStepExecutor would never receive tokens, and latch.await() would hang.
   */
  private static Consumer<ModelEngineToken> buildTokenDispatcher(
    LocalStreamRegistry streamRegistry,
    String modelId
  ) {
    return token -> {
      var activeStreams = streamRegistry.streamsForModel(modelId);
      var ctx = activeStreams.get(token.seqId());
      if (ctx == null) return;

      var stream = ctx.stream();
      if (token.isFinal()) {
        // Emit COMPLETED event.
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
        // Emit OUTPUT_TEXT_DELTA event.
        var delta = InferResponse.newBuilder()
          .setEventType(ResponseEventType.RESPONSE_EVENT_TYPE_OUTPUT_TEXT_DELTA)
          .setResponseOutputTextDelta(
            ResponseOutputTextDelta.newBuilder().setDelta(token.token()).build()
          )
          .build();
        stream.write(delta);
      }
    };
  }

  private static FinishReason toProtoFinishReason(String reason) {
    if (reason == null) return FinishReason.FINISH_REASON_STOP;
    try {
      return FinishReason.valueOf(reason);
    } catch (IllegalArgumentException e) {
      return FinishReason.FINISH_REASON_STOP;
    }
  }

  private static InferencePerformance toProtoPerformance(
    io.gravitee.singularitee.engine.ModelEnginePerformance p
  ) {
    return InferencePerformance.newBuilder()
      .setStartTimeMs(p.startTimeMs())
      .setLoadTimeMs(p.loadTimeMs())
      .setPromptEvalTimeMs(p.promptEvalTimeMs())
      .setEvalTimeMs(p.evalTimeMs())
      .setPromptTokensEvaluated(p.promptTokensEvaluated())
      .setTokensGenerated(p.tokensGenerated())
      .setTokensReused(p.tokensReused())
      .setSamplingTimeMs(p.samplingTimeMs())
      .setSampleCount(p.sampleCount())
      .build();
  }

  // -----------------------------------------------------------------------
  // Remote pipeline callbacks
  // -----------------------------------------------------------------------

  private static Map<String, SubPipelineStepExecutor.PipelineExecutorCallback> buildRemoteCallbacks(
    Map<String, SingulariteeClient> clients
  ) {
    var callbacks = new HashMap<String, SubPipelineStepExecutor.PipelineExecutorCallback>();
    for (var entry : clients.entrySet()) {
      callbacks.put(entry.getKey(), new RemotePipelineCallback(entry.getValue()));
    }
    return callbacks;
  }
}
