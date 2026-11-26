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
package io.gravitee.singularitee.standalone.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.gravitee.common.service.AbstractService;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.engine.api.pipeline.model.PipelineModel;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.pipeline.model.StepTypes;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.pipeline.executor.StepExecutorFactory;
import io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority;
import io.gravitee.singularitee.plugin.api.StepPlugins;
import io.gravitee.singularitee.standalone.vertx.ReadinessState;
import io.gravitee.singularitee.standalone.vertx.WorkspaceLoaderComponent;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls this node's workspace from the Gamma module instead of a local file.
 *
 * <p>Enabled by {@code ai.sync.enabled}. It must start before the workspace loader: it fetches
 * the group's Release, stores it in {@code ai.sync.cache-dir}, and points {@code
 * ai.workspace.path} at it. When the control plane is unreachable it falls back to the last
 * stored release and fails startup only if there is none.
 *
 * <p>While running it polls every {@code ai.sync.interval} seconds. A newer release that differs
 * from the running one only in its pipelines and templates ({@link ReleaseChanges}) is applied in
 * place once the workspace has loaded: the new pipeline set is loaded against the models already
 * served and replaces the old one all at once. A pipeline that fails to load is left out, as the
 * boot load leaves it out, and the heartbeat reports it until a release applies without one; with
 * {@code ai.workspace.strict} any such failure keeps the old set and the release is not applied.
 * Runs already started finish on the pipeline they began with. Engines cannot unload a model, so any other newer release is applied by storing it
 * and exiting; the supervisor (Kubernetes, Docker, systemd) restarts the node onto it. Set
 * {@code ai.sync.restart-on-change=false} to only store such a release.
 *
 * <p>Every {@code ai.sync.heartbeat-interval} seconds it also reports a {@link NodeStatus} to
 * Gamma (running revision, loaded models and engines, RAM, last sync error), which is
 * how the node registers and how Gamma shows download and load progress. A failed report is
 * logged and never affects serving.
 */
