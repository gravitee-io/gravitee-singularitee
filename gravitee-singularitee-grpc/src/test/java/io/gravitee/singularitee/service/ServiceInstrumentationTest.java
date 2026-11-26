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
package io.gravitee.singularitee.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.singularitee.engine.api.metrics.InferenceMetrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.vertx.core.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The span a direct unary RPC opens: OK when it answers, ERROR with the exception when not. */
class ServiceInstrumentationTest {

  private RecordingTracer tracing;
  private ServiceInstrumentation instrumentation;

  @BeforeEach
  void setUp() throws Exception {
    tracing = new RecordingTracer();
    instrumentation = new ServiceInstrumentation(
      tracing.vertx(),
      tracing.tracer(),
      new InferenceMetrics(null)
    );
  }

  @AfterEach
  void tearDown() {
    tracing.close();
  }

  private void callOnRequestContext(Supplier<Future<String>> work) throws Exception {
    var done = new CompletableFuture<Void>();
    tracing
      .context()
      .runOnContext(v ->
        instrumentation
          .traceUnary("singularitee.embed", "embed", "e5", work)
          .onComplete(ar -> done.complete(null))
      );
    done.get(10, TimeUnit.SECONDS);
  }

  @Test
  void an_answered_call_is_ok() throws Exception {
    callOnRequestContext(() -> Future.succeededFuture("vector"));

    assertThat(tracing.span("singularitee.embed").getStatus().getStatusCode()).isEqualTo(
      StatusCode.OK
    );
  }

  @Test
  void a_failed_call_is_an_error_with_its_exception() throws Exception {
    callOnRequestContext(() -> Future.failedFuture(new IllegalStateException("no such model")));

    var span = tracing.span("singularitee.embed");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("exception.message"))).isEqualTo(
      "no such model"
    );
    assertThat(span.getEvents()).isEmpty();
  }
}
