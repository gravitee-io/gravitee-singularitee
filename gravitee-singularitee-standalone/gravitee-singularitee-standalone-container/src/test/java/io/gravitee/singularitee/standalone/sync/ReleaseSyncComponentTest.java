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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.singularitee.engine.api.pipeline.model.ModelBoundConfig;
import io.gravitee.singularitee.engine.api.pipeline.model.StepConfigCodec;
import io.gravitee.singularitee.engine.api.registry.ModelRegistry;
import io.gravitee.singularitee.engine.api.registry.PipelineRegistry;
import io.gravitee.singularitee.engine.template.JinjaTemplateRenderer;
import io.gravitee.singularitee.plugin.api.StepPlugins;
import io.gravitee.singularitee.standalone.vertx.ReadinessState;
import io.gravitee.singularitee.workspace.YamlWorkspaceLoader;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path cache;

  private HttpServer server;
  private volatile int revision = 1;
  private volatile String releaseYaml = YAML;
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
        String yaml = releaseYaml;
        byte[] body = JSON.writeValueAsBytes(
          Map.of("revision", revision, "checksum", ReleaseSyncClient.sha256(yaml), "yaml", yaml)
        );
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
  void a_non_hex_trace_key_hash_withdraws_tracing() {
    var authority = new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority();
    var component = component(config(Map.of()), authority);
    component.setClientForTest(client(), 1, false, () -> {});
    statusAnswer = "{\"traceKeySha256\":\"" + ReleaseSyncClient.sha256("k-1") + "\"}";
    component.heartbeat();
    assertThat(authority.allows("k-1")).isTrue();

    statusAnswer = "{\"traceKeySha256\":\"not a hex digest\"}";
    component.heartbeat();

    assertThat(authority.allows("k-1")).isFalse();
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

  private record Echo(String text) {}

  private record Bound(String modelId) implements ModelBoundConfig {}

  private static final Map<String, StepConfigCodec<?>> CODECS = Map.of(
    "echo",
    (stepId, config, ctx) ->
      new Echo(
        config.containsKey("template")
          ? ctx.templates().get((String) config.get("template"))
          : String.valueOf(config.get("text"))
      ),
    "bound",
    (stepId, config, ctx) -> new Bound((String) config.get("model_id")),
    "broken",
    (stepId, config, ctx) -> {
      throw new IllegalStateException("codec failure");
    }
  );

  private static String workspace(String templates, String pipelines) {
    return "workspace:\n  name: demo\n" + templates + "  pipelines:\n" + pipelines;
  }

  private static String echo(String id, String key, String value) {
    return (
      "    - id: " +
      id +
      "\n      entry: s\n      steps:\n        - id: s\n          type: echo\n          config:\n            " +
      key +
      ": " +
      value +
      "\n"
    );
  }

  private static String step(String id, String type, String config) {
    return (
      "    - id: " +
      id +
      "\n      entry: s\n      steps:\n        - id: s\n          type: " +
      type +
      "\n          config:\n            " +
      config +
      "\n"
    );
  }

  private static String greeting(String content) {
    return "  templates:\n    - id: greeting\n      content: \"" + content + "\"\n";
  }

  private static final String V1 = workspace("", echo("chat", "text", "v1"));
  private static final String V2 = workspace(
    "",
    echo("chat", "text", "v2") + echo("summary", "text", "s1")
  );

  private record Node(
    ReleaseSyncComponent component,
    PipelineRegistry pipelines,
    ReadinessState readiness
  ) {}

  /** A node that booted on {@code bootYaml}: its pipelines registered as the workspace loader does. */
  private Node node(String bootYaml) throws Exception {
    var models = new ModelRegistry();
    var pipelines = new PipelineRegistry(models);
    var readiness = new ReadinessState();
    var component = new ReleaseSyncComponent(
      config(Map.of()),
      models,
      pipelines,
      readiness,
      new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority(),
      new StepPlugins(Map.of(), CODECS, Map.of(), List.of(), Map.of())
    );
    component.setHardwareForTest(new HardwareInfo("Linux", null, List.of()));
    for (var pipeline : YamlWorkspaceLoader.loadFromString(
      bootYaml,
      null,
      null,
      CODECS
    ).pipelines()) {
      pipelines.register(pipeline);
    }
    readiness.markReady();
    return new Node(component, pipelines, readiness);
  }

  private static String text(PipelineRegistry pipelines, String id) {
    return ((Echo) pipelines.get(id).orElseThrow().pipeline().steps().get(0).config()).text();
  }

  @Test
  void a_pipeline_only_release_is_applied_in_place() throws Exception {
    var restarts = new AtomicInteger();
    var node = node(V1);
    var client = client();
    node.component().setClientForTest(client, 1, V1, true, restarts::incrementAndGet);
    var started = node.pipelines().get("chat").orElseThrow();
    releaseYaml = V2;
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(0);
    assertThat(text(node.pipelines(), "chat")).isEqualTo("v2");
    assertThat(text(node.pipelines(), "summary")).isEqualTo("s1");
    assertThat(((Echo) started.pipeline().steps().get(0).config()).text()).isEqualTo("v1");
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(2);
    assertThat(node.component().snapshot().lastError()).isNull();
    assertThat(client.cachedRevision()).isEqualTo(2);
    assertThat(Files.readString(client.cachedWorkspace().orElseThrow())).isEqualTo(V2);

    node.component().heartbeat();
    assertThat(statusBody.get()).contains("\"appliedRevision\":2").contains("\"id\":\"summary\"");

    node.component().poll();
    assertThat(restarts).hasValue(0);
  }

  @Test
  void a_release_that_changes_a_model_still_exits_with_75() throws Exception {
    var restarts = new AtomicInteger();
    var node = node(V1);
    var client = client();
    node.component().setClientForTest(client, 1, V1, true, restarts::incrementAndGet);
    releaseYaml = V2.replace(
      "  pipelines:\n",
      "  models:\n    - id: llm\n      name: org/llm\n      type: llama_cpp\n  pipelines:\n"
    );
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(1);
    assertThat(client.cachedRevision()).isEqualTo(2);
    assertThat(text(node.pipelines(), "chat")).isEqualTo("v1");
    assertThat(node.pipelines().get("summary")).isEmpty();
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(1);
  }

  @Test
  void a_template_only_release_re_renders_with_the_new_template() throws Exception {
    var restarts = new AtomicInteger();
    String running = workspace(greeting("Hello {{ name }}"), echo("chat", "template", "greeting"));
    var node = node(running);
    node.component().setClientForTest(client(), 1, running, true, restarts::incrementAndGet);
    var renderer = new JinjaTemplateRenderer();
    assertThat(
      renderer.render(text(node.pipelines(), "chat"), "chat", Map.of("name", "Ada"))
    ).isEqualTo("Hello Ada");
    releaseYaml = running.replace("Hello", "Bye");
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(0);
    assertThat(
      renderer.render(text(node.pipelines(), "chat"), "chat", Map.of("name", "Ada"))
    ).isEqualTo("Bye Ada");
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(2);
  }

  @Test
  void a_release_whose_pipeline_fails_to_load_keeps_the_old_pipelines_and_reports_why()
    throws Exception {
    var restarts = new AtomicInteger();
    var node = node(V1);
    var client = client();
    node.component().setClientForTest(client, 1, V1, true, restarts::incrementAndGet);
    var before = node.pipelines().get("chat").orElseThrow();
    releaseYaml = workspace(
      "",
      echo("chat", "text", "v2") + step("judge", "bound", "model_id: missing_model")
    );
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(0);
    assertThat(node.pipelines().get("chat").orElseThrow()).isSameAs(before);
    assertThat(node.pipelines().get("judge")).isEmpty();
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(1);
    assertThat(node.component().snapshot().lastError()).contains("missing_model");
    assertThat(client.cachedRevision()).isNotEqualTo(2);

    node.component().heartbeat();
    assertThat(statusBody.get()).contains("\"appliedRevision\":1").contains("missing_model");
  }

  @Test
  void a_pipeline_the_loader_skips_fails_the_whole_release() throws Exception {
    var restarts = new AtomicInteger();
    var node = node(V1);
    node.component().setClientForTest(client(), 1, V1, true, restarts::incrementAndGet);
    var before = node.pipelines().get("chat").orElseThrow();
    releaseYaml = workspace("", echo("chat", "text", "v2") + step("flaky", "broken", "x: 1"));
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(0);
    assertThat(node.pipelines().get("chat").orElseThrow()).isSameAs(before);
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(1);
    assertThat(node.component().snapshot().lastError()).contains("flaky");
  }

  @Test
  void a_pipeline_only_release_waits_until_the_workspace_has_loaded() throws Exception {
    var restarts = new AtomicInteger();
    var models = new ModelRegistry();
    var pipelines = new PipelineRegistry(models);
    var readiness = new ReadinessState();
    var component = new ReleaseSyncComponent(
      config(Map.of()),
      models,
      pipelines,
      readiness,
      new io.gravitee.singularitee.engine.pipeline.trace.TraceKeyAuthority(),
      new StepPlugins(Map.of(), CODECS, Map.of(), List.of(), Map.of())
    );
    var client = client();
    component.setClientForTest(client, 1, V1, true, restarts::incrementAndGet);
    releaseYaml = V2;
    revision = 2;

    component.poll();

    assertThat(restarts).hasValue(0);
    assertThat(pipelines.list()).isEmpty();
    assertThat(client.cachedRevision()).isNotEqualTo(2);

    readiness.markReady();
    component.poll();

    assertThat(pipelines.get("summary")).isPresent();
    assertThat(client.cachedRevision()).isEqualTo(2);
  }

  @Test
  void a_pipeline_only_release_is_applied_in_place_without_restart_on_change() throws Exception {
    var restarts = new AtomicInteger();
    var node = node(V1);
    node.component().setClientForTest(client(), 1, V1, false, restarts::incrementAndGet);
    releaseYaml = V2;
    revision = 2;

    node.component().poll();

    assertThat(restarts).hasValue(0);
    assertThat(text(node.pipelines(), "chat")).isEqualTo("v2");
    assertThat(node.component().snapshot().appliedRevision()).isEqualTo(2);
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
