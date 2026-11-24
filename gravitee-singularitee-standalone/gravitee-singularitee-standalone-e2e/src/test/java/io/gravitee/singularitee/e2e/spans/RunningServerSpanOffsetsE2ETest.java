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
package io.gravitee.singularitee.e2e.spans;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gravitee.singularitee.e2e.SingulariteeServer;
import java.util.List;

/**
 * Span contract of a server that is already running, whatever its backend: a CUDA or vLLM
 * image, a remote deployment. Runs when {@code SINGULARITEE_E2E_URL} holds its HTTP base URL
 * (no {@code /v1}). {@code SINGULARITEE_E2E_MODEL} names the token classifier (default
 * {@code pii}) and {@code SINGULARITEE_E2E_REDACT_PIPELINE} a pipeline shaped like
 * {@code workspaces/pipelines/redact.yaml} (default {@code redact}; the redaction checks are
 * skipped when the server does not publish it).
 */
class RunningServerSpanOffsetsE2ETest extends SpanOffsetsContract {

  private static final String URL = "SINGULARITEE_E2E_URL";

  @Override
  String backend() {
    return "running-server";
  }

  @Override
  String workspace() {
    throw new UnsupportedOperationException("a running server has its own workspace");
  }

  @Override
  List<String> repositories() {
    return List.of();
  }

  @Override
  String model() {
    return env("SINGULARITEE_E2E_MODEL", "pii");
  }

  @Override
  String redactPipeline() {
    return env("SINGULARITEE_E2E_REDACT_PIPELINE", "redact");
  }

  @Override
  SingulariteeServer startServer() {
    String url = System.getenv(URL);
    assumeTrue(url != null && !url.isBlank(), URL + " is not set");
    return SingulariteeServer.attach(url.strip());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}
