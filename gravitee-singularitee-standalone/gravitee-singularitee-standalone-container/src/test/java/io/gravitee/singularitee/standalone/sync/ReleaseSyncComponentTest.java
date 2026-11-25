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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.standalone.vertx.ReadinessState;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseSyncComponentTest {

  private static final String YAML = "workspace:\n  name: demo\n";

  @TempDir
  Path cache;

  private HttpServer server;
  private volatile int revision = 1;
  private final AtomicReference<String> statusBody = new AtomicReference<>();
  private final AtomicReference<String> statusAuth = new AtomicReference<>();
  private volatile String statusAnswer;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/_sync/release", exchange -> {
      long known = Long.parseLong(exchange.getRequestURI().getQuery().substring("known=".length()));
      if (known >= revision) {
        exchange.sendResponseHeaders(304, -1);
      } else {
        byte[] body = ("{\"revision\":" +
          revision +
          ",\"checksum\":\"" +
          ReleaseSyncClient.sha256(YAML) +
          "\",\"yaml\":\"workspace:\\n  name: demo\\n\"}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    server.createContext("/_sync/instances/test-instance/status", exchange -> {
      statusBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      statusAuth.set(exchange.getRequestHeaders().getFirst("X-Singularitee-Token"));
      if (statusAnswer == null) {
        exchange.sendResponseHeaders(204, -1);
      } else {
        byte[] answer = statusAnswer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, answer.length);
        exchange.getResponseBody().write(answer);
      }
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    System.clearProperty(ReleaseSyncComponent.SYNCED_WORKSPACE_PROPERTY);
  }

  private ReleaseSyncComponent component(Configuration config) {
    return component(
      config,
      new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority()
    );
  }

  private ReleaseSyncComponent component(
    Configuration config,
    io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority authority
  ) {
    var models = new ModelRegistry();
    var component = new ReleaseSyncComponent(
      config,
      models,
      new PipelineRegistry(models),
      new ReadinessState(),
      authority
    );
    component.setHardwareForTest(
      new HardwareInfo(
        "Linux 6.8",
        new HardwareInfo.Cpu("amd64", "Test CPU", 8, 4, List.of("avx2")),
        List.of(new HardwareInfo.Gpu("NVIDIA", "RTX 4090", 24576L, "550", "8.9", "CUDA", null))
      )
    );
    return component;
  }

  private ReleaseSyncClient client() {
    return new ReleaseSyncClient(
      "http://127.0.0.1:" + server.getAddress().getPort(),
      "t",
      cache,
      Duration.ofSeconds(5)
    );
  }

  @Test
  void poll_stores_new_release_and_restarts_once() {
    var restarts = new AtomicInteger();
    var component = component(config(Map.of()));
    var client = client();
    component.setClientForTest(client, 1, true, restarts::incrementAndGet);

    component.poll();
    assertThat(restarts).hasValue(0);

    revision = 2;
    component.poll();
    component.poll();

    assertThat(restarts).hasValue(1);
    assertThat(client.cachedRevision()).isEqualTo(2);
  }

  @Test
  void poll_without_restart_only_stores() {
    var restarts = new AtomicInteger();
    var component = component(config(Map.of()));
    var client = client();
    revision = 3;
    component.setClientForTest(client, 1, false, restarts::incrementAndGet);

    component.poll();

    assertThat(restarts).hasValue(0);
    assertThat(client.cachedRevision()).isEqualTo(3);
  }

  @Test
  void heartbeat_reports_status_with_running_revision() throws Exception {
    var component = component(config(Map.of()));
    component.setClientForTest(client(), 4, false, () -> {});

    component.heartbeat();

    assertThat(statusAuth.get()).isEqualTo("t");
    assertThat(statusBody.get())
      .contains("\"appliedRevision\":4")
      .contains("\"gpuName\":\"RTX 4090\"")
      .contains("\"gpuMemoryMb\":24576")
      .contains("\"arch\":\"amd64\"")
      .contains("\"computeCapability\":\"8.9\"")
      .contains("\"models\":[]")
      .contains("\"pipelines\":[]")
      .contains("\"ready\":false")
      .contains("\"heartbeatSeconds\":10")
      .contains("\"lastError\":null");
  }

  @Test
  void heartbeat_reports_the_installed_step_types_and_schemas() throws Exception {
    io.gravitee.singularitee.engine.api.pipeline.executor.StepExecutor<?> executor = (
      stepId,
      config,
      ctx
    ) -> io.reactivex.rxjava3.core.Maybe.empty();
    var plugins = new io.gravitee.singularitee.plugin.api.StepPlugins(
      Map.of("infer", executor, "http_tool", executor, "noop", executor),
      Map.of(),
      Map.of("http_tool", "{\"type\":\"object\"}"),
      List.of(),
      Map.of()
    );
    var models = new ModelRegistry();
    var component = new ReleaseSyncComponent(
      config(Map.of()),
      models,
      new PipelineRegistry(models),
      new ReadinessState(),
      new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority(),
      plugins
    );
    component.setHardwareForTest(new HardwareInfo("Linux", null, List.of()));
    component.setClientForTest(client(), 1, false, () -> {});

    component.heartbeat();

    assertThat(component.snapshot().steps()).containsExactly(
      new NodeStatus.StepTypeState("http_tool", false, "{\"type\":\"object\"}"),
      new NodeStatus.StepTypeState("infer", true, null),
      new NodeStatus.StepTypeState("noop", false, null)
    );
    assertThat(statusBody.get())
      .contains(
        "\"steps\":[{\"type\":\"http_tool\",\"core\":false,\"schema\":\"{\\\"type\\\":\\\"object\\\"}\"}"
      )
      .contains("{\"type\":\"infer\",\"core\":true,\"schema\":null}");
  }

  @Test
  void a_node_without_step_plugins_reports_no_step_types() {
    var component = component(config(Map.of()));

    assertThat(component.snapshot().steps()).isEmpty();
  }

  @Test
  void heartbeat_carries_last_sync_error() throws Exception {
    var component = component(config(Map.of()));
    server.stop(0);
    component.setClientForTest(client(), 1, false, () -> {});

    component.poll();

    assertThat(component.snapshot().lastError()).isNotBlank();
  }

  @Test
  void a_heartbeat_answer_sets_the_expected_trace_key() {
    var authority = new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority();
    var component = component(config(Map.of()), authority);
    component.setClientForTest(client(), 1, false, () -> {});
    statusAnswer = "{\"traceKeySha256\":\"" + ReleaseSyncClient.sha256("k-1") + "\"}";

    component.heartbeat();

    assertThat(authority.allows("k-1")).isTrue();
    assertThat(authority.allows("k-2")).isFalse();
  }

  @Test
  void an_empty_heartbeat_answer_changes_nothing() {
    var authority = new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority();
    authority.expect(ReleaseSyncClient.sha256("k-1"));
    var component = component(config(Map.of()), authority);
    component.setClientForTest(client(), 1, false, () -> {});

    component.heartbeat();

    assertThat(authority.allows("k-1")).isTrue();
  }

  @Test
  void heartbeat_failure_does_not_throw() {
    var component = component(config(Map.of()));
    server.stop(0);
    component.setClientForTest(client(), 1, false, () -> {});

    component.heartbeat();
  }

  @Test
  void start_hands_the_synced_workspace_over_and_leaves_a_local_one_alone() throws Exception {
    var config = config(
      Map.of(
        "ai.sync.enabled",
        true,
        "ai.sync.url",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "ai.sync.token",
        "t",
        "ai.sync.cache-dir",
        cache.toString(),
        "ai.workspace.path",
        "/somewhere/examples/qwen3-0.6b.yaml"
      )
    );

    component(config).start();

    // The loader reads this key first, so a local ai.workspace.path (which run-server.sh always sets) cannot win.
    assertThat(System.getProperty(ReleaseSyncComponent.SYNCED_WORKSPACE_PROPERTY))
      .startsWith(cache.toString())
      .endsWith("release.yaml");
    assertThat(System.getProperty("ai.workspace.path")).isNull();
  }

  @Test
  void start_does_nothing_when_disabled() throws Exception {
    var config = config(Map.of("ai.sync.enabled", false));

    component(config).start();

    assertThat(System.getProperty(ReleaseSyncComponent.SYNCED_WORKSPACE_PROPERTY)).isNull();
  }

  @Test
  void start_fails_when_enabled_without_url() {
    var config = config(Map.of("ai.sync.enabled", true));

    assertThatThrownBy(() -> component(config).start()).hasMessageContaining("ai.sync.url");
  }

  /** Minimal Configuration backed by a map: absent keys give null or the supplied default. */
  @SuppressWarnings("unchecked")
  private static Configuration config(Map<String, Object> values) {
    return (Configuration) Proxy.newProxyInstance(
      Configuration.class.getClassLoader(),
      new Class<?>[] { Configuration.class },
      (proxy, method, args) -> {
        if (!method.getName().equals("getProperty")) {
          return null;
        }
        Object value = values.get(args[0]);
        if (args.length == 3) {
          return value != null ? value : args[2];
        }
        return value == null ? null : value.toString();
      }
    );
  }
}
