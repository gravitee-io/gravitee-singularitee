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

import io.gravitee.common.service.AbstractService;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.standalone.vertx.ReadinessState;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * <p>While running it polls every {@code ai.sync.interval} seconds. Engines cannot unload a
 * model, so a newer release is applied by storing it and exiting; the supervisor (Kubernetes,
 * Docker, systemd) restarts the node onto it. Set {@code ai.sync.restart-on-change=false} to
 * only store it.
 *
 * <p>Every {@code ai.sync.heartbeat-interval} seconds it also reports a {@link NodeStatus} to
 * Gamma (running revision, loaded models and engines, RAM, last sync error), which is
 * how the node registers and how Gamma shows download and load progress. A failed report is
 * logged and never affects serving.
 */
public class ReleaseSyncComponent extends AbstractService<ReleaseSyncComponent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseSyncComponent.class);
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
  private int heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS;
  private ReleaseSyncClient client;
  private String instanceId = "";
  private volatile long runningRevision;
  private volatile HardwareInfo hardware;
  private volatile String lastSyncError;
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
    Path workspace = bootSync();
    runningRevision = client.cachedRevision();
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
      lastSyncError,
      hw,
      readiness.isReady(),
      ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
      heartbeatSeconds,
      pipelines()
    );
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

  /** Detected once: the hardware does not change under a running node, and detection forks processes. */
  private HardwareInfo hardware() {
    HardwareInfo hw = hardware;
    if (hw == null) {
      hw = HardwareDetector.detect();
      hardware = hw;
    }
    return hw;
  }

  private static String gpuSummary(HardwareInfo hw) {
    if (hw.gpus().isEmpty()) {
      return null;
    }
    String first = hw.gpus().get(0).name();
    return hw.gpus().size() > 1 ? hw.gpus().size() + "x " + first : first;
  }

  /** Dedicated GPU memory summed over the GPUs; unified memory (Apple Silicon) is the host RAM, so null. */
  private static Long gpuMemoryMb(HardwareInfo hw) {
    long total = hw
      .gpus()
      .stream()
      .filter(g -> g.memoryMb() != null)
      .mapToLong(HardwareInfo.Gpu::memoryMb)
      .sum();
    return total > 0 ? total : null;
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
      client.reportStatus(instanceId, snapshot());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOGGER.warn("Heartbeat to Gamma failed: {}", e.getMessage());
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
    try {
      var release = client.fetch(appliedRevision);
      lastSyncError = null;
      if (release.isEmpty()) {
        return;
      }
      client.store(release.get());
      LOGGER.info("Release {} received (was {})", release.get().revision(), appliedRevision);
      appliedRevision = release.get().revision();
      if (restartOnChange) {
        LOGGER.info("Exiting so the supervisor restarts the node onto the new release");
        restart.run();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      lastSyncError = describe(e);
      LOGGER.warn("Release sync poll failed: {}", lastSyncError);
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