public class ReleaseSyncComponent extends AbstractService<ReleaseSyncComponent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseSyncComponent.class);
  private static final ObjectMapper ANSWER_JSON = new ObjectMapper();
  private static final ObjectMapper RELEASE_YAML = new ObjectMapper(new YAMLFactory());

  private final TraceKeyAuthority traceKeys;
  private final StepPlugins stepPlugins;
  static final int DEFAULT_HEARTBEAT_SECONDS = 10;
  /**
   * Where the synced workspace is, handed to the workspace loader. A dedicated key rather than {@code ai.workspace.path}:
   * that one can also come from an environment variable (which {@code run-server.sh} always sets), and which source wins is
   * the configuration's business. The loader reads this first, so a node that syncs never serves a local file.
   */
  public static final String SYNCED_WORKSPACE_PROPERTY = "singularitee.synced-workspace";

  private final Configuration configuration;
  private final ModelRegistry modelRegistry;
  private final PipelineRegistry pipelineRegistry;
  private final ReadinessState readiness;
  private final StepExecutorFactory stepExecutorFactory;
  private int heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS;
  private ReleaseSyncClient client;
  private String instanceId = "";
  private volatile long runningRevision;
  private volatile HardwareInfo hardware;
  private final AtomicBoolean hardwareProbeStarted = new AtomicBoolean();
  private Supplier<HardwareInfo> hardwareProbe = HardwareDetector::detect;
  private volatile SyncFailure syncFailure;
  private volatile String workspaceError;
  private volatile String skippedPipelines;
  private volatile String runningYaml;
  private Path workspaceDir;
  private ScheduledExecutorService scheduler;
  private long appliedRevision;
  private boolean restartOnChange;
  /** Exit status meaning "restart me onto the new release" (EX_TEMPFAIL); {@code run-server.sh} loops on it. */
  public static final int RESTART_EXIT_CODE = 75;

  private Runnable restart = () -> System.exit(RESTART_EXIT_CODE);

  public ReleaseSyncComponent(
    Configuration configuration,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    ReadinessState readiness
  ) {
    this(configuration, modelRegistry, pipelineRegistry, readiness, new TraceKeyAuthority());
  }

  /** @param traceKeys receives the trace key hash Gamma announces in its heartbeat answers */
  public ReleaseSyncComponent(
    Configuration configuration,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    ReadinessState readiness,
    TraceKeyAuthority traceKeys
  ) {
    this(configuration, modelRegistry, pipelineRegistry, readiness, traceKeys, null);
  }

  /** @param stepPlugins the installed step plugins whose types and config schemas the heartbeat reports; null reports none */
  public ReleaseSyncComponent(
    Configuration configuration,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    ReadinessState readiness,
    TraceKeyAuthority traceKeys,
    StepPlugins stepPlugins
  ) {
    this(configuration, modelRegistry, pipelineRegistry, readiness, traceKeys, stepPlugins, null);
  }

  /** @param stepExecutorFactory warms up the pipelines a release applies in place; null skips it */
  public ReleaseSyncComponent(
    Configuration configuration,
    ModelRegistry modelRegistry,
    PipelineRegistry pipelineRegistry,
    ReadinessState readiness,
    TraceKeyAuthority traceKeys,
    StepPlugins stepPlugins,
    StepExecutorFactory stepExecutorFactory
  ) {
    this.stepExecutorFactory = stepExecutorFactory;
    this.stepPlugins = stepPlugins;
    this.traceKeys = traceKeys;
    this.configuration = configuration;
    this.modelRegistry = modelRegistry;
    this.pipelineRegistry = pipelineRegistry;
    this.readiness = readiness;
  }

  @Override
  protected void doStart() throws Exception {
    if (!configuration.getProperty("ai.sync.enabled", Boolean.class, false)) {
      return;
    }
    String url = required("ai.sync.url");
    String token = required("ai.sync.token");
    Path cacheDir = Path.of(
      configuration.getProperty(
        "ai.sync.cache-dir",
        System.getProperty("gravitee.home", ".") + "/sync"
      )
    );
    long intervalSeconds = configuration.getProperty("ai.sync.interval", Long.class, 30L);
    heartbeatSeconds = (int) Math.max(
      1L,
      configuration.getProperty(
        "ai.sync.heartbeat-interval",
        Long.class,
        (long) DEFAULT_HEARTBEAT_SECONDS
      )
    );
    restartOnChange = configuration.getProperty("ai.sync.restart-on-change", Boolean.class, true);

    client = new ReleaseSyncClient(url, token, cacheDir, Duration.ofSeconds(10));
    appliedRevision = client.cachedRevision();

    instanceId = resolveInstanceId(cacheDir);
    startHardwareProbe();
    Path workspace = bootSync();
    runningRevision = client.cachedRevision();
    runningYaml = Files.readString(workspace);
    workspaceDir = workspace.getParent();
    String local = configuration.getProperty("ai.workspace.path");
    if (local != null && !local.isBlank()) {
      LOGGER.warn(
        "ai.workspace.path ({}) is ignored: with ai.sync.enabled the workspace comes from the control plane",
        local
      );
    }
    System.setProperty(SYNCED_WORKSPACE_PROPERTY, workspace.toString());
    LOGGER.info("Workspace synced from {} (revision {})", url, runningRevision);

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "release-sync");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(
      this::poll,
      intervalSeconds,
      intervalSeconds,
      TimeUnit.SECONDS
    );
    scheduler.scheduleWithFixedDelay(this::heartbeat, 0, heartbeatSeconds, TimeUnit.SECONDS);
  }

  /** Instance id: {@code ai.sync.instance-id}, else a UUID kept next to the cached release. */
  private String resolveInstanceId(Path cacheDir) throws IOException {
    String configured = configuration.getProperty("ai.sync.instance-id");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    Path file = cacheDir.resolve("instance.id");
    if (Files.isRegularFile(file)) {
      String stored = Files.readString(file).trim();
      if (!stored.isEmpty()) {
        return stored;
      }
    }
    Files.createDirectories(cacheDir);
    String generated = UUID.randomUUID().toString();
    Files.writeString(file, generated);
    return generated;
  }

  /** Builds the status snapshot reported to Gamma. */
  NodeStatus snapshot() {
    var entries = modelRegistry.entries();
    List<NodeStatus.ModelState> models = entries
      .stream()
      .map(e ->
        new NodeStatus.ModelState(
          e.getKey(),
          "READY",
          null,
          null,
          String.valueOf(e.getValue().engine().type()),
          e.getValue().task()
        )
      )
      .sorted(java.util.Comparator.comparing(NodeStatus.ModelState::id))
      .toList();
    List<String> engines = entries
      .stream()
      .map(e -> String.valueOf(e.getValue().engine().type()))
      .distinct()
      .sorted()
      .toList();
    HardwareInfo hw = hardware();
    Package pkg = ReleaseSyncComponent.class.getPackage();
    String version = pkg != null && pkg.getImplementationVersion() != null
      ? pkg.getImplementationVersion()
      : "unknown";
    Long ramTotalMb = null;
    Long ramFreeMb = null;
    if (
      ManagementFactory.getOperatingSystemMXBean() instanceof
        com.sun.management.OperatingSystemMXBean os
    ) {
      ramTotalMb = os.getTotalMemorySize() / (1024 * 1024);
      ramFreeMb = os.getFreeMemorySize() / (1024 * 1024);
    }
    return new NodeStatus(
      hostname(),
      version,
      engines,
      gpuSummary(hw),
      gpuMemoryMb(hw),
      ramTotalMb,
      ramFreeMb,
      runningRevision,
      models,
      reportedError(),
      hw,
      readiness.isReady(),
      ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
      heartbeatSeconds,
      pipelines(),
      steps()
    );
  }

  /** The node's own state wins over a sync failure: a failed release stays reported until it is resolved. */
  private String reportedError() {
    String workspace = workspaceError;
    if (workspace != null) {
      return workspace;
    }
    String skipped = skippedPipelines;
    if (skipped != null) {
      return skipped;
    }
    SyncFailure failure = syncFailure;
    return failure == null ? null : failure.message();
  }

  /** What went wrong talking to the control plane; {@code unreachable} when it never answered. */
  private record SyncFailure(String message, boolean unreachable) {}

  private List<NodeStatus.StepTypeState> steps() {
    if (stepPlugins == null) {
      return List.of();
    }
    return stepPlugins
      .executors()
      .keySet()
      .stream()
      .map(type ->
        new NodeStatus.StepTypeState(
          type,
          StepTypes.CORE.contains(type),
          stepPlugins.configSchemas().get(type)
        )
      )
      .sorted(java.util.Comparator.comparing(NodeStatus.StepTypeState::type))
      .toList();
  }

  private List<NodeStatus.PipelineState> pipelines() {
    return pipelineRegistry
      .entries()
      .stream()
      .map(e ->
        new NodeStatus.PipelineState(
          e.getKey(),
          e.getValue().pipeline().name(),
          e.getValue().pipeline().task(),
          String.valueOf(e.getValue().status()),
          e.getValue().pipeline().steps().size()
        )
      )
      .sorted(java.util.Comparator.comparing(NodeStatus.PipelineState::id))
      .toList();
  }

  /**
   * Detected once, on a thread of its own: the hardware does not change under a running node, and
   * detection forks processes that can hang (a wedged GPU driver). Null until the probe answers.
   */
  private HardwareInfo hardware() {
    HardwareInfo hw = hardware;
    if (hw == null) {
      startHardwareProbe();
    }
    return hw;
  }

  private void startHardwareProbe() {
    if (hardware != null || !hardwareProbeStarted.compareAndSet(false, true)) {
      return;
    }
    Thread probe = new Thread(
      () -> {
        try {
          hardware = hardwareProbe.get();
        } catch (RuntimeException e) {
          LOGGER.warn("Hardware detection failed: {}", e.toString());
        }
      },
      "hardware-probe"
    );
    probe.setDaemon(true);
    probe.start();
  }

  private static String gpuSummary(HardwareInfo hw) {
    if (hw == null || hw.gpus().isEmpty()) {
      return null;
    }
    String first = hw.gpus().get(0).name();
    return hw.gpus().size() > 1 ? hw.gpus().size() + "x " + first : first;
  }

  /** Dedicated GPU memory summed over the GPUs; unified memory (Apple Silicon) is the host RAM, so null. */
  private static Long gpuMemoryMb(HardwareInfo hw) {
    if (hw == null) {
      return null;
    }
    long total = hw
      .gpus()
      .stream()
      .filter(g -> g.memoryMb() != null)
      .mapToLong(HardwareInfo.Gpu::memoryMb)
      .sum();
    return total > 0 ? total : null;
  }

  void setHardwareProbeForTest(Supplier<HardwareInfo> probe) {
    this.hardwareProbe = probe;
  }

  /** Test hook: pins the hardware the status reports. */
  void setHardwareForTest(HardwareInfo hardware) {
    this.hardware = hardware;
  }

  private static String hostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.io.IOException e) {
      return null;
    }
  }

  /** One heartbeat; never throws so the schedule keeps running. */
  void heartbeat() {
    try {
      var answer = client.reportStatus(instanceId, snapshot());
      SyncFailure failure = syncFailure;
      if (failure != null && failure.unreachable()) {
        syncFailure = null;
      }
      answer.ifPresent(this::readAnswer);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOGGER.warn("Heartbeat to Gamma failed: {}", e.getMessage());
    }
  }

  private void readAnswer(String body) {
    try {
      JsonNode hash = ANSWER_JSON.readTree(body).get("traceKeySha256");
      if (hash != null && hash.isTextual()) {
        traceKeys.expect(hash.asText());
      }
    } catch (IOException e) {
      LOGGER.warn("Unreadable heartbeat answer from Gamma: {}", e.getMessage());
    }
  }

  /** Fetches the release at boot, falling back to the cached one. */
  private Path bootSync() throws IOException, InterruptedException {
    String failure = null;
    try {
      var release = client.fetch(appliedRevision);
      if (release.isPresent()) {
        appliedRevision = release.get().revision();
        return client.store(release.get());
      }
    } catch (IOException e) {
      failure = e.getMessage();
      LOGGER.warn("Release sync failed at boot ({}); trying the cached release", failure);
    }
    String reason = failure;
    return client
      .cachedWorkspace()
      .orElseThrow(() ->
        new IllegalStateException(
          reason != null
            ? "No release available: sync failed (" + reason + ") and nothing is cached"
            : "No release available: the server group has no published release yet and nothing is cached. Publish a release in Gamma."
        )
      );
  }

  /** One poll cycle; never throws so the schedule keeps running. */
  void poll() {
    ReleaseSyncClient.Release release;
    try {
      var fetched = client.fetch(appliedRevision);
      syncFailure = null;
      if (fetched.isEmpty()) {
        workspaceError = null;
        return;
      }
      release = fetched.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      syncFailure = new SyncFailure(
        describe(e),
        !(e instanceof ReleaseSyncClient.UnexpectedAnswerException)
      );
      LOGGER.warn("Release sync poll failed: {}", syncFailure.message());
      return;
    }
    try {
      if (ReleaseChanges.pipelinesOrTemplatesOnly(runningYaml, release.yaml())) {
        if (applyInPlace(release)) {
          workspaceError = null;
        }
        return;
      }
      client.store(release);
      workspaceError = null;
      LOGGER.info("Release {} received (was {})", release.revision(), appliedRevision);
      appliedRevision = release.revision();
      if (restartOnChange) {
        LOGGER.info("Exiting so the supervisor restarts the node onto the new release");
        restart.run();
      }
    } catch (Exception e) {
      workspaceError = describe(e);
      LOGGER.warn("Release sync poll failed: {}", workspaceError);
    }
  }

  /**
   * Replaces the served pipelines with the release's. Waits for the workspace to load first, so the
   * boot registration and this one never race; the release is fetched again on the next poll. A
   * pipeline that fails to load is left out unless {@code ai.workspace.strict}, where it fails the
   * set. A failure keeps the running pipelines and revision and leaves the cache as it was.
   */
  private boolean applyInPlace(ReleaseSyncClient.Release release) throws IOException {
    if (!readiness.isReady()) {
      LOGGER.info(
        "Release {} changes only pipelines or templates; it is applied once the workspace has loaded",
        release.revision()
      );
      return false;
    }
    boolean strict = configuration.getProperty("ai.workspace.strict", Boolean.class, false);
    PipelineRegistry.Replacement replacement;
    Map<String, String> skipped = new LinkedHashMap<>();
    try {
      List<PipelineModel> pipelines = loadPipelines(release.yaml(), strict, skipped);
      replacement = strict
        ? pipelineRegistry.prepareReplacement(pipelines)
        : pipelineRegistry.prepareLenientReplacement(pipelines);
    } catch (RuntimeException | IOException e) {
      throw new IllegalStateException(
        "Release " + release.revision() + " not applied: " + describe(e),
        e
      );
    }
    replacement.skipped().forEach(skipped::putIfAbsent);
    client.store(release);
    List<String> stale = changedOrRemoved(replacement);
    pipelineRegistry.replaceAll(replacement);
    runningYaml = release.yaml();
    LOGGER.info(
      "Release {} applied in place (was {}): {} pipeline(s)",
      release.revision(),
      runningRevision,
      replacement.ids().size()
    );
    appliedRevision = release.revision();
    runningRevision = release.revision();
    skippedPipelines = skipped.isEmpty() ? null : describeSkipped(release.revision(), skipped);
    if (!skipped.isEmpty()) {
      LOGGER.warn(skippedPipelines);
    }
    if (stepExecutorFactory != null) {
      stepExecutorFactory.forgetPipelines(stale);
    }
    warmUp(replacement);
    return true;
  }

  private static String describeSkipped(long revision, Map<String, String> skipped) {
    return (
      "Release " +
      revision +
      " applied without " +
      skipped.size() +
      " pipeline(s) that failed to load: " +
      skipped
        .entrySet()
        .stream()
        .map(e -> e.getKey() + " (" + e.getValue() + ")")
        .collect(Collectors.joining("; "))
    );
  }

  /** Ids of the served pipelines that the replacement drops or defines differently. */
  private List<String> changedOrRemoved(PipelineRegistry.Replacement replacement) {
    return pipelineRegistry
      .list()
      .stream()
      .filter(served -> {
        var next = replacement.entries().get(served.pipeline().id());
        return next == null || !next.pipeline().equals(served.pipeline());
      })
      .map(served -> served.pipeline().id())
      .toList();
  }

  /**
   * The release's pipelines, loaded as the workspace loader loads them. A pipeline the loader skips
   * fails the set when {@code strict}, and is added to {@code skipped} otherwise.
   */
  private List<PipelineModel> loadPipelines(
    String yaml,
    boolean strict,
    Map<String, String> skipped
  ) throws IOException {
    var codecs = stepPlugins == null ? Map.<String, StepConfigCodec<?>>of() : stepPlugins.codecs();
    List<PipelineModel> pipelines = YamlWorkspaceLoader.loadFromString(
      yaml,
      workspaceDir,
      WorkspaceLoaderComponent.templatesPath(),
      codecs,
      WorkspaceLoaderComponent.remoteEnvAllowlist(configuration)
    ).pipelines();
    List<String> declared = declaredPipelineIds(yaml);
    if (pipelines.size() < declared.size()) {
      Set<String> loaded = pipelines.stream().map(PipelineModel::id).collect(Collectors.toSet());
      List<String> notLoaded = declared
        .stream()
        .filter(id -> !loaded.contains(id))
        .toList();
      if (strict) {
        throw new IllegalStateException(
          "pipeline(s) " + notLoaded + " failed to load; the node log has the cause"
        );
      }
      notLoaded.forEach(id -> skipped.put(id, "failed to load; the node log has the cause"));
    }
    return pipelines;
  }

  private static List<String> declaredPipelineIds(String yaml) throws IOException {
    List<String> ids = new ArrayList<>();
    for (JsonNode pipeline : RELEASE_YAML.readTree(yaml).path("workspace").path("pipelines")) {
      ids.add(pipeline.path("id").asText(""));
    }
    return ids;
  }

  private void warmUp(PipelineRegistry.Replacement replacement) {
    if (stepExecutorFactory == null) {
      return;
    }
    for (var entry : replacement.entries().values()) {
      var pipeline = entry.pipeline();
      stepExecutorFactory
        .rxWarmupEmbeddings(pipeline)
        .subscribe(
          () -> {},
          e ->
            LOGGER.warn(
              "KNN embedding warmup failed for pipeline '{}': {}",
              pipeline.id(),
              e.getMessage()
            )
        );
    }
  }

  @Override
  protected void doStop() throws Exception {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** Connection errors often carry no message; fall back to the exception type. */
  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private String required(String key) {
    String value = configuration.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("ai.sync.enabled is true but " + key + " is not set");
    }
    return value;
  }

  void setClientForTest(
    ReleaseSyncClient client,
    long appliedRevision,
    String runningYaml,
    boolean restartOnChange,
    Runnable restart
  ) {
    setClientForTest(client, appliedRevision, restartOnChange, restart);
    this.runningYaml = runningYaml;
  }

  void setClientForTest(
    ReleaseSyncClient client,
    long appliedRevision,
    boolean restartOnChange,
    Runnable restart
  ) {
    this.client = client;
    this.appliedRevision = appliedRevision;
    this.runningRevision = appliedRevision;
    this.restartOnChange = restartOnChange;
    this.restart = restart;
    this.instanceId = "test-instance";
  }
}
