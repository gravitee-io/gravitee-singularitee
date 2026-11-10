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
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Pulls the workspace Release of this node's server group from the Gamma module and keeps the last good
 * copy on disk, so a node restarts on its last release when the control plane is unreachable.
 *
 * <p>Wire contract: {@code GET <url>/_sync/release?known=<revision>} with the token
 * (header {@code X-Singularitee-Token}) that names the group (the node never decodes it). {@code 304} means unchanged and {@code 204} that the group has no release yet;
 * {@code 200} carries JSON {@code {revision, checksum, yaml}} where {@code checksum} is the
 * SHA-256 (hex) of {@code yaml}.
 */
public final class ReleaseSyncClient {

  /** A release received from the control plane. */
  public record Release(long revision, String checksum, String yaml) {}

  /** Not {@code Authorization: Bearer}: APIM would try to verify it as a console session. */
  static final String TOKEN_HEADER = "X-Singularitee-Token";

  private static final String YAML_FILE = "release.yaml";
  private static final String REVISION_FILE = "release.revision";

  private final HttpClient http;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI baseUri;
  private final URI releaseUri;
  private final String token;
  private final Path cacheDir;
  private final Duration timeout;

  public ReleaseSyncClient(String baseUrl, String token, Path cacheDir, Duration timeout) {
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.baseUri = URI.create(base);
    this.releaseUri = URI.create(base + "/_sync/release");
    this.token = token;
    this.cacheDir = cacheDir;
    this.timeout = timeout;
  }

  /**
   * Fetches the release if it is newer than {@code knownRevision}.
   *
   * @return the new release, or empty when unchanged (304)
   * @throws IOException on transport failure, an unexpected status or a checksum mismatch
   */
  public Optional<Release> fetch(long knownRevision) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(releaseUri + "?known=" + knownRevision))
      .timeout(timeout)
      .header(TOKEN_HEADER, token)
      .header("Accept", "application/json")
      .GET()
      .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();
    if (status == 304 || status == 204) {
      // 304: unchanged. 204: the group has no release yet.
      return Optional.empty();
    }
    if (status != 200) {
      throw new IOException("Release sync failed: HTTP " + status);
    }
    JsonNode body = JSON.readTree(response.body());
    String yaml = body.path("yaml").asText("");
    String checksum = body.path("checksum").asText("");
    String actual = sha256(yaml);
    if (!actual.equalsIgnoreCase(checksum)) {
      throw new IOException(
        "Release checksum mismatch: expected " + checksum + " but got " + actual
      );
    }
    if (!body.hasNonNull("revision")) {
      throw new IOException("Release response is missing revision");
    }
    return Optional.of(new Release(body.get("revision").asLong(), actual, yaml));
  }

  /**
   * Reports this node's state: {@code POST <url>/_sync/instances/<id>/status} with a JSON body.
   *
   * @throws IOException on transport failure or a non-2xx status
   */
  public void reportStatus(String instanceId, Object status)
    throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(
      URI.create(
        baseUri +
          "/_sync/instances/" +
          URLEncoder.encode(instanceId, StandardCharsets.UTF_8) +
          "/status"
      )
    )
      .timeout(timeout)
      .header(TOKEN_HEADER, token)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(status)))
      .build();
    int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (code / 100 != 2) {
      throw new IOException("Status report failed: HTTP " + code);
    }
  }

  /** Writes the release atomically and returns the workspace file path. */
  public Path store(Release release) throws IOException {
    Files.createDirectories(cacheDir);
    Path yaml = cacheDir.resolve(YAML_FILE);
    atomicWrite(yaml, release.yaml());
    atomicWrite(cacheDir.resolve(REVISION_FILE), Long.toString(release.revision()));
    return yaml;
  }

  /** The cached workspace file, if a release was stored before. */
  public Optional<Path> cachedWorkspace() {
    Path yaml = cacheDir.resolve(YAML_FILE);
    return Files.isRegularFile(yaml) ? Optional.of(yaml) : Optional.empty();
  }

  /** Revision of the cached release, or {@code 0} when none. */
  public long cachedRevision() {
    try {
      return Long.parseLong(Files.readString(cacheDir.resolve(REVISION_FILE)).trim());
    } catch (IOException | NumberFormatException e) {
      return 0L;
    }
  }

  private static void atomicWrite(Path target, String content) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  static String sha256(String content) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))
      );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
