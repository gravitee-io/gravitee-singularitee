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
import io.gravitee.singularitee.inference.api.ClientFacingException;
import io.vertx.core.Future;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.StatusException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What a unary RPC failure looks like on the wire. */
class GrpcErrorsTest {

  @Test
  void unknown_model_is_not_found_with_its_message() {
    var status = GrpcErrors.toStatus(ClientFacingException.modelNotFound("nope"));
    assertThat(status.status()).isEqualTo(GrpcStatus.NOT_FOUND);
    assertThat(status.message()).isEqualTo("Model not found: nope");
  }

  @Test
  void unknown_pipeline_is_not_found() {
    var status = GrpcErrors.toStatus(ClientFacingException.pipelineNotFound("p"));
    assertThat(status.status()).isEqualTo(GrpcStatus.NOT_FOUND);
  }

  @Test
  void wrong_model_kind_is_an_invalid_argument() {
    var status = GrpcErrors.toStatus(
      ClientFacingException.unsupportedModel("Model 'llm' is not an embedding model")
    );
    assertThat(status.status()).isEqualTo(GrpcStatus.INVALID_ARGUMENT);
    assertThat(status.message()).isEqualTo("Model 'llm' is not an embedding model");
  }

  @Test
  void rejected_argument_is_an_invalid_argument() {
    var status = GrpcErrors.toStatus(ClientFacingException.invalid("vectors differ in length"));
    assertThat(status.status()).isEqualTo(GrpcStatus.INVALID_ARGUMENT);
    assertThat(status.message()).isEqualTo("vectors differ in length");
  }

  @Test
  void the_kind_picks_the_status_not_the_message() {
    var status = GrpcErrors.toStatus(ClientFacingException.invalid("Model not found: reworded"));
    assertThat(status.status()).isEqualTo(GrpcStatus.INVALID_ARGUMENT);
  }

  @Test
  void a_plain_illegal_argument_is_internal() {
    var status = GrpcErrors.toStatus(
      new IllegalArgumentException("Illegal char <:> at index 2: C::/models/secret")
    );
    assertThat(status.status()).isEqualTo(GrpcStatus.INTERNAL);
    assertThat(status.message()).startsWith("Internal error (ref ").doesNotContain("secret");
  }

  @Test
  void a_bare_failed_future_is_internal() {
    var status = GrpcErrors.toStatus(Future.failedFuture("Connection reset by 10.0.0.4").cause());
    assertThat(status.status()).isEqualTo(GrpcStatus.INTERNAL);
    assertThat(status.message()).doesNotContain("10.0.0.4");
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

    response.complete(null, ClientFacingException.modelNotFound("x"));
    assertThat(failure.get()).isInstanceOf(StatusException.class);
    assertThat(((StatusException) failure.get()).status()).isEqualTo(GrpcStatus.NOT_FOUND);
  }
}
