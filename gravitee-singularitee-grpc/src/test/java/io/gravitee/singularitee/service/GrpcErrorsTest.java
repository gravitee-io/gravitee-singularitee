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

import io.gravitee.singularitee.engine.api.ClientErrors;
import io.vertx.core.Future;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What a unary RPC failure looks like on the wire. */
class GrpcErrorsTest {

  @Test
  void unknown_model_is_not_found_with_its_message() {
    var status = GrpcErrors.toStatus(Future.failedFuture("Model not found: nope").cause());
    assertThat(status.status()).isEqualTo(GrpcStatus.NOT_FOUND);
    assertThat(status.message()).isEqualTo("Model not found: nope");
  }

  @Test
  void unknown_pipeline_is_not_found() {
    var status = GrpcErrors.toStatus(Future.failedFuture("Pipeline not found: p").cause());
    assertThat(status.status()).isEqualTo(GrpcStatus.NOT_FOUND);
  }

  @Test
  void wrong_model_kind_is_an_invalid_argument() {
    var status = GrpcErrors.toStatus(
      Future.failedFuture("Model 'llm' is not an embedding model").cause()
    );
    assertThat(status.status()).isEqualTo(GrpcStatus.INVALID_ARGUMENT);
    assertThat(status.message()).isEqualTo("Model 'llm' is not an embedding model");
  }

  @Test
  void rejected_argument_is_an_invalid_argument() {
    var status = GrpcErrors.toStatus(new IllegalArgumentException("vectors differ in length"));
    assertThat(status.status()).isEqualTo(GrpcStatus.INVALID_ARGUMENT);
    assertThat(status.message()).isEqualTo("vectors differ in length");
  }

  @Test
  void other_caller_message_keeps_its_text() {
    var status = GrpcErrors.toStatus(Future.failedFuture("Model x is not available").cause());
    assertThat(status.status()).isEqualTo(GrpcStatus.UNKNOWN);
    assertThat(status.message()).isEqualTo("Model x is not available");
  }

  @Test
  void internal_error_is_internal_without_its_detail() {
    var status = GrpcErrors.toStatus(new IllegalStateException("/secret/path/model.gguf"));
    assertThat(status.status()).isEqualTo(GrpcStatus.INTERNAL);
    assertThat(status.message()).startsWith("Internal error (ref ").doesNotContain("/secret");
  }

  @Test
  void already_sanitized_error_is_internal_and_keeps_its_reference() {
    var sanitized = ClientErrors.forClient(new IllegalStateException("boom"), "Internal error");
    var status = GrpcErrors.toStatus(sanitized);
    assertThat(status.status()).isEqualTo(GrpcStatus.INTERNAL);
    assertThat(status.message()).isEqualTo(sanitized.getMessage());
  }

  @Test
  void status_exception_passes_through() {
    var original = new StatusException(GrpcStatus.PERMISSION_DENIED, "no");
    assertThat(GrpcErrors.toStatus(original)).isSameAs(original);
  }

  @Test
  void reporting_status_keeps_success_and_converts_failure() {
    var failure = new AtomicReference<Throwable>();
    var result = new AtomicReference<String>();
    var response = GrpcErrors.<String>reportingStatus((r, e) -> {
      result.set(r);
      failure.set(e);
    });

    response.complete("ok", null);
    assertThat(result.get()).isEqualTo("ok");
    assertThat(failure.get()).isNull();

    response.complete(null, Future.failedFuture("Model not found: x").cause());
    assertThat(failure.get()).isInstanceOf(StatusException.class);
    assertThat(((StatusException) failure.get()).status()).isEqualTo(GrpcStatus.NOT_FOUND);
  }
}
