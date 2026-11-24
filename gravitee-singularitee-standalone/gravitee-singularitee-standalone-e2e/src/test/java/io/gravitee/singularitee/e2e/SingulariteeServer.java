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
package io.gravitee.singularitee.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A Singularitee server driven over its OpenAI-compatible HTTP API: either the built
 * distribution started through {@code run-server.sh} on a workspace, or a server already
 * running somewhere, which is how a CUDA or vLLM image is tested.
 */
public final class SingulariteeServer implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final Process process;
  private final Path log;

  private SingulariteeServer(String baseUrl, Process process, Path log) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.process = process;
    this.log = log;
  }

  /** A server that is already running at {@code baseUrl} (no {@code /v1} suffix). */
  public static SingulariteeServer attach(String baseUrl) {
    return new SingulariteeServer(baseUrl, null, null);
  }

  /**
   * Starts the built distribution on {@code workspace} and waits until {@code /v1/models}
   * lists every id in {@code expectedIds}.
   *
   * @throws IllegalStateException when the distribution is not built, the server exits, or the
   *     ids are not published in time; the message carries the end of the server log
   */
  public static SingulariteeServer start(Path workspace, List<String> expectedIds)
    throws IOException, InterruptedException {
    Path root = repositoryRoot();
    Path launcher = root.resolve("run-server.sh");
    Path distribution = root.resolve(
      "gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution"
    );
    if (!Files.isExecutable(distribution.resolve("bin/gravitee.sh"))) {
      throw new IllegalStateException(
        "Distribution not built at " + distribution + ": run mvn install -DskipTests first"
      );
    }
    int httpPort = freePort();
    Path logs = Files.createDirectories(Path.of("target", "e2e-logs"));
    Path log = logs.resolve(workspace.getFileName().toString().replace(".yaml", "") + ".log");

    var builder = new ProcessBuilder(
      launcher.toString(),
      "--port",
      Integer.toString(httpPort),
      "--workspace",
      workspace.toAbsolutePath().toString()
    );
    builder.directory(root.toFile());
    builder.environment().put("GRAVITEE_GRPC_PORT", Integer.toString(freePort()));
    builder.environment().put("GRAVITEE_SERVICES_CORE_HTTP_PORT", Integer.toString(freePort()));
    // A model that fails to load must fail the boot, not leave a healthy empty server.
    builder.environment().put("GRAVITEE_AI_WORKSPACE_STRICT", "true");
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());

    var server = new SingulariteeServer("http://127.0.0.1:" + httpPort, builder.start(), log);
    try {
      server.awaitModels(expectedIds);
    } catch (RuntimeException | InterruptedException e) {
      server.close();
      throw e;
    }
    return server;
  }

  /** POSTs a JSON body to a path under the base URL and returns the parsed 200 answer. */
  public JsonNode post(String path, JsonNode body) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
      .timeout(REQUEST_TIMEOUT)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
      .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
        "POST " + path + " answered " + response.statusCode() + ": " + response.body()
      );
    }
    return MAPPER.readTree(response.body());
  }

  /** The first result of {@code POST /v1/classify} for one input. */
  public JsonNode classify(String model, String input) throws IOException, InterruptedException {
    ObjectNode body = MAPPER.createObjectNode().put("model", model).put("input", input);
    return post("/v1/classify", body).path("results").path(0);
  }

  /** The answer of a non-streaming, non-stored {@code POST /v1/responses} for one text input. */
  public JsonNode respond(String model, String input) throws IOException, InterruptedException {
    ObjectNode body = MAPPER.createObjectNode()
      .put("model", model)
      .put("input", input)
      .put("store", false);
    return post("/v1/responses", body);
  }

  /** Whether {@code /v1/models} lists {@code id}. */
  public boolean publishes(String id) throws IOException, InterruptedException {
    return publishedIds().contains(id);
  }

  @Override
  public void close() {
    if (process == null) {
      return;
    }
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitModels(List<String> expectedIds) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(E2e.bootTimeoutSeconds()).toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new IllegalStateException("Server exited during boot.\n" + logTail());
      }
      try {
        if (publishedIds().containsAll(expectedIds)) {
          return;
        }
      } catch (IOException | IllegalStateException notReadyYet) {
        // Ports bind before models load: keep polling.
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException(
      "Server did not publish " +
        expectedIds +
        " within " +
        E2e.bootTimeoutSeconds() +
        "s.\n" +
        logTail()
    );
  }

  private List<String> publishedIds() throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IllegalStateException("GET /v1/models answered " + response.statusCode());
    }
    return MAPPER.readTree(response.body()).path("data").findValuesAsText("id");
  }

  private String logTail() {
    try {
      List<String> lines = Files.readAllLines(log);
      return (
        "Last lines of " +
        log.toAbsolutePath() +
        ":\n" +
        String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()))
      );
    } catch (IOException e) {
      return "Server log unreadable: " + log;
    }
  }

  private static Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("run-server.sh"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
        "run-server.sh not found above " + Path.of("").toAbsolutePath()
      );
    }
    return dir;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
